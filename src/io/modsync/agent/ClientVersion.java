package io.modsync.agent;

import io.modsync.common.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Best-effort detection of the client's Minecraft and mod-loader versions, so the agent can tell the
 * player to upgrade their loader when the server moved on.
 *
 * <p>Sources, in order: explicit config, Prism/MultiMC {@code mmc-pack.json} (searched in the game
 * directory and its parents), then a scan of {@code versions/<name>/<name>.json} as written by the
 * official launcher and PCL2.
 */
public final class ClientVersion {

    private static final Pattern NEOFORGE_IN_LIB = Pattern.compile("net\\.neoforged:neoforge:([0-9][0-9A-Za-z.\\-+]*)");
    private static final Pattern FORGE_IN_LIB = Pattern.compile("net\\.minecraftforge:forge:([0-9][0-9A-Za-z.\\-+]*)");
    private static final Pattern FABRIC_IN_LIB = Pattern.compile("net\\.fabricmc:fabric-loader:([0-9][0-9A-Za-z.\\-+]*)");
    private static final Pattern MC_VERSION = Pattern.compile("\"?(?:inheritsFrom|id)\"?\\s*:\\s*\"(1\\.[0-9]+(?:\\.[0-9]+)?)");

    public String minecraft = "";
    public String loaderType = "neoforge";
    public String loaderVersion = "";
    /** Where the values came from, shown in warnings so users can fix a wrong detection. */
    public String source = "未检测到";

    public static ClientVersion detect(Path gameDir, AgentConfig cfg) {
        ClientVersion v = new ClientVersion();
        if (cfg.clientMinecraftVersion != null && !cfg.clientMinecraftVersion.isBlank()) {
            v.minecraft = cfg.clientMinecraftVersion.trim();
        }
        if (cfg.clientLoaderVersion != null && !cfg.clientLoaderVersion.isBlank()) {
            v.loaderVersion = cfg.clientLoaderVersion.trim();
        }
        if (!v.minecraft.isEmpty() && !v.loaderVersion.isEmpty()) {
            v.source = cfg.clientVersionSource == null || cfg.clientVersionSource.isBlank()
                    ? "配置覆盖" : cfg.clientVersionSource;
            return v;
        }

        // Prism Launcher / MultiMC: <instance>/mmc-pack.json (game dir is <instance>/minecraft)
        Path dir = gameDir.toAbsolutePath().normalize();
        for (int depth = 0; depth < 3 && dir != null; depth++, dir = dir.getParent()) {
            Path pack = dir.resolve("mmc-pack.json");
            if (Files.isRegularFile(pack)) {
                if (parseMmcPack(pack, v)) {
                    v.source = pack.toString();
                    if (!v.loaderVersion.isEmpty() && !v.minecraft.isEmpty()) {
                        return v;
                    }
                }
            }
        }

        // Official launcher / PCL2: versions/<name>/<name>.json
        Path versions = gameDir.resolve("versions");
        if (Files.isDirectory(versions)) {
            try (Stream<Path> stream = Files.list(versions)) {
                for (Path candidate : stream.filter(Files::isDirectory).toList()) {
                    Path json = candidate.resolve(candidate.getFileName().toString() + ".json");
                    if (Files.isRegularFile(json) && parseVersionJson(json, v)) {
                        v.source = json.toString();
                        if (!v.loaderVersion.isEmpty()) {
                            return v;
                        }
                    }
                }
            } catch (IOException ignored) {
                // detection is best-effort only
            }
        }
        return v;
    }

    private static boolean parseMmcPack(Path file, ClientVersion v) {
        try {
            Map<String, Object> root = Json.asObject(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            if (root == null) {
                return false;
            }
            List<Object> components = Json.asArray(root.get("components"));
            if (components == null) {
                return false;
            }
            boolean found = false;
            for (Object raw : components) {
                Map<String, Object> c = Json.asObject(raw);
                if (c == null) {
                    continue;
                }
                String uid = Json.str(c, "uid", "");
                String version = Json.str(c, "version", "");
                if (version.isBlank()) {
                    continue;
                }
                if ("net.minecraft".equals(uid)) {
                    v.minecraft = version;
                    found = true;
                } else if ("net.neoforged".equals(uid)) {
                    v.loaderType = "neoforge";
                    v.loaderVersion = version;
                    found = true;
                } else if ("net.minecraftforge".equals(uid)) {
                    v.loaderType = "forge";
                    v.loaderVersion = version;
                    found = true;
                } else if (uid.endsWith("fabric-loader") || "net.fabricmc.fabric-loader".equals(uid)) {
                    v.loaderType = "fabric";
                    v.loaderVersion = version;
                    found = true;
                }
            }
            return found;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean parseVersionJson(Path file, ClientVersion v) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            boolean found = false;
            Matcher neo = NEOFORGE_IN_LIB.matcher(text);
            Matcher forge = FORGE_IN_LIB.matcher(text);
            Matcher fabric = FABRIC_IN_LIB.matcher(text);
            if (neo.find()) {
                v.loaderType = "neoforge";
                v.loaderVersion = neo.group(1);
                found = true;
            } else if (forge.find()) {
                v.loaderType = "forge";
                v.loaderVersion = forge.group(1);
                found = true;
            } else if (fabric.find()) {
                v.loaderType = "fabric";
                v.loaderVersion = fabric.group(1);
                found = true;
            }
            if (v.minecraft.isEmpty()) {
                Matcher mc = MC_VERSION.matcher(text);
                if (mc.find()) {
                    v.minecraft = mc.group(1);
                }
            }
            return found;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
