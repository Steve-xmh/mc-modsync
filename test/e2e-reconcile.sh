#!/usr/bin/env bash
# 对账式同步（v1.2.0 新模型）的端到端测试。
#
# 覆盖 docs/design-reconcile.md 的测试清单：
#   T1  首次同步（无账本）→ 只增不删，玩家自装模组原封不动
#   T2  幂等 → 已符合 N，需下载 0，mtime 不变
#   T3  服务器新增 → 只下载新增的那一个
#   T4  玩家删掉客户端模组 → 不再复活（本次改造的核心验收）
#   T5  玩家删掉服务器模组 → 被补回
#   T6  玩家模组 modId 撞服务器 → 进 modsync/archive/，mods/ 只剩服务器版本
#   T7  服务器删除模组 → 账本与 mods/ 同时移除，旧版进 trash，写 removal-log
#   T8  服务器改名（同 modId 换文件名）→ 视为更新，不被护栏拦截
#   T9  清单时间戳更旧/相同 → 只增改不删；更新后 → 正常删除
#   T10 清单 files 为空 / JSON 损坏 → 本轮完全不动
#   T11 服务器模组被改坏 → 复跑修复
#   T12 结构：last-manifest / manifest-history / removal-log 存在，且不再有 mods-client
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${JAVA:-java}"
AGENT_JAR="$ROOT/build/modsync-agent.jar"
PORT="${MODSYNC_RECONCILE_PORT:-25881}"
BASE="http://127.0.0.1:$PORT"
MANIFEST_URL="$BASE/manifest.json"

