#!/usr/bin/env bash
# End-to-end test for the ModSync client agent, driven by a static HTTP host (no Java server needed).
#
# Covers: first sync, idempotency ("存在就不动"), mods-server update, stale removal to trash,
# client mod coexistence, same-mod-id conflict (server wins), migration of player-dropped jars,
# dry-run, direct-URL downloads and the -javaagent entry point.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${JAVA:-java}"
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
  echo "找不到 ${AGENT_JAR}，请先运行 ./build.sh" >&2
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
regens=0
regen() {
  regens=$((regens + 1))
  python3 "$ROOT/test/gen-manifest.py" --mods "$SRC" --out "$WWW" --base-url "$BASE" \
    --generated-at "$(printf '2026-10-08T00:00:%02d.%03dZ' "$((regens % 60))" "$regens")" "$@" >/dev/null
}

run_agent() {
  local log="$1"
  shift
  java -jar "$AGENT_JAR" --game-dir "$SANDBOX" --manifest-url "$MANIFEST_URL" "$@" >"$log" 2>&1
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
say "T1 首次同步：服务器模组进镜像并部署到 mods/"
run_agent "$WS/t1.log"
assert_eq "0" "$?" "agent 退出码为 0"
assert_contains "$WS/t1.log" "下载 2" "下载了 2 个文件"
assert_eq "2" "$(count_jars "$SANDBOX/mods-server")" "mods-server 有 2 个 jar"
assert_eq "2" "$(count_jars "$SANDBOX/mods")" "mods/ 里有 2 个服务器模组"
assert_same_content "$SANDBOX/mods/create-1.0.jar" "$SANDBOX/mods-server/create-1.0.jar" "mods 是 mods-server 的拷贝"

MTIME_BEFORE="$(file_mtime "$SANDBOX/mods-server/create-1.0.jar")"

# ---------------------------------------------------------------- T2 幂等
say "T2 再次运行：符合的文件不重写（稳态）"
run_agent "$WS/t2.log"
assert_eq "0" "$?" "agent 退出码为 0"
assert_contains "$WS/t2.log" "已一致 2 个" "识别出 2 个文件已一致"
assert_contains "$WS/t2.log" "完成: 一致 2，下载 0，移除 0，归档 0，补回 0" "稳态下不做任何改动"
assert_eq "$MTIME_BEFORE" "$(file_mtime "$SANDBOX/mods-server/create-1.0.jar")" "已一致文件未被重写（mtime 不变）"

# ---------------------------------------------------------------- T3 client 模组共存
say "T3 玩家自装模组：直接放在 mods/ 里，永不被触碰"
make_mod "$SANDBOX/mods/extra-1.0.jar" extra "payload-extra"
EXTRA_MTIME="$(file_mtime "$SANDBOX/mods/extra-1.0.jar")"
run_agent "$WS/t3.log"
assert_eq "3" "$(count_jars "$SANDBOX/mods")" "mods/ 变成 3 个 jar（2 服务器 + 1 玩家）"
assert_eq "$EXTRA_MTIME" "$(file_mtime "$SANDBOX/mods/extra-1.0.jar")" "玩家模组文件未被触碰"
if has_file "$SANDBOX/modsync/trash" 'create-1.0.jar*'; then
  fail "内容未变的服务器模组不应进入 trash"
else
  pass "内容未变的模组原地保留，不产生 trash 垃圾"
fi

# ---------------------------------------------------------------- T4 同 mod id 冲突
say "T4 同 mod id 冲突：服务器版本优先，玩家那份进 archive"
make_mod "$SANDBOX/mods/create-mine-1.0.jar" create "payload-client-create"
run_agent "$WS/t4.log"
assert_file_absent "$SANDBOX/mods/create-mine-1.0.jar" "冲突的玩家版本被移出 mods/"
assert_file_exists "$SANDBOX/modsync/archive/create-mine-1.0.jar" "玩家版本进了 modsync/archive（可恢复）"
assert_contains "$WS/t4.log" "归档玩家模组" "日志说明是归档而不是删除"
assert_eq "3" "$(count_jars "$SANDBOX/mods")" "mods/ 仍是 3 个 jar"
assert_same_content "$SANDBOX/mods/create-1.0.jar" "$SANDBOX/mods-server/create-1.0.jar" "mods 中保留的是服务器版本"

# ---------------------------------------------------------------- T5 误放进 mods 的模组
say "T5 玩家把模组直接丢进 mods/：视为玩家模组，原样保留、不迁移"
make_mod "$SANDBOX/mods/manual-1.0.jar" manual "payload-manual"
MANUAL_MTIME="$(file_mtime "$SANDBOX/mods/manual-1.0.jar")"
run_agent "$WS/t5.log"
assert_file_exists "$SANDBOX/mods/manual-1.0.jar" "文件仍在 mods/"
assert_eq "$MANUAL_MTIME" "$(file_mtime "$SANDBOX/mods/manual-1.0.jar")" "文件未被触碰"
assert_file_absent "$SANDBOX/mods-client" "不再有 mods-client 目录"
assert_eq "4" "$(count_jars "$SANDBOX/mods")" "mods/ 为 4 个 jar"

# ---------------------------------------------------------------- T6 服务器更新
say "T6 服务器模组更新：只下载变化的那一个"
make_mod "$SRC/create-1.0.jar" create "payload-create-v2"
regen
run_agent "$WS/t6.log"
assert_contains "$WS/t6.log" "下载 1" "只下载 1 个变化文件"
assert_same_content "$SANDBOX/mods/create-1.0.jar" "$SANDBOX/mods-server/create-1.0.jar" "mods 同步到新版本"

# ---------------------------------------------------------------- T7 服务器删除
say "T7 服务器删除模组：镜像与 mods/ 同步移除，旧版进 trash"
rm -f "$SRC/sodium-1.0.jar"
regen
run_agent "$WS/t7.log"
assert_contains "$WS/t7.log" "服务器已删除" "日志说明是服务器删除"
assert_file_absent "$SANDBOX/mods/sodium-1.0.jar" "mods/ 中已移除"
assert_file_absent "$SANDBOX/mods-server/sodium-1.0.jar" "mods-server 中已移除"
if has_file "$SANDBOX/modsync/trash" 'sodium-1.0.jar*'; then
  pass "被移除的文件进入 modsync/trash"
else
  fail "trash 中找不到被移除的 sodium-1.0.jar"
fi
assert_eq "3" "$(count_jars "$SANDBOX/mods")" "删除后 mods/ 为 3 个 jar"

# ---------------------------------------------------------------- T8 dry-run
say "T8 --dry-run 只报告不改动"
make_mod "$SRC/pending-1.0.jar" pending "payload-pending"
regen
run_agent "$WS/t8.log" --dry-run
assert_contains "$WS/t8.log" "[dry-run]" "输出 dry-run 计划"
assert_file_absent "$SANDBOX/mods/pending-1.0.jar" "dry-run 不写 mods/"
assert_file_absent "$SANDBOX/mods-server/pending-1.0.jar" "dry-run 不写镜像"
assert_eq "3" "$(count_jars "$SANDBOX/mods")" "mods 未被改动"
rm -f "$SRC/pending-1.0.jar"

# ---------------------------------------------------------------- T9 url 直链
say "T9 清单提供 url 直链时走直链下载"
make_mod "$SRC/create-1.0.jar" create "payload-create-v3"
regen --direct-url-for create-1.0.jar
run_agent "$WS/t9.log"
assert_contains "$WS/t9.log" "下载 1" "通过直链下载了更新"
assert_same_content "$SANDBOX/mods/create-1.0.jar" "$SANDBOX/mods-server/create-1.0.jar" "直链下载后内容一致"

# ---------------------------------------------------------------- T9b 篡改检测
say "T9b 两份都被改动（大小不变）：按 sha256 检出并重新下载"
python3 - "$SANDBOX/mods-server/create-1.0.jar" "$SANDBOX/mods/create-1.0.jar" <<'PY'
import sys
for p in sys.argv[1:]:
    data = bytearray(open(p, "rb").read())
    data[-1] ^= 0xFF  # 大小不变，内容变了
    open(p, "wb").write(bytes(data))
PY
run_agent "$WS/t9b.log"
assert_contains "$WS/t9b.log" "下载 1" "检出哈希不匹配并重新下载"
assert_same_content "$SANDBOX/mods-server/create-1.0.jar" "$SANDBOX/mods/create-1.0.jar" "修复后 mods 与 mods-server 一致"

say "T9c 只有镜像被改坏（mods/ 那份是对的）：用本地文件修镜像，不联网"
python3 - "$SANDBOX/mods-server/create-1.0.jar" <<'PY'
import sys
p = sys.argv[1]
data = bytearray(open(p, "rb").read())
data[-1] ^= 0xFF
open(p, "wb").write(bytes(data))
PY
run_agent "$WS/t9c.log"
assert_contains "$WS/t9c.log" "沿用" "用 mods/ 里内容一致的文件补镜像"
assert_contains "$WS/t9c.log" "下载 0" "没有重复下载"
assert_same_content "$SANDBOX/mods-server/create-1.0.jar" "$SANDBOX/mods/create-1.0.jar" "镜像被修复"

# ---------------------------------------------------------------- T10 -javaagent
say "T10 -javaagent 模式（PCL 等直接改 JVM 参数）"
# 启动器按空格切分 JVM 参数，所以 agent 自己的参数必须全部写在 "=" 之后、用逗号分隔。
# 写成独立的 --xxx 会被 JVM 当成它自己的参数而拒绝启动（Unrecognized option）——
# 这正是删掉 的原因。
java "-javaagent:$AGENT_JAR=--game-dir=$SANDBOX,--manifest-url=$MANIFEST_URL" -version >"$WS/t10.log" 2>&1
assert_eq "0" "$?" "java -javaagent 启动成功（逗号分隔多参数）"
assert_contains "$WS/t10.log" "[modsync]" "premain 中执行了同步"
assert_contains "$WS/t10.log" "$SANDBOX" "premain 解析出了 --game-dir"
assert_contains "$WS/t10.log" "manifest.json" "premain 解析出了 --manifest-url"
assert_contains "$WS/t10.log" "openjdk version" "premain 之后 JVM 正常继续（-version）"

say "T11 启动器参数陷阱：agent 参数不能作为独立 JVM 参数"
if "$JAVA" -javaagent:"$AGENT_JAR=--game-dir=$SANDBOX" "--manifest-url=$MANIFEST_URL" -version >"$WS/t11.log" 2>&1; then
  fail "JVM 竟然接受了独立的 --manifest-url"
else
  pass "独立的 --xxx 会被 JVM 拒绝（所以必须写在 = 之后）"
fi
if "$JAVA" -jar "$AGENT_JAR" --help 2>&1 | grep -q 'no-gui'; then
  fail "--help 中仍存在 no-gui"
else
  pass "--help 中已无 no-gui"
fi

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAILED 项"
echo "日志目录: $WS"
echo "======================================"
[ "$FAILED" -eq 0 ]
