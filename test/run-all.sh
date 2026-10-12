#!/usr/bin/env bash
# Runs the whole ModSync test suite:
#   build + agent + version-mismatch + server-only filtering + reconcile + full-stack.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "############ 1/6 build ############"
./build.sh || exit 1

if [[ -x ./build-mod.sh ]]; then
  # 区分"没有 NeoForge 依赖所以跳过"与"模组构建真的失败"——后者必须让整个套件红。
  if ./build-mod.sh >"$ROOT/build/build-mod.log" 2>&1; then
    echo "[build] modsync-neoforge jar ok"
  elif [[ -n "${MODSYNC_NEO_LIBS:-}" ]] || ! grep -q '找不到 NeoForge 依赖目录' "$ROOT/build/build-mod.log"; then
    echo "[build] ✗ NeoForge 模组构建失败：" >&2
    tail -20 "$ROOT/build/build-mod.log" >&2
    exit 1
  else
    echo "[build] skipped NeoForge mod (no local NeoForge libraries found)"
  fi
fi

echo
echo "############ 2/6 agent scenario suite ############"
bash test/e2e-agent.sh
AGENT_RC=$?

echo
echo "############ 3/6 version mismatch suite ############"
bash test/e2e-version.sh
VERSION_RC=$?

echo
echo "############ 4/6 server-only filtering suite ############"
bash test/e2e-filter.sh
FILTER_RC=$?

echo
echo "############ 5/6 reconcile suite (v1.2.0 同步模型) ############"
bash test/e2e-reconcile.sh
RECONCILE_RC=$?

echo
echo "############ 6/6 full-stack suite (server + agent) ############"
bash test/e2e-server.sh
SERVER_RC=$?

echo
echo "############ summary ############"
printf '%-12s %s\n' "agent suite:"      "$([ "$AGENT_RC" -eq 0 ] && echo PASS || echo FAIL)"
printf '%-12s %s\n' "version suite:"    "$([ "$VERSION_RC" -eq 0 ] && echo PASS || echo FAIL)"
printf '%-12s %s\n' "filter suite:"     "$([ "$FILTER_RC" -eq 0 ] && echo PASS || echo FAIL)"
printf '%-12s %s\n' "reconcile suite:"  "$([ "$RECONCILE_RC" -eq 0 ] && echo PASS || echo FAIL)"
printf '%-12s %s\n' "full-stack suite:" "$([ "$SERVER_RC" -eq 0 ] && echo PASS || echo FAIL)"
[ "$AGENT_RC" -eq 0 ] && [ "$VERSION_RC" -eq 0 ] && [ "$FILTER_RC" -eq 0 ] \
  && [ "$RECONCILE_RC" -eq 0 ] && [ "$SERVER_RC" -eq 0 ]
