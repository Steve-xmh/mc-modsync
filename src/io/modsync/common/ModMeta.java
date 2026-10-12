package io.modsync.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Hashing plus best-effort mod-id extraction from a mod jar. */
public final class ModMeta {

    private ModMeta() {
    }

    /**
     * True when {@code path} is a real {@code .jar} file on the default filesystem.
     *
     * <p>FML also hands out paths for jar-in-jar children and other loader internals. Those live on a
     * custom {@link java.nio.file.FileSystem} mounted over the owning jar, so they report
     * {@code isRegularFile() == true} even though no such file exists on disk — the loader reads them
     * straight out of the parent jar. Treating them as files would publish a jar this server never had
     * (the client would then get a standalone copy of a mod the parent jar already bundles, plus a
     * {@code modId}-less manifest entry).
     */
    public static boolean isRealJar(Path path) {
        if (path == null || path.getFileSystem() != FileSystems.getDefault()) {
            return false;
        }
        Path name = path.getFileName();
        return name != null && name.toString().endsWith(".jar") && Files.isRegularFile(path);
    }

    /**
     * Reads the {@code FMLModType} attribute of the jar's {@code META-INF/MANIFEST.MF}.
     *
     * <p>FML uses this attribute to keep non-mod jars on the classpath: a jar declaring
     * {@code LIBRARY} (KotlinForForge and friends) is loaded by the loader but never appears in
     * {@code ModList.getMods()} <em>nor</em> {@code ModList.getModFiles()} — verified against NeoForge
     * 21.1.248. Clients need those jars as soon as a published mod declares them as a language or
     * library provider, so the publisher has to find them by inspecting the mods directory itself.
     *
     * @return the raw value (e.g. {@code LIBRARY}), or {@code null} when absent or unreadable
     */
    public static String fmlModType(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String manifest = readEntry(zip, "META-INF/MANIFEST.MF");
            if (manifest == null) {
                return null;
            }
            // Manifest lines are wrapped at 72 bytes; a leading space continues the previous attribute.
            StringBuilder unfolded = new StringBuilder(manifest.length());
            for (String line : manifest.split("\r\n|\n|\r", -1)) {
                if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                    unfolded.append(line, 1, line.length());
                } else {
                    unfolded.append('\n').append(line);
                }
            }
            for (String line : unfolded.toString().split("\n", -1)) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("FMLModType")) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static String sha256(Path file) throws IOException {
        return digest("SHA-256", file);
    }

    public static String sha1(Path file) throws IOException {
        return digest("SHA-1", file);
    }

    private static String digest(String algorithm, Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Reads the mod id from NeoForge ({@code META-INF/neoforge.mods.toml}), Forge
     * ({@code META-INF/mods.toml}) or Fabric ({@code fabric.mod.json}) metadata.
     *
     * @return the mod id, or {@code null} when it cannot be determined
     */
    public static String readModId(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String toml = readEntry(zip, "META-INF/neoforge.mods.toml");
            if (toml == null) {
                toml = readEntry(zip, "META-INF/mods.toml");
            }
            if (toml != null) {
                String id = firstTomlModId(toml);
                if (id != null) {
                    return id;
                }
            }
            String fabric = readEntry(zip, "fabric.mod.json");
            if (fabric != null) {
                String id = firstJsonId(fabric);
                if (id != null) {
                    return id;
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
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

    /** Handles both {@code modId="x"} and {@code modId = 'x'} forms, ignoring comments. */
    static String firstTomlModId(String toml) {
        for (String rawLine : toml.split("\r?\n")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("modId")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2) {
                char q = value.charAt(0);
                if ((q == '"' || q == '\'') && value.charAt(value.length() - 1) == q) {
                    return value.substring(1, value.length() - 1);
                }
            }
        }
        return null;
    }

    static String firstJsonId(String json) {
        try {
            Map<String, Object> obj = Json.asObject(Json.parse(json));
            return obj == null ? null : Json.str(obj, "id", null);
        } catch (RuntimeException e) {
            return null;
        }
    }

}
