package io.modsync.server;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Command-line entry point for the standalone ModSync publishing server.
 *
 * <p>Compiles and runs with no third-party dependencies:
 *
 * <pre>
 *   javac -d out src/io/modsync/common/*.java src/io/modsync/server/*.java
 *   java  -cp out io.modsync.server.ServerMain --mods ./mods --port 25566
 * </pre>
 */
public final class ServerMain {

    private static final String TAG = "[modsync-server]";

    private ServerMain() {
    }

    public static void main(String[] args) {
        Integer code = run(args);
        if (code != null) {
            System.exit(code);
        }
    }

    /**
     * Runs the server.
     *
     * @return the process exit code, or {@code null} when the JVM should be left to exit on its own
     *     (server mode, after an orderly shutdown)
     */
    static Integer run(String[] argv) {
        Cli cli;
        try {
            cli = parse(argv);
        } catch (UsageException e) {
            System.err.println(TAG + " error: " + e.getMessage());
            System.err.println(TAG + " run with --help for usage");
            return 2;
        }
        if (cli.help) {
            printUsage(System.out);
            return 0;
        }

        List<Path> dirs = new ArrayList<>();
        dirs.add(cli.mods);
        dirs.addAll(cli.extras);

        List<String> excludes = new ArrayList<>(cli.excludes);
        String self = selfJarName();
        if (self != null && !excludes.contains(self)) {
            excludes.add(self);
            // In --once mode stdout carries the manifest, so diagnostics go to stderr.
            (cli.once ? System.err : System.out).println(TAG + " not publishing our own jar: " + self);
        }

        SyncServer.Options options = new SyncServer.Options(
                dirs,
                excludes,
                cli.port,
                cli.bind,
                cli.baseUrl,
                cli.packName,
                cli.serverId,
                cli.state,
                cli.modrinthLookup,
                cli.once,
                cli.filterServerOnly,
                cli.forcePublish,
                cli.minecraftVersion,
                cli.loaderType,
                cli.loaderVersion,
                cli.modFiles);
        SyncServer server = new SyncServer(options);

        try {
            server.generate();
            if (cli.writeManifest != null) {
                Path target = cli.writeManifest.toAbsolutePath();
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, server.manifestJson() + "\n", StandardCharsets.UTF_8);
            }
            if (cli.once) {
                System.out.println(server.manifestJson());
                System.out.flush();
                return 0;
            }
            server.start();
        } catch (IOException e) {
            System.err.println(TAG + " error: " + e.getMessage());
            return 1;
        }

