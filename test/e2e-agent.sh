#!/usr/bin/env bash
# End-to-end test for the ModSync client agent, driven by a static HTTP host (no Java server needed).
#
# Covers: first sync, idempotency ("存在就不动"), mods-server update, stale removal to trash,
# client mod coexistence, same-mod-id conflict (server wins), migration of player-dropped jars,
# dry-run, direct-URL downloads and the -javaagent entry point.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_JAR="$ROOT/build/modsync-agent.jar"
PORT="${MODSYNC_TEST_PORT:-25871}"
BASE="http://127.0.0.1:$PORT"
MANIFEST_URL="$BASE/manifest.json"

PASS=0
FAILED=0

pass() { PASS=$((PASS + 1)); echo "  ok   - $*"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL - $*"; }

assert_eq() { if [ "$1" = "$2" ]; then pass "$3 (= $1)"; else fail "$3: 期望 [$1] 实际 [$2]"; fi; }
assert_contains() { if grep -qF -- "$2" "$1"; then pass "$3"; else fail "$3: '$1' 中未找到 '$2'"; fi; }
assert_file_exists() { if [ -f "$1" ]; then pass "$2"; else fail "$2: 缺少 $1"; fi; }
assert_file_absent() { if [ ! -e "$1" ]; then pass "$2"; else fail "$2: 仍然存在 $1"; fi; }
assert_same_content() {
  if [ "$(shasum -a 256 "$1" | cut -d' ' -f1)" = "$(shasum -a 256 "$2" | cut -d' ' -f1)" ]; then
    pass "$3"
  else
    fail "$3: $1 与 $2 内容不一致"
  fi
}
count_jars() { ls -1 "$1"/*.jar 2>/dev/null | wc -l | tr -d ' '; }
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

if [ ! -f "$AGENT_JAR" ]; then
  echo "找不到 $AGENT_JAR，请先运行 ./build.sh" >&2
  exit 1
fi

WS="$(mktemp -d /tmp/modsync-e2e.XXXXXX)"
SANDBOX="$WS/game"
SRC="$WS/servermods"
WWW="$WS/www"
mkdir -p "$SANDBOX" "$SRC" "$WWW"

HTTP_PID=""
cleanup() {
  [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
  wait "$HTTP_PID" 2>/dev/null
}
trap cleanup EXIT

make_mod() { bash "$ROOT/test/make-mod.sh" "$@" >/dev/null; }
regen() { python3 "$ROOT/test/gen-manifest.py" --mods "$SRC" --out "$WWW" --base-url "$BASE" "$@" >/dev/null; }

run_agent() {
  local log="$1"
  shift
  java -jar "$AGENT_JAR" --game-dir "$SANDBOX" --manifest-url "$MANIFEST_URL" --no-gui "$@" >"$log" 2>&1
  return $?
}

echo "工作目录: $WS"

say "准备测试数据"
make_mod "$SRC/create-1.0.jar" create "payload-create-v1"
make_mod "$SRC/sodium-1.0.jar" sodium "payload-sodium-v1"
regen

say "启动静态清单服务器 http://127.0.0.1:$PORT"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WWW" >"$WS/http.log" 2>&1 &
HTTP_PID=$!
for _ in $(seq 1 50); do
  curl -sf "$BASE/manifest.json" -o /dev/null && break
  sleep 0.2
done
if ! curl -sf "$BASE/manifest.json" -o /dev/null; then
  echo "静态服务器启动失败" >&2
  cat "$WS/http.log" >&2
  exit 1
fi

# ---------------------------------------------------------------- T1 首次同步
say "T1 首次同步：把服务器模组下载到 mods-server 并重组 mods"
run_agent "$WS/t1.log"
assert_eq "0" "$?" "agent 退出码为 0"
assert_contains "$WS/t1.log" "下载 2" "下载了 2 个文件"
assert_eq "2" "$(count_jars "$SANDBOX/mods-server")" "mods-server 有 2 个 jar"
assert_eq "2" "$(count_jars "$SANDBOX/mods")" "mods 重组后有 2 个 jar"
assert_same_content "$SANDBOX/mods/create-1.0.jar" "$SANDBOX/mods-server/create-1.0.jar" "mods 是 mods-server 的拷贝"

MTIME_BEFORE="$(file_mtime "$SANDBOX/mods-server/create-1.0.jar")"

# ---------------------------------------------------------------- T2 幂等
say "T2 再次运行：符合的文件不重写、不重组"
run_agent "$WS/t2.log"
assert_eq "0" "$?" "agent 退出码为 0"
assert_contains "$WS/t2.log" "已符合 2 个" "识别出 2 个文件已符合"
assert_contains "$WS/t2.log" "无需重组" "跳过重组"
assert_eq "$MTIME_BEFORE" "$(file_mtime "$SANDBOX/mods-server/create-1.0.jar")" "已符合文件未被重写（mtime 不变）"

# ---------------------------------------------------------------- T3 client 模组共存
say "T3 玩家自装模组（mods-client）参与重组"
make_mod "$SANDBOX/mods-client/extra-1.0.jar" extra "payload-extra"
run_agent "$WS/t3.log"
assert_eq "3" "$(count_jars "$SANDBOX/mods")" "mods 变成 3 个 jar"
assert_file_exists "$SANDBOX/mods/extra-1.0.jar" "自装模组被部署到 mods"
if has_file "$SANDBOX/modsync/trash" 'create-1.0.jar*'; then
  fail "内容未变的服务器模组不应进入 trash（重组应原地保留）"
else
  pass "内容未变的模组原地保留，不产生 trash 垃圾"
fi

# ---------------------------------------------------------------- T4 同 mod id 冲突
say "T4 同 mod id 冲突：以服务器版本为准"
make_mod "$SANDBOX/mods-client/create-mine-1.0.jar" create "payload-client-create"
run_agent "$WS/t4.log"
assert_eq "3" "$(count_jars "$SANDBOX/mods")" "mods 仍为 3 个（冲突项被跳过）"
assert_contains "$WS/t4.log" "以服务器版本为准" "日志说明冲突处理策略"
assert_same_content "$SANDBOX/mods/create-1.0.jar" "$SANDBOX/mods-server/create-1.0.jar" "mods 中保留的是服务器版本"

# ---------------------------------------------------------------- T5 误放进 mods 的模组
say "T5 玩家把模组直接丢进 mods/：迁移到 mods-client 而非删除"
make_mod "$SANDBOX/mods/manual-1.0.jar" manual "payload-manual"
run_agent "$WS/t5.log"
assert_contains "$WS/t5.log" "迁移玩家自装模组" "日志记录迁移动作"
assert_file_exists "$SANDBOX/mods-client/manual-1.0.jar" "文件被迁移到 mods-client"
assert_eq "4" "$(count_jars "$SANDBOX/mods")" "迁移后 mods 为 4 个 jar"

# ---------------------------------------------------------------- T6 服务器更新
say "T6 服务器模组更新：只下载变化的那一个"
make_mod "$SRC/create-1.0.jar" create "payload-create-v2"
regen
run_agent "$WS/t6.log"
assert_contains "$WS/t6.log" "下载 1" "只下载 1 个变化文件"
assert_same_content "$SANDBOX/mods/create-1.0.jar" "$SANDBOX/mods-server/create-1.0.jar" "mods 同步到新版本"

# ---------------------------------------------------------------- T7 服务器删除
say "T7 服务器删除模组：本地移入 trash 并重组"
rm -f "$SRC/sodium-1.0.jar"
regen
run_agent "$WS/t7.log"
assert_file_absent "$SANDBOX/mods-server/sodium-1.0.jar" "mods-server 中已移除"
if has_file "$SANDBOX/modsync/trash" 'sodium-1.0.jar*'; then
  pass "被移除的文件进入 modsync/trash"
else
  fail "trash 中找不到被移除的 sodium-1.0.jar"
fi
assert_eq "3" "$(count_jars "$SANDBOX/mods")" "重组后 mods 为 3 个 jar"

# ---------------------------------------------------------------- T8 dry-run
say "T8 --force --dry-run 只报告不改动"
run_agent "$WS/t8.log" --force --dry-run
assert_contains "$WS/t8.log" "[dry-run]" "输出 dry-run 计划"
assert_eq "3" "$(count_jars "$SANDBOX/mods")" "mods 未被改动"

# ---------------------------------------------------------------- T9 url 直链
say "T9 清单提供 url 直链时走直链下载"
make_mod "$SRC/create-1.0.jar" create "payload-create-v3"
regen --direct-url-for create-1.0.jar
run_agent "$WS/t9.log"
assert_contains "$WS/t9.log" "下载 1" "通过直链下载了更新"
assert_same_content "$SANDBOX/mods/create-1.0.jar" "$SANDBOX/mods-server/create-1.0.jar" "直链下载后内容一致"

# ---------------------------------------------------------------- T9b 篡改检测
say "T9b 本地文件被改动（大小不变）：按 sha256 检出并重新下载"
python3 - "$SANDBOX/mods-server/create-1.0.jar" <<'PY'
import sys
p = sys.argv[1]
data = bytearray(open(p, "rb").read())
data[-1] ^= 0xFF  # 大小不变，内容变了
open(p, "wb").write(bytes(data))
PY
run_agent "$WS/t9b.log"
assert_contains "$WS/t9b.log" "下载 1" "检出哈希不匹配并重新下载"
assert_same_content "$SANDBOX/mods-server/create-1.0.jar" "$SANDBOX/mods/create-1.0.jar" "修复后 mods 与 mods-server 一致"

# ---------------------------------------------------------------- T10 -javaagent
say "T10 -javaagent 模式（PCL 等直接改 JVM 参数）"
java "-javaagent:$AGENT_JAR=--game-dir=$SANDBOX --manifest-url=$MANIFEST_URL --no-gui" -version >"$WS/t10.log" 2>&1
assert_eq "0" "$?" "java -javaagent 启动成功"
assert_contains "$WS/t10.log" "[modsync]" "premain 中执行了同步"
assert_contains "$WS/t10.log" "清单" "premain 中读取了清单"
assert_contains "$WS/t10.log" "openjdk version" "premain 之后 JVM 正常继续（-version）"

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAILED 项"
echo "日志目录: $WS"
echo "======================================"
[ "$FAILED" -eq 0 ]
