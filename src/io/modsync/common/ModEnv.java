package io.modsync.common;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Best-effort detection of which side(s) a mod runs on, so the publisher can skip pure server-side
 * mods (clients do not need - and often must not have - them).
 *
 * <p>Two sources are consulted, in order of reliability:
 *
 * <ol>
 *   <li>{@code fabric.mod.json}'s {@code environment} field ({@code server} = server only).</li>
 *   <li>The {@code @Mod} annotation's {@code dist} element in the mod's own class file
 *       ({@code DEDICATED_SERVER} = server only). This is read by parsing the class file's
 *       {@code RuntimeVisibleAnnotations} attribute - string matching would misfire on dual-side mods
 *       that merely <em>reference</em> {@code Dist.DEDICATED_SERVER} in code.</li>
 * </ol>
 *
 * <p>No external dependencies and no Minecraft classes are loaded; everything is byte-level parsing.
 */
public final class ModEnv {

    public enum Env {
        /** Runs on both sides (no side information, or explicitly neither). */
        BOTH,
        CLIENT_ONLY,
        SERVER_ONLY,
        /** Could not be determined. */
        UNKNOWN
    }

    private static final String NEOFORGE_MOD_ANNOTATION = "Lnet/neoforged/fml/common/Mod;";
    private static final String FORGE_MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";
    private static final long MAX_CLASS_BYTES = 4L * 1024 * 1024;

    private ModEnv() {
    }

