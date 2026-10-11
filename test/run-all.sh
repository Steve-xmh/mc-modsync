#!/usr/bin/env bash
# Runs the whole ModSync test suite:
#   build + agent scenarios + version-mismatch scenarios + server-only filtering + full-stack.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "############ 1/5 build ############"
./build.sh || exit 1

if [[ -x ./build-mod.sh ]]; then
  ./build-mod.sh >/dev/null 2>&1 && echo "[build] modsync-neoforge jar ok" \
    || echo "[build] skipped NeoForge mod (no local NeoForge libraries found)"
fi

echo
echo "############ 2/5 agent scenario suite ############"
bash test/e2e-agent.sh
AGENT_RC=$?

echo
echo "############ 3/5 version mismatch suite ############"
bash test/e2e-version.sh
VERSION_RC=$?

echo
echo "############ 4/5 server-only filtering suite ############"
bash test/e2e-filter.sh
FILTER_RC=$?

echo
echo "############ 5/5 full-stack suite (server + agent) ############"
bash test/e2e-server.sh
SERVER_RC=$?

echo
echo "############ summary ############"
echo "agent suite:      $([ "$AGENT_RC" -eq 0 ] && echo PASS || echo FAIL)"
echo "version suite:    $([ "$VERSION_RC" -eq 0 ] && echo PASS || echo FAIL)"
echo "filter suite:     $([ "$FILTER_RC" -eq 0 ] && echo PASS || echo FAIL)"
echo "full-stack suite: $([ "$SERVER_RC" -eq 0 ] && echo PASS || echo FAIL)"
[ "$AGENT_RC" -eq 0 ] && [ "$VERSION_RC" -eq 0 ] && [ "$FILTER_RC" -eq 0 ] && [ "$SERVER_RC" -eq 0 ]
