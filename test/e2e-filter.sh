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

[ -f "$SERVER_JAR" ] || { echo "缺少 ${SERVER_JAR}，请先 ./build.sh" >&2; exit 1; }

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

# <manifest> <file name> -> yes/no：该条目是否带 modId
entry_has_modid() { python3 - "$1" "$2" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]))
print("yes" if any(f["path"].split("/")[-1]==sys.argv[2] and f.get("modId") for f in m["files"]) else "no")
PY
}

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

say "T5 --mod-file：发布 FML 实际加载的文件（可位于 --mods 之外）"
mkdir -p "$WORK/elsewhere"
make_neo_jar "$WORK/elsewhere/external-2.0.jar" external_mod BothDefaultMod.class
gen "$WORK/m5.json" --mod-file "$WORK/elsewhere/external-2.0.jar"
LIST5="$(published "$WORK/m5.json")"
if echo "$LIST5" | grep -qx "external-2.0.jar"; then
  pass "目录之外的 --mod-file 被发布"
else
  fail "--mod-file 未被发布"
fi
if echo "$LIST5" | grep -qx "plain-nometa-1.0.jar"; then
  pass "目录扫描仍然生效（两者可以并存）"
else
  fail "目录扫描被破坏"
fi

say "T6 --mod-file 与目录里的同一文件：只算一次、不报重复"
gen "$WORK/m6.json" --mod-file "$MODS/neo-bothdefault-1.0.jar"
LIST6="$(published "$WORK/m6.json")"
COUNT6="$(echo "$LIST6" | grep -c .)"
DUPES="$(echo "$LIST6" | grep -cx 'neo-bothdefault-1.0.jar')"
if [ "$DUPES" = "1" ]; then pass "同一文件只出现一次"; else fail "同一文件出现 $DUPES 次"; fi
if [ "$COUNT6" = "7" ]; then pass "总数仍为 7（未因重复参数变多）"; else fail "期望 7，实际 $COUNT6"; fi
if grep -q 'duplicate file name' "$WORK/diag.txt"; then
  fail "同一路径被误报为重名"
else
  pass "同一路径没有被误报为重名"
fi

say "T7 加载器报告的 jar-in-jar 虚拟路径绝不能被当成可发布文件"
# 造一个「父 jar 内嵌模组 jar」的形状（真实案例：天境 aether 内嵌 accessories，
# 结果被当成独立文件发布到了玩家 mods/ 里，而服务端根本没有那个文件）。
mkdir -p "$WORK/jij/inner" "$WORK/jij/outer/META-INF/jarjar"
make_neo_jar "$WORK/jij/inner/bundled-mod-1.0.jar" bundled_mod BothDefaultMod.class
cp "$WORK/jij/inner/bundled-mod-1.0.jar" "$WORK/jij/outer/META-INF/jarjar/bundled-mod-1.0.jar"
(cd "$WORK/jij/outer" && jar --create --file "$MODS/parent-with-jij-1.0.jar" .)

# FMLModType: LIBRARY 的库（KotlinForForge 的形态）：FML 会加载它，但它既不在
# ModList.getMods() 也不在 getModFiles() 里，发布器只能靠读 MANIFEST 找到它。
mkdir -p "$WORK/libstage/lib"
printf 'not a mod\n' > "$WORK/libstage/lib/payload.txt"
cat > "$WORK/libstage/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
FMLModType: LIBRARY
EOF
(cd "$WORK/libstage" && jar --create --file "$MODS/neo-library-1.0.jar" --manifest MANIFEST.MF .)

mkdir -p "$WORK/probe-cp"
(cd "$WORK/probe-cp" && jar --extract --file "$SERVER_JAR")
mkdir -p "$WORK/probe"
cat > "$WORK/probe/RealJarProbe.java" <<'JAVA'
import io.modsync.common.ModMeta;
import io.modsync.server.SyncServer;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

