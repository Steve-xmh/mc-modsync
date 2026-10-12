#!/usr/bin/env bash
# Full-stack test: the real ModSync publishing server + the real client agent over HTTP.
#
# Covers: manifest generation (--once), excludes, extra directory, health, ETag/304, content
# addressed download byte-equality, 404/400 handling, and the agent's 304 fast path.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_JAR="$ROOT/build/modsync-server.jar"
AGENT_JAR="$ROOT/build/modsync-agent.jar"
PORT="${MODSYNC_SERVER_TEST_PORT:-25872}"
BASE="http://127.0.0.1:$PORT"
URL="$BASE/modsync"
MANIFEST_URL="$URL/manifest.json"

PASS=0
FAILED=0
pass() { PASS=$((PASS + 1)); echo "  ok   - $*"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL - $*"; }
assert_eq() { if [ "$1" = "$2" ]; then pass "$3 (= $1)"; else fail "$3: 期望 [$1] 实际 [$2]"; fi; }
assert_contains() { if grep -qF -- "$2" "$1"; then pass "$3"; else fail "$3: '$1' 中未找到 '$2'"; fi; }
assert_not_contains() { if grep -qF -- "$2" "$1"; then fail "$3: 不应出现 '$2'"; else pass "$3"; fi; }
assert_file_exists() { if [ -f "$1" ]; then pass "$2"; else fail "$2: 缺少 $1"; fi; }
say() { echo; echo "== $*"; }

# BSD stat (macOS) vs GNU stat (Linux)
if [ "$(uname -s)" = "Darwin" ]; then
  file_mtime() { stat -f %m "$1"; }
  file_size() { stat -f %z "$1"; }
else
  file_mtime() { stat -c %Y "$1"; }
  file_size() { stat -c %s "$1"; }
fi
has_file() { [ -n "$(find "$1" -name "$2" -print 2>/dev/null | head -1)" ]; }

for jar in "$SERVER_JAR" "$AGENT_JAR"; do
  [ -f "$jar" ] || { echo "缺少 ${jar}，请先运行 ./build.sh" >&2; exit 1; }
done

WS="$(mktemp -d /tmp/modsync-server-e2e.XXXXXX)"
SRV="$WS/srv"
MODS="$SRV/mods"
EXTRA="$SRV/extra"
GAME="$WS/game"
mkdir -p "$MODS" "$EXTRA" "$GAME"

SERVER_PID=""
cleanup() { [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null; }
trap cleanup EXIT

make_mod() { bash "$ROOT/test/make-mod.sh" "$@" >/dev/null; }

echo "工作目录: $WS"
say "准备服务端模组（含一个被 exclude 的敏感模组）"
make_mod "$MODS/create-1.0.jar" create "server-create"
make_mod "$MODS/jei-1.0.jar" jei "server-jei"
make_mod "$MODS/secret-internal-1.0.jar" secretinternal "should not be published"
make_mod "$EXTRA/sodium-client-1.0.jar" sodium "client-only from extra dir"

say "--once 生成清单并校验结构"
java -jar "$SERVER_JAR" --mods "$MODS" --extra "$EXTRA" --exclude 'secret-*.jar' \
  --port "$PORT" --bind 127.0.0.1 --base-url "$URL" --pack-name "E2E Pack" \
  --minecraft-version 1.21.1 --loader-version 21.1.248 \
  --state "$SRV/state.json" --once >"$WS/manifest-once.json" 2>"$WS/once.err"
assert_eq "0" "$?" "--once 退出码为 0"

python3 - "$WS/manifest-once.json" <<'PY' >"$WS/once-check.txt" 2>&1
import json, sys
m = json.load(open(sys.argv[1]))
paths = [f["path"] for f in m["files"]]
assert m["schema"] == 1, m["schema"]
assert m["packName"] == "E2E Pack", m["packName"]
assert m["baseUrl"] == "http://127.0.0.1:25872/modsync", m["baseUrl"]
assert sorted(paths) == paths, "files 未按 path 排序"
assert paths == ["mods/create-1.0.jar", "mods/jei-1.0.jar", "mods/sodium-client-1.0.jar"], paths
assert m["minecraft"] == "1.21.1", m.get("minecraft")
assert m["loader"]["type"] == "neoforge" and m["loader"]["version"] == "21.1.248", m.get("loader")
by_name = {f["path"].split("/")[-1]: f for f in m["files"]}
assert by_name["create-1.0.jar"]["modId"] == "create", by_name["create-1.0.jar"]
assert len(by_name["create-1.0.jar"]["sha256"]) == 64
assert by_name["create-1.0.jar"]["size"] > 0
print("manifest-ok")
PY
if grep -q "manifest-ok" "$WS/once-check.txt"; then
  pass "清单结构/schema/排序/modId/exclude 全部正确"
else
  fail "清单结构断言失败: $(cat "$WS/once-check.txt")"
fi
assert_not_contains "$WS/manifest-once.json" "secret-internal" "被 exclude 的模组未进入清单"

say "启动服务端 http://127.0.0.1:$PORT"
java -jar "$SERVER_JAR" --mods "$MODS" --extra "$EXTRA" --exclude 'secret-*.jar' \
  --port "$PORT" --bind 127.0.0.1 --base-url "$URL" --pack-name "E2E Pack" \
  --state "$SRV/state.json" >"$WS/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 50); do
  curl -sf "$URL/health" -o /dev/null && break
  sleep 0.2
done
if ! curl -sf "$URL/health" -o /dev/null; then
  echo "服务端启动失败" >&2; cat "$WS/server.log" >&2; exit 1
fi

say "health / manifest / ETag / 304"
curl -s "$URL/health" -o "$WS/health.json"
assert_contains "$WS/health.json" '"status":"ok"' "health 返回 ok"
assert_contains "$WS/health.json" '"files":3' "health 报告 3 个文件"

curl -s -D "$WS/manifest.headers" "$MANIFEST_URL" -o "$WS/manifest.json"
ETAG="$(grep -i '^etag:' "$WS/manifest.headers" | tr -d '\r' | sed 's/^[Ee][Tt][Aa][Gg]: *//')"
if [ -n "$ETAG" ]; then pass "manifest 返回 ETag ($ETAG)"; else fail "manifest 缺少 ETag"; fi
CODE="$(curl -s -o /dev/null -w '%{http_code}' -H "If-None-Match: $ETAG" "$MANIFEST_URL")"
assert_eq "304" "$CODE" "If-None-Match 命中返回 304"

say "文件下载：按 sha256 内容寻址"
ENTRY="$(python3 -c "
import json,sys
m=json.load(open('$WS/manifest.json'))
e=[f for f in m['files'] if f['path'].endswith('create-1.0.jar')][0]
print(e['sha256'], e['size'])
")"
SHA="$(echo "$ENTRY" | cut -d' ' -f1)"
SIZE="$(echo "$ENTRY" | cut -d' ' -f2)"
curl -s "$URL/files/$SHA" -o "$WS/download.jar"
LOCAL_SHA="$(shasum -a 256 "$WS/download.jar" | cut -d' ' -f1)"
assert_eq "$SHA" "$LOCAL_SHA" "下载内容与清单 sha256 一致"
assert_eq "$SIZE" "$(file_size "$WS/download.jar")" "下载大小与清单一致"
assert_eq "$(shasum -a 256 "$MODS/create-1.0.jar" | cut -d' ' -f1)" "$LOCAL_SHA" "下载内容与源文件一致"
assert_eq "404" "$(curl -s -o /dev/null -w '%{http_code}' "$URL/files/$(printf '0%.0s' {1..64})")" "未知哈希返回 404"
assert_eq "400" "$(curl -s -o /dev/null -w '%{http_code}' "$URL/files/not-a-hash")" "非法哈希返回 400"

say "agent 首次同步（真服务端 + 真 agent）"
java -jar "$AGENT_JAR" --game-dir "$GAME" --manifest-url "$MANIFEST_URL" >"$WS/a1.log" 2>&1
assert_eq "0" "$?" "agent 退出码为 0"
assert_contains "$WS/a1.log" "下载 3" "下载了 3 个文件"
assert_eq "3" "$(ls -1 "$GAME/mods"/*.jar 2>/dev/null | wc -l | tr -d ' ')" "mods 中部署 3 个 jar"
assert_file_exists "$GAME/mods-server/sodium-client-1.0.jar" "extra 目录的客户端模组也同步到 mods-server"

python3 - "$GAME/modsync/state.json" "$WS/manifest.json" <<'PY' >"$WS/state-check.txt" 2>&1
import json, sys
state = json.load(open(sys.argv[1]))
m = json.load(open(sys.argv[2]))
assert state["serverId"] == m["serverId"], (state["serverId"], m["serverId"])
assert state["etag"], "未保存 ETag"
assert len(state["serverFiles"]) == 3, state["serverFiles"]
assert len(state["deployed"]) == 3, state["deployed"]
print("state-ok")
PY
if grep -q "state-ok" "$WS/state-check.txt"; then
  pass "state.json 记录了 serverId / ETag / 文件清单"
else
  fail "state.json 断言失败: $(cat "$WS/state-check.txt")"
fi

say "agent 二次同步：走 ETag 304 快速路径"
java -jar "$AGENT_JAR" --game-dir "$GAME" --manifest-url "$MANIFEST_URL" >"$WS/a2.log" 2>&1
assert_eq "0" "$?" "agent 退出码为 0"
assert_contains "$WS/a2.log" "清单未变化 (HTTP 304)" "agent 命中 304 快速路径"
assert_contains "$WS/a2.log" "下载 0" "增量同步未重复下载"

say "服务端模组更新后 agent 增量拉取"
make_mod "$MODS/jei-1.0.jar" jei "server-jei-v2"
java -jar "$SERVER_JAR" --mods "$MODS" --extra "$EXTRA" --exclude 'secret-*.jar' \
  --port "$PORT" --bind 127.0.0.1 --base-url "$URL" --pack-name "E2E Pack" \
  --state "$SRV/state.json" --write-manifest "$SRV/manifest2.json" --once >/dev/null 2>&1
assert_eq "0" "$?" "--write-manifest 版本生成成功"
kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null
java -jar "$SERVER_JAR" --mods "$MODS" --extra "$EXTRA" --exclude 'secret-*.jar' \
  --port "$PORT" --bind 127.0.0.1 --base-url "$URL" --pack-name "E2E Pack" \
  --state "$SRV/state.json" >>"$WS/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 50); do curl -sf "$URL/health" -o /dev/null && break; sleep 0.2; done
java -jar "$AGENT_JAR" --game-dir "$GAME" --manifest-url "$MANIFEST_URL" >"$WS/a3.log" 2>&1
assert_contains "$WS/a3.log" "下载 1" "只增量下载变化的那一个文件"
assert_eq "$(shasum -a 256 "$MODS/jei-1.0.jar" | cut -d' ' -f1)" \
          "$(shasum -a 256 "$GAME/mods/jei-1.0.jar" | cut -d' ' -f1)" "mods 中的 jar 已更新到新版本"

if [ "${MODSYNC_TEST_ONLINE:-0}" = "1" ]; then
  say "可选(在线)：Modrinth 直链回填 (MODSYNC_TEST_ONLINE=1)"
  ONLINE_DIR="$(mktemp -d /tmp/modsync-online.XXXXXX)"
  mkdir -p "$ONLINE_DIR/mods"
  if python3 - "$ONLINE_DIR" <<'PY'
import json, os, sys, urllib.request
out = sys.argv[1]
versions = json.load(urllib.request.urlopen(
    "https://api.modrinth.com/v2/project/8IFHiuMI/version"
    "?loaders=%5B%22neoforge%22%5D&game_versions=%5B%221.21.1%22%5D", timeout=30))
best = min((f for v in versions for f in v["files"]), key=lambda f: f["size"])
urllib.request.urlretrieve(best["url"], os.path.join(out, "mods", "online-mod.jar"))
print(best["filename"], best["size"])
PY
  then
    java -jar "$SERVER_JAR" --mods "$ONLINE_DIR/mods" --once --modrinth-lookup \
      --base-url "http://127.0.0.1:$PORT/modsync" --state "$ONLINE_DIR/state.json" \
      >"$ONLINE_DIR/manifest.json" 2>"$ONLINE_DIR/err.txt"
    if grep -q 'cdn.modrinth.com' "$ONLINE_DIR/manifest.json"; then
      pass "Modrinth 直链已回填到清单 files[].url"
    else
      fail "清单未回填 url（err: $(tr '\n' ' ' <"$ONLINE_DIR/err.txt")）"
    fi
  else
    fail "在线测试准备失败（无法访问 Modrinth API）"
  fi
  rm -rf "$ONLINE_DIR"
fi

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAILED 项"
echo "日志目录: $WS"
echo "======================================"
[ "$FAILED" -eq 0 ]
