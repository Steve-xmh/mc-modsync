package io.modsync.agent;

import io.modsync.common.Args;
import io.modsync.common.Json;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Agent configuration, loaded from {@code modsync/config.json} and overridden by CLI/system properties. */
public final class AgentConfig {

    public static final String CONFIG_DIR = "modsync";
    public static final String CONFIG_FILE = "config.json";

    public String manifestUrl = "";
    public String serverDirName = "mods-server";
    public String targetDirName = "mods";
    public boolean strict = false;
    /** 忽略清单 generatedAt 顺序护栏（`--force-sync`）。 */
    public boolean forceSync = false;
    public boolean dryRun = false;
    public boolean verbose = false;
    public int timeoutSeconds = 30;
    public int parallelDownloads = 4;
    /** Optional overrides for client version detection. */
    public String clientMinecraftVersion = "";
    public String clientLoaderVersion = "";
    /** Where those overrides came from, quoted in mismatch warnings. */
    public String clientVersionSource = "";
    /** warn (default) | abort | ignore - what to do when the server's loader version differs. */
    public String loaderMismatch = "warn";
    /** skip (default) | warn | abort - what to do on a Minecraft version mismatch. */
    public String minecraftMismatch = "skip";
    /** Seconds to keep the GUI reminder on screen (0 disables the window). */
    public int warnHoldSeconds = 8;

    public static Path configFile(Path gameDir) {
        return gameDir.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
    }

    public static Path workDir(Path gameDir) {
        return gameDir.resolve(CONFIG_DIR);
    }

    /** Loads the config, applies CLI/system-property overrides and writes a template when absent. */
    public static AgentConfig load(Path gameDir, Args args, PrintStream log) throws IOException {
        AgentConfig cfg = new AgentConfig();
        Path file = configFile(gameDir);
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> root = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)), "config");
                cfg.manifestUrl = Json.str(root, "manifestUrl", cfg.manifestUrl);
                cfg.serverDirName = Json.str(root, "serverDir", cfg.serverDirName);
                cfg.targetDirName = Json.str(root, "targetDir", cfg.targetDirName);
                cfg.strict = Json.bool(root, "strict", cfg.strict);
                cfg.forceSync = Json.bool(root, "forceSync", cfg.forceSync);
                cfg.timeoutSeconds = (int) Json.num(root, "timeoutSeconds", cfg.timeoutSeconds);
                cfg.parallelDownloads = (int) Json.num(root, "parallelDownloads", cfg.parallelDownloads);
                cfg.clientMinecraftVersion = Json.str(root, "clientMinecraftVersion", cfg.clientMinecraftVersion);
                cfg.clientLoaderVersion = Json.str(root, "clientLoaderVersion", cfg.clientLoaderVersion);
                if (!cfg.clientMinecraftVersion.isBlank() || !cfg.clientLoaderVersion.isBlank()) {
                    cfg.clientVersionSource = "modsync/config.json";
                }
                cfg.loaderMismatch = Json.str(root, "loaderMismatch", cfg.loaderMismatch);
                cfg.minecraftMismatch = Json.str(root, "minecraftMismatch", cfg.minecraftMismatch);
                cfg.warnHoldSeconds = (int) Json.num(root, "warnHoldSeconds", cfg.warnHoldSeconds);
            } catch (RuntimeException e) {
                log.println("[modsync] 警告: 配置文件无法解析，使用默认值: " + e.getMessage());
            }
        }

        cfg.manifestUrl = firstNonBlank(
                args.get("manifest-url"),
                System.getProperty("modsync.manifestUrl"),
                System.getenv("MODSYNC_MANIFEST_URL"),
                cfg.manifestUrl);
        if (args.has("strict")) {
            cfg.strict = args.flag("strict");
        }
        if (args.has("force-sync")) {
            cfg.forceSync = true;
        }
        if (args.has("dry-run")) {
            cfg.dryRun = true;
        }
        if (args.has("verbose")) {
            cfg.verbose = true;
        }
        String timeout = args.get("timeout", System.getProperty("modsync.timeout"));
        if (timeout != null) {
            cfg.timeoutSeconds = Integer.parseInt(timeout.trim());
        }
        String parallel = args.get("parallel", System.getProperty("modsync.parallel"));
        if (parallel != null) {
            cfg.parallelDownloads = Math.max(1, Integer.parseInt(parallel.trim()));
        }
        if (args.has("server-dir")) {
            cfg.serverDirName = args.get("server-dir");
        }
        if (args.has("client-mc-version")) {
            cfg.clientMinecraftVersion = args.get("client-mc-version");
            cfg.clientVersionSource = "命令行参数";
        }
        if (args.has("client-loader-version")) {
            cfg.clientLoaderVersion = args.get("client-loader-version");
            cfg.clientVersionSource = "命令行参数";
        }
        if (args.flag("abort-on-mismatch")) {
            cfg.loaderMismatch = "abort";
            cfg.minecraftMismatch = "abort";
        }
        if (args.flag("no-version-check")) {
            cfg.loaderMismatch = "ignore";
            cfg.minecraftMismatch = "warn";
        }
        if (args.has("warn-hold")) {
            try {
                cfg.warnHoldSeconds = Math.max(0, Integer.parseInt(args.get("warn-hold").trim()));
            } catch (NumberFormatException e) {
                log.println("[modsync] 警告: --warn-hold 不是数字，忽略");
            }
        }

        if (!Files.isRegularFile(file)) {
            cfg.writeTemplate(gameDir, log);
        }
        return cfg;
    }

    private void writeTemplate(Path gameDir, PrintStream log) {
        try {
            Files.createDirectories(configFile(gameDir).getParent());
            Files.writeString(configFile(gameDir), Json.write(toJson()), StandardCharsets.UTF_8);
            log.println("[modsync] 已生成配置模板: " + configFile(gameDir));
        } catch (IOException e) {
            log.println("[modsync] 警告: 无法写入配置模板: " + e.getMessage());
        }
    }

    public Map<String, Object> toJson() {
        Map<String, Object> root = Json.map();
        root.put("manifestUrl", manifestUrl);
        root.put("serverDir", serverDirName);
        root.put("targetDir", targetDirName);
        root.put("strict", strict);
        root.put("forceSync", forceSync);
        root.put("timeoutSeconds", (long) timeoutSeconds);
        root.put("parallelDownloads", (long) parallelDownloads);
        root.put("clientMinecraftVersion", clientMinecraftVersion);
        root.put("clientLoaderVersion", clientLoaderVersion);
        root.put("loaderMismatch", loaderMismatch);
        root.put("minecraftMismatch", minecraftMismatch);
        root.put("warnHoldSeconds", (long) warnHoldSeconds);
        return root;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