/** 断言：只有默认文件系统上的真实 .jar 才可能被发布（用 zip 文件系统模拟 FML 的 jar-in-jar）。 */
public final class RealJarProbe {
    public static void main(String[] args) throws Exception {
        Path parent = Path.of(args[0]);
        Path real = Path.of(args[1]);
        Path library = Path.of(args[2]);
        Path state = Path.of(args[3]);
        try (FileSystem zipFs = FileSystems.newFileSystem(parent)) {
            Path nested = zipFs.getPath("/META-INF/jarjar/bundled-mod-1.0.jar");
            check(!ModMeta.isRealJar(nested), "zip 文件系统里的嵌套 jar 必须被判为非真实文件");
            check(ModMeta.isRealJar(real), "真实 jar 必须被判为真实文件");

            SyncServer virtual = new SyncServer(options(state, List.of(nested)));
            virtual.generate();
            check(virtual.fileCount() == 0, "只有虚拟路径时不应发布任何文件，实际 " + virtual.fileCount());
            check(!virtual.manifestJson().contains("bundled-mod"), "虚拟路径不得出现在清单里");

            SyncServer actual = new SyncServer(options(state, List.of(real)));
            actual.generate();
            check(actual.fileCount() == 1, "真实 jar 应被发布，实际 " + actual.fileCount());
            check(actual.manifestJson().contains(real.getFileName().toString()), "真实 jar 应出现在清单里");
        }

        check("LIBRARY".equalsIgnoreCase(ModMeta.fmlModType(library)), "FMLModType: LIBRARY 必须被识别");
        check(ModMeta.fmlModType(real) == null, "普通模组 jar 不应有 FMLModType");
        System.out.println("probe-ok");
    }

    private static SyncServer.Options options(Path state, List<Path> explicit) {
        return new SyncServer.Options(List.of(), List.of(), 0, "127.0.0.1", "", "", "", state,
                false, true, true, List.of(), "1.21.1", "neoforge", "21.1.248", explicit);
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            System.err.println("probe-fail: " + message);
            System.exit(1);
        }
    }
}
JAVA
if javac -encoding UTF-8 -cp "$WORK/probe-cp" -d "$WORK/probe-cp" "$WORK/probe/RealJarProbe.java" >"$WORK/probe-javac.log" 2>&1; then
  if java -cp "$WORK/probe-cp" RealJarProbe "$MODS/parent-with-jij-1.0.jar" \
        "$MODS/neo-bothdefault-1.0.jar" "$MODS/neo-library-1.0.jar" \
        "$WORK/probe-state.json" >"$WORK/probe.log" 2>&1; then
    pass "虚拟路径被判为非文件且不发布；同一判定下真实 jar 照常发布"
    pass "FMLModType 读取正确（LIBRARY 被识别、普通模组为 null）"
  else
    fail "探针断言失败: $(cat "$WORK/probe.log")"
  fi
else
  fail "探针编译失败: $(cat "$WORK/probe-javac.log")"
fi

say "T8 FML LIBRARY 形态的 jar（没有 mods.toml）也必须发布"
gen "$WORK/m8.json"
LIST8="$(published "$WORK/m8.json")"
if echo "$LIST8" | grep -qx "neo-library-1.0.jar"; then
  pass "LIBRARY jar 被发布"
else
  fail "LIBRARY jar 未被发布"
fi
if [ "$(entry_has_modid "$WORK/m8.json" neo-library-1.0.jar)" = "no" ]; then
  pass "LIBRARY jar 没有 modId（确认它不是 mod，却仍被发布）"
else
  fail "LIBRARY jar 不应带 modId"
fi
COUNT8="$(echo "$LIST8" | grep -c .)"
if [ "$COUNT8" = "9" ]; then
  pass "目录里 11 个 jar：过滤 2 个纯服务端后发布 9 个"
else
  fail "期望 9 个，实际 $COUNT8"
fi
if echo "$LIST8" | grep -qx "bundled-mod-1.0.jar"; then
  fail "内嵌 jar 被单独发布了（应只随父 jar 一起发布）"
else
  pass "内嵌在父 jar 里的模组没有被单独发布"
fi

echo
echo "======================================"
echo "通过 $PASS 项，失败 $FAILED 项"
echo "工作目录: $WORK"
echo "======================================"
[ "$FAILED" -eq 0 ]