    public static Env detect(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String fabric = readEntry(zip, "fabric.mod.json");
            if (fabric != null) {
                Env env = fromFabric(fabric);
                if (env != null) {
                    return env;
                }
            }
            String toml = readEntry(zip, "META-INF/neoforge.mods.toml");
            if (toml == null) {
                toml = readEntry(zip, "META-INF/mods.toml");
            }
            String modId = toml == null ? null : ModMeta.firstTomlModId(toml);
            Env env = fromModAnnotation(zip, modId);
            if (env != null) {
                return env;
            }
        } catch (IOException | RuntimeException e) {
            // Detection is advisory: unreadable jars are simply treated as unknown.
        }
        return Env.UNKNOWN;
    }

    // ------------------------------------------------------------------ fabric

    private static Env fromFabric(String json) {
        try {
            Map<String, Object> root = Json.asObject(Json.parse(json));
            if (root == null) {
                return null;
            }
            String environment = Json.str(root, "environment", null);
            if (environment == null) {
                return null;
            }
            return switch (environment.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "server" -> Env.SERVER_ONLY;
                case "client" -> Env.CLIENT_ONLY;
                case "*" -> Env.BOTH;
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------- @Mod(dist = ...)

    private static Env fromModAnnotation(ZipFile zip, String modId) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.endsWith(".class") || entry.isDirectory()) {
                continue;
            }
            long size = entry.getSize();
            if (size > MAX_CLASS_BYTES) {
                continue;
            }
            byte[] data;
            try (InputStream in = zip.getInputStream(entry)) {
                data = in.readAllBytes();
            }
            if (data.length > MAX_CLASS_BYTES) {
                continue;
            }
            Env env = parseClassAnnotations(data, modId);
            if (env != null) {
                return env;
            }
        }
        return null;
    }

    /** @return the mod's environment, or {@code null} when this class declares no matching {@code @Mod} */
    static Env parseClassAnnotations(byte[] data, String modId) {
        try {
            ClassReader r = new ClassReader(data);
            if (r.u4() != 0xCAFEBABEL) {
                return null;
            }
            r.u2(); // minor
            r.u2(); // major
            int cpCount = r.u2();
            String[] utf8 = new String[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = r.u1();
                switch (tag) {
                    case 1 -> utf8[i] = r.utf8(r.u2());
                    case 7, 8, 16, 19, 20 -> r.u2();
                    case 15 -> {
                        r.u1();
                        r.u2();
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> r.u4();
                    case 5, 6 -> {
                        r.u8();
                        i++; // long/double occupy two constant-pool slots
                    }
                    default -> {
                        return null; // unknown tag: give up on this class
                    }
                }
            }
            r.u2(); // access flags
            r.u2(); // this class
            r.u2(); // super class
            int interfaces = r.u2();
            r.skip(2L * interfaces);
            if (!skipMembers(r, r.u2())) {
                return null;
            }
            if (!skipMembers(r, r.u2())) {
                return null;
            }
            int attributes = r.u2();
            for (int i = 0; i < attributes; i++) {
                int nameIndex = r.u2();
                long length = r.u4();
                String attrName = (nameIndex > 0 && nameIndex < utf8.length) ? utf8[nameIndex] : null;
                if ("RuntimeVisibleAnnotations".equals(attrName)) {
                    byte[] annotations = r.bytes((int) length);
                    return evaluateAnnotations(annotations, utf8, modId);
                }
                r.skip(length);
            }
            return null;
        } catch (RuntimeException e) {
            // Set -Dmodsync.debug=true to see why a class file could not be parsed.
            if (Boolean.getBoolean("modsync.debug")) {
                System.out.println("[modsync] ModEnv: cannot parse class annotations: " + e);
            }
            return null;
        }
    }

    private static boolean skipMembers(ClassReader r, int count) {
        for (int i = 0; i < count; i++) {
            r.u2(); // access
            r.u2(); // name
            r.u2(); // descriptor
            int attributes = r.u2();
            for (int a = 0; a < attributes; a++) {
                r.u2();
                r.skip(r.u4());
            }
        }
        return true;
    }

    private static Env evaluateAnnotations(byte[] data, String[] utf8, String modId) {
        try {
            ClassReader r = new ClassReader(data);
            int count = r.u2();
            for (int i = 0; i < count; i++) {
                int typeIndex = r.u2();
                String type = (typeIndex > 0 && typeIndex < utf8.length) ? utf8[typeIndex] : null;
                boolean isMod = NEOFORGE_MOD_ANNOTATION.equals(type) || FORGE_MOD_ANNOTATION.equals(type);
                Map<String, String> strings = new LinkedHashMap<>();
                Map<String, List<String>> enums = new LinkedHashMap<>();
                int pairs = r.u2();
                for (int p = 0; p < pairs; p++) {
                    int nameIdx = r.u2();
                    String element = (nameIdx > 0 && nameIdx < utf8.length) ? utf8[nameIdx] : "";
                    readElementValue(r, utf8, element, strings, enums);
                }
                if (!isMod) {
                    continue;
                }
                String value = strings.get("value");
                if (modId != null && value != null && !modId.isEmpty() && !modId.equals(value)) {
                    continue; // a different mod's entrypoint in the same jar
                }
                // NeoForge declares `Dist[] dist() default {CLIENT, DEDICATED_SERVER}`:
                // - absent            -> both sides
                // - {CLIENT, SERVER}  -> both sides (explicitly)
                // - {SERVER}          -> server only
                // - {CLIENT}          -> client only
                List<String> dists = enums.getOrDefault("dist", List.of());
                boolean server = dists.contains("DEDICATED_SERVER");
                boolean client = dists.contains("CLIENT");
                if (server && !client) {
                    return Env.SERVER_ONLY;
                }
                if (client && !server) {
                    return Env.CLIENT_ONLY;
                }
                return Env.BOTH;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reads one element_value, recording string ('s') and enum ('e') payloads we care about. */
    private static void readElementValue(ClassReader r, String[] utf8, String element,
                                         Map<String, String> strings, Map<String, List<String>> enums) {
        int tag = r.u1();
        switch (tag) {
            case 's' -> strings.put(element, utf8At(r.u2(), utf8));
            case 'e' -> {
                r.u2(); // enum type descriptor
                enums.computeIfAbsent(element, k -> new java.util.ArrayList<>()).add(utf8At(r.u2(), utf8));
            }
            case 'c' -> r.u2();
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> r.u2();
            case '@' -> {
                r.u2(); // nested annotation type: not interesting for @Mod
                int pairs = r.u2();
                for (int i = 0; i < pairs; i++) {
                    readElementValue(r, utf8, utf8At(r.u2(), utf8), strings, enums);
                }
            }
            case '[' -> {
                int n = r.u2();
                for (int i = 0; i < n; i++) {
                    readElementValue(r, utf8, element, strings, enums);
                }
            }
            default -> throw new IllegalStateException("unknown element_value tag " + tag);
        }
    }

    private static String utf8At(int index, String[] utf8) {
        return (index > 0 && index < utf8.length) ? utf8[index] : "";
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Minimal big-endian class-file reader with bounds checking. */
    private static final class ClassReader {
        private final DataInputStream in;

        ClassReader(byte[] data) {
            this.in = new DataInputStream(new java.io.ByteArrayInputStream(data));
        }

        int u1() {
            try {
                return in.readUnsignedByte();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        int u2() {
            try {
                return in.readUnsignedShort();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        long u4() {
            try {
                return in.readInt() & 0xFFFFFFFFL;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void u8() {
            try {
                in.readLong();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void skip(long n) {
            try {
                long remaining = n;
                while (remaining > 0) {
                    long skipped = in.skip(remaining);
                    if (skipped <= 0) {
                        if (in.read() < 0) {
                            throw new IllegalStateException("unexpected end of class file");
                        }
                        remaining--;
                    } else {
                        remaining -= skipped;
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        String utf8(int length) {
            try {
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        byte[] bytes(int length) {
            try {
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                return bytes;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