        System.out.println(TAG + " published " + server.fileCount() + " files, "
                + String.format(Locale.ROOT, "%.1f", server.totalBytes() / (1024.0 * 1024.0)) + " MB"
                + ", pack=" + server.packName()
                + ", id=" + server.serverId()
                + ", listening on http://" + cli.bind + ":" + cli.port + SyncServer.PREFIX);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "modsync-shutdown"));
        try {
            server.awaitTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ------------------------------------------------------------------- CLI

    /** File name of the jar this process runs from, so the publisher never ships itself. */
    private static String selfJarName() {
        try {
            java.security.CodeSource source = ServerMain.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = java.nio.file.Paths.get(source.getLocation().toURI());
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                return path.getFileName().toString();
            }
        } catch (Throwable ignored) {
            // Started from a classpath directory: nothing to exclude.
        }
        return null;
    }

    private static final class Cli {
        Path mods = Path.of("./mods");
        final List<Path> extras = new ArrayList<>();
        final List<Path> modFiles = new ArrayList<>();
        final List<String> excludes = new ArrayList<>();
        int port = 25590;
        String bind = "0.0.0.0";
        String baseUrl;
        String packName;
        String serverId;
        String minecraftVersion;
        String loaderType = "neoforge";
        String loaderVersion;
        Path state = Path.of("./modsync-server.json");
        boolean modrinthLookup;
        boolean once;
        boolean filterServerOnly = true;
        final List<String> forcePublish = new ArrayList<>();
        Path writeManifest;
        boolean help;
    }

    private static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    static Cli parse(String[] argv) {
        Cli cli = new Cli();
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (token.equals("--help") || token.equals("-h")) {
                cli.help = true;
                continue;
            }
            if (!token.startsWith("--")) {
                throw new UsageException("unexpected argument '" + token + "'");
            }
            String body = token.substring(2);
            String key;
            String inline = null;
            int eq = body.indexOf('=');
            if (eq >= 0) {
                key = body.substring(0, eq);
                inline = body.substring(eq + 1);
            } else {
                key = body;
            }
            switch (key) {
                case "mods" -> cli.mods = Path.of(value(argv, i, key, inline));
                case "extra" -> cli.extras.add(Path.of(value(argv, i, key, inline)));
                case "mod-file" -> cli.modFiles.add(Path.of(value(argv, i, key, inline)));
                case "exclude" -> cli.excludes.add(value(argv, i, key, inline));
                case "port" -> cli.port = parsePort(value(argv, i, key, inline));
                case "bind" -> cli.bind = value(argv, i, key, inline);
                case "base-url" -> cli.baseUrl = value(argv, i, key, inline);
                case "pack-name" -> cli.packName = value(argv, i, key, inline);
                case "server-id" -> cli.serverId = value(argv, i, key, inline);
                case "minecraft-version" -> cli.minecraftVersion = value(argv, i, key, inline);
                case "loader-type" -> cli.loaderType = value(argv, i, key, inline);
                case "loader-version" -> cli.loaderVersion = value(argv, i, key, inline);
                case "state" -> cli.state = Path.of(value(argv, i, key, inline));
                case "modrinth-lookup" -> cli.modrinthLookup = flag(inline);
                case "no-filter-server-only" -> cli.filterServerOnly = false;
                case "force-publish" -> cli.forcePublish.add(value(argv, i, key, inline));
                case "once" -> cli.once = flag(inline);
                case "write-manifest" -> cli.writeManifest = Path.of(value(argv, i, key, inline));
                default -> throw new UsageException("unknown option '--" + key + "'");
            }
            if (inline == null && takesValue(key)) {
                i++;
            }
        }
        return cli;
    }

    private static boolean takesValue(String key) {
        return switch (key) {
            case "mods", "extra", "mod-file", "exclude", "port", "bind", "base-url", "pack-name", "server-id",
                    "state", "write-manifest", "minecraft-version", "loader-type", "loader-version",
                    "force-publish" -> true;
            default -> false;
        };
    }

    private static String value(String[] argv, int index, String key, String inline) {
        if (inline != null) {
            return inline;
        }
        if (index + 1 >= argv.length || argv[index + 1] == null || argv[index + 1].startsWith("--")) {
            throw new UsageException("missing value for --" + key);
        }
        return argv[index + 1];
    }

    private static boolean flag(String inline) {
        if (inline == null || inline.isEmpty()) {
            return true;
        }
        String v = inline.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new UsageException("invalid boolean value '" + inline + "'");
        };
    }

    private static int parsePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new UsageException("invalid --port value '" + raw + "'");
        }
        if (port < 1 || port > 65535) {
            throw new UsageException("--port must be between 1 and 65535, got " + port);
        }
        return port;
    }

    static void printUsage(PrintStream out) {
        out.println("ModSync server - publish server mods as a manifest + hash-addressed downloads");
        out.println();
        out.println("Usage: java -jar modsync-server.jar [options]");
        out.println();
        out.println("  --mods <dir>             primary published directory (default: ./mods)");
        out.println("  --extra <dir>            additional published directory, repeatable");
        out.println("  --mod-file <jar>         publish this exact jar, repeatable (takes priority)");
        out.println("  --exclude <glob>         skip file names matching a * and ? glob, repeatable");
        out.println("  --port <int>             listen port (default: 25590)");
        out.println("  --bind <addr>            listen address (default: 0.0.0.0)");
        out.println("  --base-url <url>         download prefix; default from the request Host header");
        out.println("  --pack-name <name>       display name (default: host name or \"Minecraft Server\")");
        out.println("  --server-id <id>         stable server id (default: persisted random 16 hex chars)");
        out.println("  --minecraft-version <v>  Minecraft version announced to clients (e.g. 1.21.1)");
        out.println("  --loader-type <name>     loader name announced to clients (default: neoforge)");
        out.println("  --loader-version <v>     loader version announced to clients (e.g. 21.1.248)");
        out.println("  --state <file>           state file (default: ./modsync-server.json)");
        out.println("  --modrinth-lookup        best-effort sha1 lookup to fill files[].url");
        out.println("  --no-filter-server-only  publish server-only mods too (default: they are skipped)");
        out.println("  --force-publish <glob>   always publish matching file names, repeatable");
        out.println("  --once                   print the manifest to stdout and exit");
        out.println("  --write-manifest <file>  also write the generated manifest to <file>");
        out.println("  --help                   print this help");
        out.println();
        out.println("HTTP: GET/HEAD " + SyncServer.PREFIX + "/manifest.json, "
                + SyncServer.PREFIX + "/files/{sha256}, " + SyncServer.PREFIX + "/health");
    }
}
