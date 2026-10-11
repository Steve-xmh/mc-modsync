package io.modsync.neoforge;

import io.modsync.common.Json;
import io.modsync.server.SyncServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NeoForge wrapper around {@link SyncServer}: the dedicated server itself publishes its mods, so
 * admins do not have to run a second process.
 *
 * <p>Config lives in {@code config/modsync-server.json}; state ({@code serverId}) is kept separately
 * in {@code config/modsync-server-state.json}. The publisher starts when the server has finished
 * loading and stops with it.
 */
@Mod("modsync")
@EventBusSubscriber(modid = "modsync")
public final class ModSyncMod {

    private static final String CONFIG_NAME = "modsync-server.json";
    private static volatile SyncServer running;

    public ModSyncMod() {
        // Nothing to do at construction time: the publisher starts with the server.
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (running != null) {
            return;
        }
        Config cfg = null;
        net.neoforged.fml.loading.VersionInfo info = null;
        try {
            cfg = Config.load();
            if (!cfg.enabled) {
                System.out.println("[modsync] disabled by config/modsync-server.json");
                return;
            }
            info = net.neoforged.fml.loading.FMLLoader.versionInfo();
            Path gameDir = FMLPaths.GAMEDIR.get();
            List<String> excludes = new ArrayList<>(cfg.excludes);
            String self = selfJarName();
            if (self != null && !excludes.contains(self)) {
                excludes.add(self);
                System.out.println("[modsync] not publishing our own mod jar: " + self);
            }
            List<Path> loaded = cfg.publishLoadedMods ? loadedModFiles(gameDir) : List.of();
            if (cfg.publishLoadedMods && loaded.isEmpty()) {
                System.out.println("[modsync] WARN FML reported no loaded mods; falling back to scanning mods/");
            }
            List<Path> dirs = new ArrayList<>();
            if (cfg.publishLoadedMods && loaded.isEmpty()) {
                dirs.add(gameDir.resolve("mods"));
            }
            for (String dir : cfg.modDirs) {
                dirs.add(gameDir.resolve(dir));
            }
            for (String dir : cfg.extraDirs) {
                Path extra = gameDir.resolve(dir);
                // Create the client-only drop folder so admins can just put jars there.
                if (!Files.isDirectory(extra)) {
                    try {
                        Files.createDirectories(extra);
                    } catch (IOException ignored) {
                        // SyncServer will simply warn about the missing directory.
                    }
                }
                dirs.add(extra);
            }
            SyncServer.Options options = new SyncServer.Options(
                    dirs,
                    excludes,
                    cfg.port,
                    cfg.bind,
                    cfg.baseUrl,
                    cfg.packName,
                    cfg.serverId,
                    FMLPaths.CONFIGDIR.get().resolve("modsync-server-state.json"),
                    cfg.modrinthLookup,
                    false,
                    cfg.filterServerOnlyMods,
                    cfg.forcePublish,
                    info == null ? "" : info.mcVersion(),
                    "neoforge",
                    info == null ? "" : info.neoForgeVersion(),
                    loaded);
            SyncServer server = new SyncServer(options);
            server.generate();
            server.start();
            running = server;
            if (!loaded.isEmpty()) {
                System.out.println("[modsync] publishing the " + loaded.size()
                        + " mod jar(s) FML actually loaded (no directory scan needed)");
            }
            System.out.println("[modsync] publishing " + server.fileCount() + " files ("
                    + String.format(java.util.Locale.ROOT, "%.1f", server.totalBytes() / (1024.0 * 1024.0))
                    + " MB) at " + server.effectiveBaseUrl() + " - clients sync from "
                    + server.effectiveBaseUrl() + "/manifest.json");
            if (info != null) {
                System.out.println("[modsync] announcing Minecraft " + info.mcVersion()
                        + " / NeoForge " + info.neoForgeVersion()
                        + " so clients can be told to upgrade their loader when needed");
            }
        } catch (Throwable t) {
            // A broken sync server must never take the Minecraft server down.
            String hint = "";
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof java.net.BindException) {
                    hint = " (端口 " + (cfg == null ? "?" : cfg.port)
                            + " 已被占用：改 config/modsync-server.json 的 port 后重启服务器)";
                    break;
                }
            }
            System.out.println("[modsync] ERROR failed to start mod publisher: " + t + hint);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SyncServer server = running;
        running = null;
        if (server != null) {
            try {
                server.stop(0);
                System.out.println("[modsync] mod publisher stopped");
            } catch (Throwable t) {
                System.out.println("[modsync] WARN error while stopping publisher: " + t);
            }
        }
    }

    /**
     * File name of this mod's own jar, so the publisher never ships itself to clients no matter what
     * the jar is called. Resolved reflectively because {@code IModFileInfo} is not present on every
     * compile classpath; any failure simply falls back to the name-based default exclude.
     */
    private static String selfJarName() {
        try {
            net.neoforged.fml.ModContainer container = net.neoforged.fml.ModList.get()
                    .getModContainerById("modsync").orElse(null);
            if (container == null) {
                return null;
            }
            java.lang.reflect.Method getModInfo = net.neoforged.fml.ModContainer.class.getMethod("getModInfo");
            Object modInfo = getModInfo.invoke(container);
            Class<?> modInfoType = Class.forName("net.neoforged.neoforgespi.language.IModInfo");
            // IModInfo -> IModFileInfo -> IModFile -> getFilePath(); the SPI classes are not on the
            // compile classpath, so every hop goes through the declaring interface.
            Object owning = modInfoType.getMethod("getOwningFile").invoke(modInfo);
            Object file = invokeByInterface(owning, "getFile");
            Object path = invokeByInterface(file != null ? file : owning, "getFilePath");
            if (path instanceof Path p) {
                return p.getFileName().toString();
            }
        } catch (Throwable ignored) {
            // best effort only
        }
        return null;
    }

    /** Mods FML provides itself; their jars must never be shipped to clients. */
    private static final java.util.Set<String> LOADER_MOD_IDS = java.util.Set.of("minecraft", "neoforge", "forge");

    /**
     * The jars FML actually loaded on this dedicated server.
     *
     * <p>This is the source of truth instead of scanning a directory: it does not care where a mod
     * file lives, it cannot publish a jar the server is not running, and both jar-in-jar children
     * (virtual paths) and loader artifacts under {@code libraries/} are skipped automatically.
     */
    private static List<Path> loadedModFiles(Path gameDir) {
        List<Path> files = new ArrayList<>();
        java.util.Set<Path> seenFiles = new java.util.HashSet<>();
        Path libraries = gameDir.resolve("libraries").toAbsolutePath().normalize();
        try {
            for (Object modInfo : net.neoforged.fml.ModList.get().getMods()) {
                Object idValue = invokeByInterface(modInfo, "getModId");
                if (!(idValue instanceof String modId) || modId.isBlank()
                        || LOADER_MOD_IDS.contains(modId) || "modsync".equals(modId)) {
                    continue;
                }
                Object owning = invokeByInterface(modInfo, "getOwningFile");
                Object fileInfo = invokeByInterface(owning, "getFile");
                Object pathValue = invokeByInterface(fileInfo != null ? fileInfo : owning, "getFilePath");
                if (!(pathValue instanceof Path path)) {
                    continue;
                }
                Path abs = path.toAbsolutePath().normalize();
                if (!abs.getFileName().toString().endsWith(".jar")
                        || !Files.isRegularFile(abs)
                        || abs.startsWith(libraries)
                        || !seenFiles.add(abs)) {
                    continue;
                }
                files.add(abs);
            }
        } catch (Throwable ignored) {
            // Best effort; callers fall back to the configured directories.
        }
        files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    /** Invokes a public no-arg method found on one of the target's interfaces. */
    private static Object invokeByInterface(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                try {
                    return iface.getMethod(methodName).invoke(target);
                } catch (NoSuchMethodException ignored) {
                    // keep looking
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Mod config; a template is written on first start. */
    static final class Config {
        boolean enabled = true;
        /**
         * Publish whatever FML actually loaded (recommended). With this on, {@code modDirs} is only
         * an addition for jars the server does not load itself, e.g. client-only mods.
         */
        boolean publishLoadedMods = true;
        List<String> modDirs = new ArrayList<>();
        List<String> extraDirs = new ArrayList<>(List.of("modsync/extra"));
        List<String> excludes = new ArrayList<>(List.of("modsync-neoforge-*.jar", "modsync-server*.jar"));
        int port = 25590;
        String bind = "0.0.0.0";
        String baseUrl = "";
        String packName = "";
        String serverId = "";
        boolean modrinthLookup = false;
        /** Skip mods that only run on the server (detected from mod/Modrinth metadata). */
        boolean filterServerOnlyMods = true;
        /** File-name globs that stay published even when detected as server-only. */
        List<String> forcePublish = new ArrayList<>();

        static Config load() throws IOException {
            Path file = FMLPaths.CONFIGDIR.get().resolve(CONFIG_NAME);
            Config cfg = new Config();
            if (Files.isRegularFile(file)) {
                try {
                    Map<String, Object> root = Json.asObject(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
                    if (root != null) {
                        cfg.enabled = Json.bool(root, "enabled", cfg.enabled);
                        cfg.publishLoadedMods = Json.bool(root, "publishLoadedMods", cfg.publishLoadedMods);
                        cfg.modDirs = stringList(root.get("modDirs"), cfg.modDirs);
                        cfg.extraDirs = stringList(root.get("extraDirs"), cfg.extraDirs);
                        cfg.excludes = stringList(root.get("excludes"), cfg.excludes);
                        cfg.port = (int) Json.num(root, "port", cfg.port);
                        cfg.bind = Json.str(root, "bind", cfg.bind);
                        cfg.baseUrl = Json.str(root, "baseUrl", cfg.baseUrl);
                        cfg.packName = Json.str(root, "packName", cfg.packName);
                        cfg.serverId = Json.str(root, "serverId", cfg.serverId);
                        cfg.modrinthLookup = Json.bool(root, "modrinthLookup", cfg.modrinthLookup);
                        cfg.filterServerOnlyMods = Json.bool(root, "filterServerOnlyMods", cfg.filterServerOnlyMods);
                        cfg.forcePublish = stringList(root.get("forcePublish"), cfg.forcePublish);
                    }
                } catch (RuntimeException e) {
                    System.out.println("[modsync] WARN config/modsync-server.json is invalid, using defaults: " + e);
                }
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.write(cfg.toJson()) + "\n", StandardCharsets.UTF_8);
                System.out.println("[modsync] wrote default config to " + file);
            }
            if (!cfg.publishLoadedMods && cfg.modDirs.isEmpty()) {
                // Directory scanning is the only source then; fall back so this still publishes something.
                cfg.modDirs = new ArrayList<>(List.of("mods"));
                System.out.println("[modsync] publishLoadedMods=false, scanning mods/ instead");
            }
            return cfg;
        }

        private static List<String> stringList(Object node, List<String> def) {
            List<Object> raw = Json.asArray(node);
            if (raw == null) {
                return def;
            }
            List<String> out = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        }

        Map<String, Object> toJson() {
            Map<String, Object> root = Json.map();
            root.put("enabled", enabled);
            root.put("publishLoadedMods", publishLoadedMods);
            root.put("modDirs", new ArrayList<Object>(modDirs));
            root.put("extraDirs", new ArrayList<Object>(extraDirs));
            root.put("excludes", new ArrayList<Object>(excludes));
            root.put("port", (long) port);
            root.put("bind", bind);
            root.put("baseUrl", baseUrl);
            root.put("packName", packName);
            root.put("serverId", serverId);
            root.put("modrinthLookup", modrinthLookup);
            root.put("filterServerOnlyMods", filterServerOnlyMods);
            root.put("forcePublish", new ArrayList<Object>(forcePublish));
            return root;
        }
    }
}
