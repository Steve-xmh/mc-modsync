#!/usr/bin/env bash
# Builds the two ModSync jars with nothing but a JDK 21 installation.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build"
CLASSES="$OUT/classes"
MF="$OUT/mf"

rm -rf "$CLASSES"
mkdir -p "$CLASSES" "$MF"

# The neoforge/ package needs NeoForge on the classpath and is built separately by build-mod.sh.
find "$ROOT/src" -name '*.java' -not -path '*/io/modsync/neoforge/*' -print > "$OUT/sources.txt"
echo "[build] compiling $(wc -l < "$OUT/sources.txt" | tr -d ' ') source files"
javac -encoding UTF-8 -Xlint:all -d "$CLASSES" @"$OUT/sources.txt"

cat > "$MF/agent.mf" <<'EOF'
Manifest-Version: 1.0
Main-Class: io.modsync.agent.Agent
Premain-Class: io.modsync.agent.Agent
Agent-Class: io.modsync.agent.Agent
Implementation-Title: ModSync Agent
Implementation-Version: 1.0.0
EOF
jar --create --file "$OUT/modsync-agent.jar" --manifest "$MF/agent.mf" -C "$CLASSES" .
echo "[build] $OUT/modsync-agent.jar"

if [[ -f "$CLASSES/io/modsync/server/ServerMain.class" ]]; then
  cat > "$MF/server.mf" <<'EOF'
Manifest-Version: 1.0
Main-Class: io.modsync.server.ServerMain
Implementation-Title: ModSync Server
Implementation-Version: 1.0.0
EOF
  jar --create --file "$OUT/modsync-server.jar" --manifest "$MF/server.mf" -C "$CLASSES" .
  echo "[build] $OUT/modsync-server.jar"
else
  echo "[build] skipped modsync-server.jar (io/modsync/server/ServerMain.class 未找到)"
fi