PASS=0
FAILED=0
pass() { PASS=$((PASS + 1)); echo "  ok   - $*"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL - $*"; }
say() { echo; echo "== $*"; }
assert_eq() { if [ "$1" = "$2" ]; then pass "$3 (= $1)"; else fail "$3: 期望 [$1] 实际 [$2]"; fi; }
assert_file_exists() { if [ -f "$1" ]; then pass "$2"; else fail "$2: 缺少 $1"; fi; }
assert_file_absent() { if [ ! -e "$1" ]; then pass "$2"; else fail "$2: 仍然存在 $1"; fi; }
assert_log() { if grep -qF -- "$2" "$1"; then pass "$3"; else fail "$3: 日志中未找到 '$2'"; fi; }
assert_no_log() { if grep -qF -- "$2" "$1"; then fail "$3: 日志中不该出现 '$2'"; else pass "$3"; fi; }
assert_same_content() {
  if [ "$(shasum -a 256 "$1" | cut -d' ' -f1)" = "$(shasum -a 256 "$2" | cut -d' ' -f1)" ]; then
    pass "$3"; else fail "$3: 内容不一致"; fi
}
count_jars() { ls -1 "$1"/*.jar 2>/dev/null | wc -l | tr -d ' '; }

if [ "$(uname -s)" = "Darwin" ]; then
  file_mtime() { stat -f %m "$1"; }
else
  file_mtime() { stat -c %Y "$1"; }
fi
has_file() { [ -n "$(find "$1" -name "$2" -print 2>/dev/null | head -1)" ]; }

if [ ! -f "$AGENT_JAR" ]; then
  echo "找不到 $AGENT_JAR，请先运行 ./build.sh" >&2
  exit 1
fi

WS="$(mktemp -d /tmp/modsync-reconcile.XXXXXX)"
SANDBOX="$WS/game"
SRC="$WS/servermods"
WWW="$WS/www"
mkdir -p "$SANDBOX/mods" "$SRC" "$WWW"

HTTP_PID=""
cleanup() { [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null; wait "$HTTP_PID" 2>/dev/null; }
trap cleanup EXIT

make_mod() { bash "$ROOT/test/make-mod.sh" "$@" >/dev/null; }
# 时间戳护栏要能被测试驱动：每次发布都用一个严格递增的时间戳
regens=0
regen() {
  regens=$((regens + 1))
  local ts
  ts="$(printf '2026-10-08T00:00:%02d.%03dZ' "$((regens % 60))" "$regens")"
  python3 "$ROOT/test/gen-manifest.py" --mods "$SRC" --out "$WWW" --base-url "$BASE" \
    --generated-at "$ts" "$@" >/dev/null
}
regen_old() { # 用比上次更旧（或相同）的时间戳重新生成
  python3 "$ROOT/test/gen-manifest.py" --mods "$SRC" --out "$WWW" --base-url "$BASE" \
    --generated-at "2026-10-08T00:00:00.000Z" "$@" >/dev/null
}

run_agent() {
  local log="$1"; shift
  ( cd "$SANDBOX" && "$JAVA" -jar "$AGENT_JAR" --game-dir "$SANDBOX" \
      --manifest-url "$MANIFEST_URL" "$@" ) >"$log" 2>&1
  return $?
}

echo "工作目录: $WS"

say "准备：服务器发布 create / jei，玩家自己已装 sodium-player-1.0.jar"
make_mod "$SRC/create-1.0.jar" create "payload-create-v1"
make_mod "$SRC/jei-1.0.jar" jei "payload-jei-v1"
make_mod "$SANDBOX/mods/sodium-player-1.0.jar" sodium "player-sodium-v1"
regen

say "启动静态清单服务器 http://127.0.0.1:$PORT"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WWW" >"$WS/http.log" 2>&1 &
HTTP_PID=$!
for _ in $(seq 1 50); do curl -sf "$MANIFEST_URL" -o /dev/null && break; sleep 0.2; done
if ! curl -sf "$MANIFEST_URL" -o /dev/null; then echo "静态服务器启动失败" >&2; cat "$WS/http.log" >&2; exit 1; fi

# ---------------------------------------------------------------- T1 首次同步
say "T1 首次同步（无账本）：只增不删，玩家模组原封不动"
SODIUM_MTIME="$(file_mtime "$SANDBOX/mods/sodium-player-1.0.jar")"
run_agent "$WS/t1.log"
assert_file_exists "$SANDBOX/mods/create-1.0.jar" "服务器模组已部署 create"
assert_file_exists "$SANDBOX/mods/jei-1.0.jar" "服务器模组已部署 jei"
assert_file_exists "$SANDBOX/mods/sodium-player-1.0.jar" "玩家自装模组仍在"
assert_eq "$SODIUM_MTIME" "$(file_mtime "$SANDBOX/mods/sodium-player-1.0.jar")" "玩家模组文件未被触碰"
assert_eq "2" "$(count_jars "$SANDBOX/mods-server")" "账本里只有服务器模组"
assert_file_exists "$SANDBOX/modsync/last-manifest.json" "已保存本地清单副本"

# ---------------------------------------------------------------- T2 幂等
say "T2 幂等：已符合、不重写"
CREATE_MTIME="$(file_mtime "$SANDBOX/mods/create-1.0.jar")"
run_agent "$WS/t2.log"
assert_eq "$CREATE_MTIME" "$(file_mtime "$SANDBOX/mods/create-1.0.jar")" "已符合文件未被重写（mtime 不变）"
assert_log "$WS/t2.log" "已符合 2" "预检行报告 2 个文件已符合"

# ---------------------------------------------------------------- T3 服务器新增
say "T3 服务器新增模组：只下载新增的那一个"
make_mod "$SRC/apple-1.0.jar" apple "payload-apple-v1"
regen
run_agent "$WS/t3.log"
assert_file_exists "$SANDBOX/mods/apple-1.0.jar" "新增模组已下载"
assert_eq "$CREATE_MTIME" "$(file_mtime "$SANDBOX/mods/create-1.0.jar")" "既有模组没有被重新下载"
assert_log "$WS/t3.log" "需下载 1" "只下载了 1 个"

# ------------------------------------------- T4 玩家删客户端模组不再复活（核心）
say "T4 玩家删掉客户端模组：不再复活"
rm -f "$SANDBOX/mods/sodium-player-1.0.jar"
run_agent "$WS/t4.log"
assert_file_absent "$SANDBOX/mods/sodium-player-1.0.jar" "mods/ 里没有复活"
assert_file_absent "$SANDBOX/mods-client" "不再有 mods-client 目录"
assert_no_log "$WS/t4.log" "迁移玩家自装模组" "不再有「迁移到 mods-client」的行为"

# ------------------------------------------------- T5 玩家删服务器模组 → 补回
say "T5 玩家删掉服务器模组：下次启动被补回"
rm -f "$SANDBOX/mods/create-1.0.jar"
run_agent "$WS/t5.log"
assert_file_exists "$SANDBOX/mods/create-1.0.jar" "服务器模组被补回"
assert_same_content "$SANDBOX/mods-server/create-1.0.jar" "$SANDBOX/mods/create-1.0.jar" "补回内容与账本一致"
assert_log "$WS/t5.log" "补回" "日志说明是补回"

# ------------------------------------------------ T6 玩家 modId 与服务器冲突
say "T6 玩家模组 modId 撞服务器：进 archive，mods/ 只剩服务器版本"
make_mod "$SRC/sodium-1.0.jar" sodium "server-sodium-v1"
make_mod "$SANDBOX/mods/sodium-custom-1.0.jar" sodium "player-sodium-v2"
regen
run_agent "$WS/t6.log"
assert_file_exists "$SANDBOX/mods/sodium-1.0.jar" "服务器版本在 mods/ 里"
assert_file_absent "$SANDBOX/mods/sodium-custom-1.0.jar" "玩家冲突版本被移出 mods/"
assert_file_exists "$SANDBOX/modsync/archive/sodium-custom-1.0.jar" "玩家版本进了 archive（可恢复）"
assert_log "$WS/t6.log" "归档玩家模组" "日志说明了归档原因"

# ------------------------------------------------------- T7 服务器删除模组
say "T7 服务器删掉模组：账本与 mods/ 同步移除，旧版进 trash"
rm -f "$SRC/apple-1.0.jar"
regen
run_agent "$WS/t7.log"
assert_file_absent "$SANDBOX/mods/apple-1.0.jar" "mods/ 里已移除"
assert_file_absent "$SANDBOX/mods-server/apple-1.0.jar" "账本里已移除"
assert_log "$WS/t7.log" "服务器已删除" "日志说明是服务器删除"
assert_file_exists "$SANDBOX/modsync/removal-log.jsonl" "写入了审计日志"

# ------------------------------------------------------- T8 服务器改名/更新
say "T8 服务器改名（同 modId）：视为更新，不被护栏拦截"
mv "$SRC/create-1.0.jar" "$SRC/create-2.0.jar"
regen
run_agent "$WS/t8.log"
assert_file_exists "$SANDBOX/mods/create-2.0.jar" "新文件就位"
assert_file_absent "$SANDBOX/mods/create-1.0.jar" "旧文件已清除"
assert_log "$WS/t8.log" "服务器更新" "日志标为更新"
assert_no_log "$WS/t8.log" "本轮只增改" "没有被删除护栏误拦"

# ---------------------------------------------------- T9 时间戳顺序护栏
say "T9 清单时间戳更旧：只增改不删"
rm -f "$SRC/jei-1.0.jar"
regen_old
run_agent "$WS/t9.log"
assert_file_exists "$SANDBOX/mods/jei-1.0.jar" "时间戳不倒序 → 不执行删除"
assert_log "$WS/t9.log" "本轮只增改，不删除" "日志说明了护栏生效"
say "T9b 时间戳前进：恢复删除"
regen
run_agent "$WS/t9b.log"
assert_file_absent "$SANDBOX/mods/jei-1.0.jar" "时间戳前进后正常删除"
assert_file_absent "$SANDBOX/mods-server/jei-1.0.jar" "账本同步移除"

# ---------------------------------------------------- T10 空/坏清单
say "T10 清单 files 为空 / JSON 损坏：本轮完全不动"
BEFORE="$(ls -1 "$SANDBOX/mods" | sort | tr '\n' ' ')"
printf '{"schema":1,"serverId":"testserver00000001","baseUrl":"%s","generatedAt":"2026-10-09T00:00:00.000Z","files":[]}\n' "$BASE" > "$WWW/manifest.json"
run_agent "$WS/t10a.log"
assert_log "$WS/t10a.log" "清单为空或无法解析" "空清单按获取失败处理"
assert_eq "$BEFORE" "$(ls -1 "$SANDBOX/mods" | sort | tr '\n' ' ')" "空清单时 mods/ 完全没动"
printf '{"schema":1,\n' > "$WWW/manifest.json"
run_agent "$WS/t10b.log"
assert_log "$WS/t10b.log" "清单为空或无法解析" "损坏 JSON 同样按获取失败处理"
assert_eq "$BEFORE" "$(ls -1 "$SANDBOX/mods" | sort | tr '\n' ' ')" "损坏 JSON 时 mods/ 完全没动"

# ---------------------------------------------------- T11 被改坏 → 复跑修复
say "T11 服务器模组被改坏：复跑修复"
regen
run_agent "$WS/t11a.log"
printf 'corrupted!!!' > "$SANDBOX/mods/create-2.0.jar"
run_agent "$WS/t11b.log"
assert_same_content "$SANDBOX/mods-server/create-2.0.jar" "$SANDBOX/mods/create-2.0.jar" "被改坏的文件已修复"

# ---------------------------------------------------- T12 结构检查
say "T12 运行期文件结构"
assert_file_exists "$SANDBOX/modsync/state.json" "state.json 存在"
assert_file_exists "$SANDBOX/modsync/last-manifest.json" "last-manifest.json 存在"
HIST="$(ls -1 "$SANDBOX/modsync/manifest-history" 2>/dev/null | wc -l | tr -d ' ')"
if [ "$HIST" -le 5 ]; then pass "manifest-history 最多 5 份（当前 $HIST）"; else fail "manifest-history 超过 5 份（$HIST）"; fi
if has_file "$SANDBOX/modsync/trash" 'apple-1.0.jar*'; then pass "被删的服务器模组在 trash 里有备份"; else fail "trash 里没有 apple-1.0.jar"; fi
assert_file_absent "$SANDBOX/mods-client" "全程没有 mods-client"

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAILED 项"
echo "工作目录: $WS"
echo "======================================"
[ "$FAILED" -eq 0 ]
