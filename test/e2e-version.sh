#!/usr/bin/env bash
# Version-mismatch scenarios.
#
# The server announces its Minecraft/loader versions in the manifest; the agent compares them with
# the local instance (config override, launcher metadata or Prism's mmc-pack.json) and either warns,
# skips the sync, or aborts the launch - depending on configuration.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_JAR="$ROOT/build/modsync-agent.jar"
PORT="${MODSYNC_VERSION_TEST_PORT:-25876}"
BASE="http://127.0.0.1:$PORT"
MANIFEST_URL="$BASE/manifest.json"

PASS=0
FAILED=0
pass() { PASS=$((PASS + 1)); echo "  ok   - $*"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL - $*"; }
assert_eq() { if [ "$1" = "$2" ]; then pass "$3 (= $1)"; else fail "$3: 期望 [$1] 实际 [$2]"; fi; }
assert_contains() { if grep -qF -- "$2" "$1"; then pass "$3"; else fail "$3: '$1' 中未找到 '$2'"; fi; }
assert_not_contains() { if grep -qF -- "$2" "$1"; then fail "$3: 不应出现 '$2'"; else pass "$3"; fi; }
count_jars() { ls -1 "$1"/*.jar 2>/dev/null | wc -l | tr -d ' '; }
say() { echo; echo "== $*"; }

[ -f "$AGENT_JAR" ] || { echo "缺少 $AGENT_JAR，请先 ./build.sh" >&2; exit 1; }

WS="$(mktemp -d /tmp/modsync-ver.XXXXXX)"
SRC="$WS/mods"
WWW="$WS/www"
mkdir -p "$SRC" "$WWW"
HTTP_PID=""
cleanup() { [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null; wait "$HTTP_PID" 2>/dev/null; }
trap cleanup EXIT

bash "$ROOT/test/make-mod.sh" "$SRC/alpha-1.0.jar" alpha "alpha" >/dev/null
bash "$ROOT/test/make-mod.sh" "$SRC/beta-1.0.jar" beta "beta" >/dev/null

# 服务器：Minecraft 1.21.1 + NeoForge 21.1.248
python3 "$ROOT/test/gen-manifest.py" --mods "$SRC" --out "$WWW" --base-url "$BASE" \
  --minecraft-version 1.21.1 --loader-version 21.1.248 >/dev/null

python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WWW" >"$WS/http.log" 2>&1 &
HTTP_PID=$!
for _ in $(seq 1 50); do curl -sf "$MANIFEST_URL" -o /dev/null && break; sleep 0.2; done
curl -sf "$MANIFEST_URL" -o /dev/null || { echo "静态服务器启动失败" >&2; exit 1; }

run_agent() { # <log> <gameDir> [extra args...]
  local log="$1" game="$2"; shift 2
  java -jar "$AGENT_JAR" --game-dir "$game" --manifest-url "$MANIFEST_URL" --no-gui "$@" >"$log" 2>&1
  return $?
}

echo "工作目录: $WS"

say "T1 版本一致：不提示，正常同步"
G1="$WS/g1"; mkdir -p "$G1"
run_agent "$WS/t1.log" "$G1" --client-mc-version 1.21.1 --client-loader-version 21.1.248
assert_eq "0" "$?" "退出码 0"
assert_not_contains "$WS/t1.log" "不一致" "没有误报不一致"
assert_eq "2" "$(count_jars "$G1/mods")" "正常同步 2 个模组"

say "T2 从 mmc-pack.json 自动探测本地版本（Prism/MultiMC 路径）"
G2="$WS/instance2/minecraft"; mkdir -p "$G2"
cat > "$WS/instance2/mmc-pack.json" <<'JSON'
{
  "components": [
    { "uid": "net.minecraft", "version": "1.21.1" },
    { "uid": "net.neoforged", "version": "21.1.100" }
  ],
  "formatVersion": 1
}
JSON
run_agent "$WS/t2.log" "$G2"
assert_eq "0" "$?" "退出码 0"
assert_contains "$WS/t2.log" "模组加载器版本不一致" "检出加载器版本差异"
assert_contains "$WS/t2.log" "21.1.100" "提示里带上本地版本"
assert_contains "$WS/t2.log" "Prism" "提示里给出 Prism 的升级路径"
assert_eq "2" "$(count_jars "$G2/mods")" "默认策略下仍然同步（仅提醒）"

say "T3 --abort-on-mismatch：版本不一致时中止启动"
G3="$WS/g3"; mkdir -p "$G3"
run_agent "$WS/t3.log" "$G3" --client-mc-version 1.21.1 --client-loader-version 21.1.100 --abort-on-mismatch
RC=$?
assert_eq "3" "$RC" "退出码 3（非 0，Prism 会中止启动）"
assert_contains "$WS/t3.log" "已中止游戏启动" "日志说明中止原因"
assert_eq "0" "$(count_jars "$G3/mods" 2>/dev/null)" "中止后未同步任何模组"

say "T4 Minecraft 版本不一致：默认跳过同步（避免跨版本崩溃）"
G4="$WS/g4"; mkdir -p "$G4"
run_agent "$WS/t4.log" "$G4" --client-mc-version 1.20.1 --client-loader-version 21.1.248
assert_eq "0" "$?" "退出码 0（不阻断启动）"
assert_contains "$WS/t4.log" "Minecraft 版本不一致" "检出 Minecraft 版本差异"
assert_contains "$WS/t4.log" "已跳过" "说明跳过同步"
assert_eq "0" "$(count_jars "$G4/mods-server" 2>/dev/null)" "未下载任何模组"

say "T5 --no-version-check：关闭比对"
G5="$WS/g5"; mkdir -p "$G5"
run_agent "$WS/t5.log" "$G5" --client-loader-version 19.0.0 --no-version-check
assert_not_contains "$WS/t5.log" "不一致" "不再提示版本差异"
assert_eq "2" "$(count_jars "$G5/mods")" "照常同步"

say "T6 服务器未声明版本时不误报"
python3 "$ROOT/test/gen-manifest.py" --mods "$SRC" --out "$WWW" --base-url "$BASE" >/dev/null
G6="$WS/g6"; mkdir -p "$G6"
run_agent "$WS/t6.log" "$G6" --client-loader-version 19.0.0
assert_eq "0" "$?" "退出码 0"
assert_not_contains "$WS/t6.log" "不一致" "服务器未声明版本 → 不提示"

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAILED 项"
echo "日志目录: $WS"
echo "======================================"
[ "$FAILED" -eq 0 ]
