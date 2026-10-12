#!/usr/bin/env bash
# Builds the NeoForge server mod (build/modsync-neoforge-<ver>.jar) with plain javac.
#
# Compiling against the NeoForge jars that Prism / a dedicated server already ship keeps this
# dependency-free and offline-capable (no Gradle, no NeoGradle download).
#
# Usage:
#   ./build-mod.sh [--neo-libs <dir>]
# If --neo-libs is omitted the script auto-detects, in order:
#   1. $MODSYNC_NEO_LIBS
#   2. ./server/libraries or ./libraries    (e.g. a NeoForge server next to this repo)
#   3. Prism Launcher's shared libraries (macOS / Linux / Windows)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build"
VERSION="${MODSYNC_MOD_VERSION:-1.2.2}"

NEOLIBS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --neo-libs) NEOLIBS="${2:?missing value}"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$NEOLIBS" ]]; then
  for candidate in "${MODSYNC_NEO_LIBS:-}" \
                   "./server/libraries" \
                   "./libraries" \
                   "$HOME/Library/Application Support/PrismLauncher/libraries" \
                   "$HOME/.local/share/PrismLauncher/libraries" \
                   "${APPDATA:-}/PrismLauncher/libraries"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then NEOLIBS="$candidate"; break; fi
  done
fi
if [[ -z "$NEOLIBS" || ! -d "$NEOLIBS" ]]; then
  echo "找不到 NeoForge 依赖目录，请用 --neo-libs <dir> 指定（里面应有 net/neoforged/...）" >&2
  exit 1
fi

find_jar() { find "$NEOLIBS" -name "$1" -print -quit 2>/dev/null; }
NEOFORGE_JAR="$(find_jar 'neoforge-*-universal.jar')"
FML_JAR="$(find_jar 'loader-*.jar')"
BUS_JAR="$(find_jar 'bus-*.jar')"
for j in "$NEOFORGE_JAR" "$FML_JAR" "$BUS_JAR"; do
  if [[ -z "$j" ]]; then echo "缺少依赖 jar（neoforge universal / fml loader / event bus）" >&2; exit 1; fi
done
CP="$NEOFORGE_JAR:$FML_JAR:$BUS_JAR"

echo "[mod] 依赖来源: $NEOLIBS"
echo "[mod]   $(basename "$NEOFORGE_JAR")"
echo "[mod]   $(basename "$FML_JAR")"
echo "[mod]   $(basename "$BUS_JAR")"

CLASSES="$OUT/mod-classes"
STAGE="$OUT/mod-stage"
rm -rf "$CLASSES" "$STAGE"
mkdir -p "$CLASSES" "$STAGE/META-INF"

SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$ROOT/src/io/modsync/common" "$ROOT/src/io/modsync/server" "$ROOT/src/io/modsync/neoforge" -name '*.java')
echo "[mod] 编译 ${#SOURCES[@]} 个源文件"
# 保持严格 lint。强制英文消息，便于稳定匹配；唯一的例外是 javac 的强制警告
# "unknown enum constant Dist"：NeoForge 的 @Mod(dist = {...}) 需要 classpath 上有
# net.neoforged.api.distmarker.Dist 才能打印注解默认值，而本地依赖里未必带那个 api jar。
# 出现任何其它警告都视为构建失败。
javac -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -Xlint:all \
  -cp "$CP" -d "$CLASSES" "${SOURCES[@]}" 2>"$OUT/mod-javac.log"
BENIGN='unknown enum constant|未知的枚举常量|distmarker[.]Dist|[[:space:]]*\^[[:space:]]*$|^[[:space:]]*$|^[0-9]+ (warnings?|个警告)$|^[0-9]+ (errors?|个错误)$'
if grep -qE 'unknown enum constant|未知的枚举常量' "$OUT/mod-javac.log"; then
  echo "[mod] 说明：本地依赖缺少 distmarker api jar，javac 报的 'unknown enum constant Dist' 可忽略"
fi
REAL_WARNINGS="$(grep -vE "$BENIGN" "$OUT/mod-javac.log" | grep -cE 'warning|警告' || true)"
if [[ "${REAL_WARNINGS:-0}" != "0" ]]; then
  echo "[mod] ✗ 除 Dist 之外还有编译警告：" >&2
  grep -vE "$BENIGN" "$OUT/mod-javac.log" >&2
  exit 1
fi

# 模组元数据：没有这个文件 FML 根本不会把 jar 当作模组载入。
cat > "$STAGE/META-INF/neoforge.mods.toml" <<EOF
modLoader="javafml"
loaderVersion="[4,)"
license="LGPL-3.0-or-later"

[[mods]]
modId="modsync"
version="$VERSION"
displayName="ModSync Server"
description='''
Publishes this server's mods as a JSON manifest over one HTTP port so that clients can sync
before the game launches (ModSync client agent). Server-side only; ships no client code.
'''
displayTest="IGNORE_ALL_VERSION"

[[dependencies.modsync]]
    modId="neoforge"
    type="required"
    versionRange="[21.0.0-beta,)"
    ordering="NONE"
    side="SERVER"
EOF

cp -R "$CLASSES/." "$STAGE/"
(cd "$STAGE" && jar --create --file "$OUT/modsync-neoforge-$VERSION.jar" .)

JAR="$OUT/modsync-neoforge-$VERSION.jar"

# 断言产物真的是一个模组 jar：少一个都不是"能装但没生效"，而是完全不会被载入。
#
# 注意不要把这里写成 `unzip -l "$JAR" | grep -q ...`：本脚本开了 pipefail，而 grep -q 命中后
# 立刻退出会让 unzip 收到 SIGPIPE（退出码 141），整条管道被判失败 —— jar 越大越容易踩到。
# 所以先把输出落到文件，再对文件做匹配。
unzip -l "$JAR" > "$OUT/mod-jar-listing.txt" 2>/dev/null || true
if ! grep -q 'META-INF/neoforge.mods.toml' "$OUT/mod-jar-listing.txt"; then
  echo "[mod] ✗ 产物缺少 META-INF/neoforge.mods.toml，NeoForge 不会载入它" >&2
  exit 1
fi
if ! grep -q 'io/modsync/neoforge/ModSyncMod.class' "$OUT/mod-jar-listing.txt"; then
  echo "[mod] ✗ 产物缺少 io/modsync/neoforge/ModSyncMod.class" >&2
  exit 1
fi
unzip -p "$JAR" 'META-INF/neoforge.mods.toml' > "$OUT/mod-mods-toml.txt" 2>/dev/null || true
if ! grep -q 'modId="modsync"' "$OUT/mod-mods-toml.txt"; then
  echo "[mod] ✗ mods.toml 里没有 modId=\"modsync\"" >&2
  exit 1
fi

echo "[mod] 产出: $JAR ($(wc -c < "$JAR" | tr -d ' ') bytes)"
unzip -l "$JAR" | grep -E 'neoforge.mods.toml|ModSyncMod.class|SyncServer.class' | sed 's/^/      /'
