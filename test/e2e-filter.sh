#!/usr/bin/env bash
# Server-only mod filtering.
#
# Builds synthetic mods that exercise every metadata shape (Fabric environment, NeoForge
# @Mod(dist = ...) incl. the explicit two-sided array, and no metadata at all) and checks that the
# publisher skips only the genuinely server-only ones.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_JAR="$ROOT/build/modsync-server.jar"
WORK="$(mktemp -d /tmp/modsync-filter.XXXXXX)"
MODS="$WORK/mods"
mkdir -p "$MODS"

PASS=0
FAILED=0
pass() { PASS=$((PASS + 1)); echo "  ok   - $*"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL - $*"; }
say() { echo; echo "== $*"; }

[ -f "$SERVER_JAR" ] || { echo "缺少 $SERVER_JAR，请先 ./build.sh" >&2; exit 1; }

say "生成测试模组（含 @Mod(dist=...) 的各种形态）"
SRC="$WORK/src"
mkdir -p "$SRC/net/neoforged/api/distmarker" "$SRC/net/neoforged/fml/common" "$SRC/demo"

cat > "$SRC/net/neoforged/api/distmarker/Dist.java" <<'JAVA'
package net.neoforged.api.distmarker;
public enum Dist { CLIENT, DEDICATED_SERVER }
JAVA

# 与真实 NeoForge 一致：Dist[]，默认 {CLIENT, DEDICATED_SERVER}，保留策略 RUNTIME
cat > "$SRC/net/neoforged/fml/common/Mod.java" <<'JAVA'
package net.neoforged.fml.common;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import net.neoforged.api.distmarker.Dist;
@Retention(RetentionPolicy.RUNTIME)
public @interface Mod {
    String value();
    Dist[] dist() default { Dist.CLIENT, Dist.DEDICATED_SERVER };
}
JAVA

cat > "$SRC/demo/ServerOnlyMod.java" <<'JAVA'
package demo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
@Mod(value = "neo_srvonly", dist = { Dist.DEDICATED_SERVER })
public class ServerOnlyMod { }
JAVA

cat > "$SRC/demo/ClientOnlyMod.java" <<'JAVA'
package demo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
@Mod(value = "neo_clientonly", dist = { Dist.CLIENT })
public class ClientOnlyMod { }
JAVA

# 关键反例：显式声明两端，绝不能被判成服务端专属
cat > "$SRC/demo/BothExplicitMod.java" <<'JAVA'
package demo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
@Mod(value = "neo_bothexplicit", dist = { Dist.CLIENT, Dist.DEDICATED_SERVER })
public class BothExplicitMod { }
JAVA

cat > "$SRC/demo/BothDefaultMod.java" <<'JAVA'
package demo;
import net.neoforged.fml.common.Mod;
@Mod("neo_bothdefault")
public class BothDefaultMod { }
JAVA

cat > "$SRC/demo/UsesDistInCode.java" <<'JAVA'
package demo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
@Mod("neo_usesdist")
public class UsesDistInCode {
    public static boolean onServer() {
        // 双端模组里很常见的写法：只在代码里引用 DEDICATED_SERVER（不能被误判）
        return Dist.DEDICATED_SERVER == Dist.DEDICATED_SERVER;
    }
}
JAVA

javac -d "$WORK/classes" $(find "$SRC" -name '*.java') || { echo "javac 失败" >&2; exit 1; }

make_neo_jar() { # <out.jar> <modId> <class-file-name>
  local out="$1" modid="$2" cls="$3"
  local stage; stage="$(mktemp -d)"
  mkdir -p "$stage/META-INF" "$stage/demo"
  cat > "$stage/META-INF/neoforge.mods.toml" <<EOF
modLoader="javafml"
loaderVersion="[4,)"
license="MIT"

[[mods]]
modId="$modid"
version="1.0.0"
displayName="$modid"
EOF
  cp "$WORK/classes/demo/$cls" "$stage/demo/"
  (cd "$stage" && jar --create --file "$out" .)
  rm -rf "$stage"
}

make_fabric_jar() { # <out.jar> <modId> <environment>
  local out="$1" modid="$2" env="$3"
  local stage; stage="$(mktemp -d)"
  cat > "$stage/fabric.mod.json" <<EOF
{ "schemaVersion": 1, "id": "$modid", "version": "1.0.0", "environment": "$env" }
EOF
  (cd "$stage" && jar --create --file "$out" .)
  rm -rf "$stage"
}

make_neo_jar "$MODS/neo-srvonly-1.0.jar"     neo_srvonly     ServerOnlyMod.class
make_neo_jar "$MODS/neo-clientonly-1.0.jar"  neo_clientonly  ClientOnlyMod.class
make_neo_jar "$MODS/neo-bothexplicit-1.0.jar" neo_bothexplicit BothExplicitMod.class
make_neo_jar "$MODS/neo-bothdefault-1.0.jar" neo_bothdefault BothDefaultMod.class
make_neo_jar "$MODS/neo-usesdist-1.0.jar"    neo_usesdist    UsesDistInCode.class
make_fabric_jar "$MODS/fab-srvonly-1.0.jar"  fab_srvonly     server
make_fabric_jar "$MODS/fab-both-1.0.jar"     fab_both        '*'
make_fabric_jar "$MODS/fab-client-1.0.jar"   fab_client      client
# 完全无元数据的普通 jar：无法判定 → 默认发布
printf 'not a real mod' > "$WORK/plain.txt"
(cd "$WORK" && jar --create --file "$MODS/plain-nometa-1.0.jar" plain.txt)

gen() { # <out.json> [extra args...]
  local out="$1"; shift
  java -jar "$SERVER_JAR" --mods "$MODS" --base-url http://x/modsync \
    --state "$WORK/state.json" --write-manifest "$out" --once "$@" >/dev/null 2>"$WORK/diag.txt"
}

published() { python3 -c "
import json,sys
m=json.load(open(sys.argv[1]))
print('\n'.join(sorted(f['path'].split('/')[-1] for f in m['files'])))
" "$1"; }

say "T1 默认开启过滤：只跳过纯服务端模组"
gen "$WORK/m1.json"
LIST="$(published "$WORK/m1.json")"
echo "$LIST" | sed 's/^/     /'
for skipped in neo-srvonly-1.0.jar fab-srvonly-1.0.jar; do
  if echo "$LIST" | grep -qx "$skipped"; then fail "不应发布 $skipped"; else pass "已跳过纯服务端模组 $skipped"; fi
done
for kept in neo-bothdefault-1.0.jar neo-bothexplicit-1.0.jar neo-usesdist-1.0.jar \
            neo-clientonly-1.0.jar fab-both-1.0.jar fab-client-1.0.jar plain-nometa-1.0.jar; do
  if echo "$LIST" | grep -qx "$kept"; then pass "保留 $kept"; else fail "误伤：$kept 未被发布"; fi
done
if grep -q 'skipping server-only mod' "$WORK/diag.txt"; then
  pass "日志记录了跳过原因"
else
  fail "日志没有跳过记录"
fi

say "T2 --force-publish 覆盖判定"
gen "$WORK/m2.json" --force-publish 'neo-srvonly*.jar'
LIST2="$(published "$WORK/m2.json")"
if echo "$LIST2" | grep -qx "neo-srvonly-1.0.jar"; then pass "force-publish 强制发布了服务端模组"; else fail "force-publish 未生效"; fi
if echo "$LIST2" | grep -qx "fab-srvonly-1.0.jar"; then fail "未列入 force 的服务端模组不应发布"; else pass "其余服务端模组仍被过滤"; fi

say "T3 --no-filter-server-only 关闭过滤"
gen "$WORK/m3.json" --no-filter-server-only
LIST3="$(published "$WORK/m3.json")"
COUNT="$(echo "$LIST3" | grep -c .)"
if [ "$COUNT" = "9" ]; then pass "9 个 jar 全部发布"; else fail "期望 9 个，实际 $COUNT"; fi

say "T4 发布器不会发布自己（独立进程形态）"
cp "$SERVER_JAR" "$MODS/modsync-server.jar"
gen "$WORK/m4.json"
LIST4="$(published "$WORK/m4.json")"
if echo "$LIST4" | grep -qx "modsync-server.jar"; then
  fail "不应发布自己的 jar"
else
  pass "已排除自身 jar（modsync-server.jar）"
fi
COUNT4="$(echo "$LIST4" | grep -c .)"
if [ "$COUNT4" = "7" ]; then pass "其余 7 个文件照常发布"; else fail "期望 7 个，实际 $COUNT4"; fi
if grep -q 'not publishing our own jar' "$WORK/diag.txt"; then
  pass "日志记录了自身排除"
else
  fail "日志缺少自身排除记录"
fi
rm -f "$MODS/modsync-server.jar"

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAILED 项"
echo "工作目录: $WORK"
echo "======================================"
[ "$FAILED" -eq 0 ]
