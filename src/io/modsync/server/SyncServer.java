package io.modsync.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.modsync.common.Json;
import io.modsync.common.ModEnv;
import io.modsync.common.ModMeta;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Standalone ModSync publishing server.
 *
 * <p>Scans one or more mod directories once at startup, turns them into a manifest (schema 1 of
 * {@code docs/protocol.md}) and serves the manifest plus content-addressed file downloads over a
 * single HTTP port below {@value #PREFIX}. No third-party dependencies: only the JDK HTTP server,
 * zip/hash helpers from {@code io.modsync.common} and {@code java.net.http} for the optional
 * Modrinth lookup.
 *
 * <p>The published set never changes while the server runs; there is no hot reload.
 */
public final class SyncServer implements AutoCloseable {

    /** Path prefix every route lives under. */
    public static final String PREFIX = "/modsync";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int HTTP_THREADS = 8;
    private static final int MAX_CACHED_VIEWS = 64;
    private static final String MODRINTH_VERSION_FILE =
            "https://api.modrinth.com/v2/version_file/";
    private static final String MODRINTH_PROJECT = "https://api.modrinth.com/v2/project/";

    /** Optional Modrinth result: a CDN url and, when server-only filtering is on, a side verdict. */
    private record ModrinthInfo(String url, boolean serverOnly) {}

    /** Immutable startup configuration; {@link ServerMain} maps the CLI onto it. */
    public record Options(
            List<Path> modDirs,
            List<String> excludes,
            int port,
            String bind,
            String baseUrl,
            String packName,
            String serverId,
            Path stateFile,
            boolean modrinthLookup,
            boolean once,
            boolean filterServerOnly,
            List<String> forcePublish,
            String mcVersion,
            String loaderType,
            String loaderVersion) {}

    /** One published jar. {@code path} is always {@code mods/<file name>}. */
    private record FileEntry(String path, String sha256, long size, String modId, String url, Path source) {}

    private final Options opt;
    private final List<Pattern> excludePatterns = new ArrayList<>();
    private final List<Pattern> forcePatterns = new ArrayList<>();
    /** Modrinth project id -> "server only" verdict, so the extra API call happens once per project. */
    private final Map<String, Boolean> projectServerOnly = new ConcurrentHashMap<>();
    /** Rendered bodies keyed by base url: {body, quoted etag}. */
    private final Map<String, String[]> viewCache = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final java.util.concurrent.atomic.AtomicBoolean modrinthWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    private volatile List<FileEntry> entries = List.of();
    private volatile Map<String, FileEntry> byHash = Map.of();
    private volatile String generatedAt = TIMESTAMP.format(Instant.now());
    private volatile long totalBytes;
    private volatile boolean generated;
    private volatile String packName;
    private volatile String serverId;

    private volatile HttpServer http;
    private volatile ExecutorService pool;

    public SyncServer(Options options) {
        this.opt = Objects.requireNonNull(options, "options");
        List<String> excludes = options.excludes() == null ? List.of() : options.excludes();
        for (String glob : excludes) {
            if (glob != null && !glob.isBlank()) {
                excludePatterns.add(globPattern(glob));
            }
        }
        List<String> force = options.forcePublish() == null ? List.of() : options.forcePublish();
        for (String glob : force) {
            if (glob != null && !glob.isBlank()) {
                forcePatterns.add(globPattern(glob));
            }
        }
        loadState();
    }

    // ------------------------------------------------------------------ state

    /** Loads {@code serverId}/{@code packName} from the state file, generating the id once. */
    private void loadState() {
        String id = blankToNull(opt.serverId());
        String name = blankToNull(opt.packName());
        Path stateFile = opt.stateFile();
        if (stateFile != null && Files.isRegularFile(stateFile)) {
            try {
                Map<String, Object> state = Json.asObject(Json.parse(Files.readString(stateFile, StandardCharsets.UTF_8)));
                if (state != null) {
                    if (id == null) {
                        id = blankToNull(Json.str(state, "serverId", null));
                    }
                    if (name == null) {
                        name = blankToNull(Json.str(state, "packName", null));
                    }
                }
            } catch (Exception e) {
                diag("[modsync-server] WARN cannot read state file " + stateFile + ": " + e);
            }
        }
        if (id == null) {
            id = randomServerId();
        }
        if (name == null) {
            name = hostName();
        }
        this.serverId = id;
        this.packName = name;
        if (stateFile != null) {
            try {
                Map<String, Object> out = Json.map();
                out.put("schema", 1L);
                out.put("serverId", serverId);
                out.put("packName", packName);
                Path abs = stateFile.toAbsolutePath();
                Path parent = abs.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(abs, Json.write(out) + "\n", StandardCharsets.UTF_8);
            } catch (Exception e) {
                diag("[modsync-server] WARN cannot write state file " + stateFile + ": " + e);
            }
        }
    }

    private static String randomServerId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return ModMeta.hex(bytes);
    }

    // --------------------------------------------------------------- scanning

    /**
     * Scans the configured directories, computes hashes/metadata and renders the manifest.
     *
     * <p>Only flat {@code *.jar} files directly inside a directory are published; each one becomes
     * {@code mods/<file name>} in the manifest. Duplicate file names keep the first directory's
     * copy (primary directory first).
     */
    public void generate() throws IOException {
        List<FileEntry> found = new ArrayList<>();
        Map<String, Path> seenByPath = new LinkedHashMap<>();
        HttpClient client = opt.modrinthLookup() ? newHttpClient() : null;
        long total = 0;
        List<Path> dirs = opt.modDirs() == null ? List.of() : opt.modDirs();
        for (Path dir : dirs) {
            if (dir == null) {
                continue;
            }
            if (!Files.isDirectory(dir)) {
                diag("[modsync-server] WARN mods directory not found: " + dir);
                continue;
            }
            List<Path> candidates = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile).forEach(candidates::add);
            }
            candidates.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path file : candidates) {
                String name = file.getFileName().toString();
                if (!isPublishable(name) || matchesExclude(name)) {
                    continue;
                }
                String manifestPath = "mods/" + name;
                Path previous = seenByPath.putIfAbsent(manifestPath, file);
                if (previous != null) {
                    diag("[modsync-server] WARN duplicate file name '" + name + "', keeping " + previous);
                    continue;
                }
                ModrinthInfo modrinth = client == null ? null : modrinthLookup(client, file);
                if (opt.filterServerOnly() && !matchesAny(forcePatterns, name)) {
                    ModEnv.Env env = ModEnv.detect(file);
                    boolean serverOnly = env == ModEnv.Env.SERVER_ONLY
                            || (env == ModEnv.Env.UNKNOWN && modrinth != null && modrinth.serverOnly());
                    if (serverOnly) {
                        diag("[modsync-server] skipping server-only mod (clients do not need it): " + name
                                + (env == ModEnv.Env.SERVER_ONLY
                                        ? " [from mod metadata]"
                                        : " [from Modrinth metadata]")
                                + " (use --force-publish '" + name + "' to publish it anyway)");
                        seenByPath.remove(manifestPath);
                        continue;
                    }
                }
                try {
                    String sha = ModMeta.sha256(file);
                    long size = Files.size(file);
                    String modId = ModMeta.readModId(file);
                    found.add(new FileEntry(manifestPath, sha, size, modId,
                            modrinth == null ? null : modrinth.url(),
                            file.toAbsolutePath().normalize()));
                    total += size;
                } catch (IOException e) {
                    seenByPath.remove(manifestPath);
                    diag("[modsync-server] WARN skipping " + file + ": " + e);
                }
            }
        }
        found.sort(Comparator.comparing(FileEntry::path));

        Map<String, FileEntry> hashes = new HashMap<>();
        for (FileEntry entry : found) {
            hashes.putIfAbsent(entry.sha256(), entry);
        }
        warnDuplicateModIds(found);

        this.entries = List.copyOf(found);
        this.byHash = Map.copyOf(hashes);
        this.totalBytes = total;
        this.generatedAt = TIMESTAMP.format(Instant.now());
        this.generated = true;
        this.viewCache.clear();
    }

    private void warnDuplicateModIds(List<FileEntry> found) {
        Map<String, List<String>> byModId = new LinkedHashMap<>();
        for (FileEntry entry : found) {
            if (entry.modId() != null && !entry.modId().isBlank()) {
                byModId.computeIfAbsent(entry.modId(), k -> new ArrayList<>()).add(entry.path());
            }
        }
        for (Map.Entry<String, List<String>> e : byModId.entrySet()) {
            if (e.getValue().size() > 1) {
                diag("[modsync-server] WARN duplicate modId '" + e.getKey() + "' published by "
                        + String.join(", ", e.getValue()));
            }
        }
    }

    private static boolean isPublishable(String name) {
        if (name.isEmpty() || name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar")) {
            return false;
        }
        return !(lower.endsWith(".part") || lower.endsWith(".tmp") || lower.endsWith(".disabled"));
    }

    private boolean matchesExclude(String name) {
        return matchesAny(excludePatterns, name);
    }

    private static boolean matchesAny(List<Pattern> patterns, String name) {
        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Converts the tiny {@code *}/{@code ?} glob syntax into an anchored regex. */
    static Pattern globPattern(String glob) {
        StringBuilder re = new StringBuilder("(?s)^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        re.append('$');
        return Pattern.compile(re.toString());
    }

    // ------------------------------------------------------------ modrinth

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                // Generous: the first connect can need an IPv6->IPv4 fallback (DNS + TLS) on some hosts.
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Best-effort Modrinth lookup; every failure silently yields {@code null}. */
    private ModrinthInfo modrinthLookup(HttpClient client, Path file) {
        try {
            String sha1 = ModMeta.sha1(file);
            URI uri = URI.create(MODRINTH_VERSION_FILE + sha1 + "?algorithm=sha1");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "modsync-server/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Map<String, Object> version = Json.asObject(Json.parse(response.body()));
            if (version == null) {
                return null;
            }
            List<Object> files = Json.asArray(version.get("files"));
            if (files == null) {
                return null;
            }
            String sha256 = ModMeta.sha256(file);
            String url = null;
            for (Object raw : files) {
                Map<String, Object> candidate = Json.asObject(raw);
                if (candidate == null) {
                    continue;
                }
                String candidateUrl = Json.str(candidate, "url", null);
                if (candidateUrl == null || candidateUrl.isBlank()) {
                    continue;
                }
                Map<String, Object> hashes = Json.asObject(candidate.get("hashes"));
                if (hashes != null && sha256.equalsIgnoreCase(Json.str(hashes, "sha256", null))) {
                    url = candidateUrl;
                    break;
                }
                if (url == null) {
                    url = candidateUrl;
                }
            }
            boolean serverOnly = false;
            if (url != null && opt.filterServerOnly()) {
                String projectId = Json.str(version, "project_id", null);
                if (projectId != null && !projectId.isBlank()) {
                    serverOnly = projectServerOnly.computeIfAbsent(projectId,
                            id -> lookupServerOnlyProject(client, id));
                }
            }
            return new ModrinthInfo(url, serverOnly);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // Broken network or an unparseable response: keep publishing, just without direct urls.
            // Warn once so operators can tell a lookup outage from "these files are not on Modrinth".
            if (modrinthWarned.compareAndSet(false, true)) {
                diag("[modsync-server] WARN Modrinth lookup unavailable, clients will download from this server: " + e);
            }
            return null;
        }
    }

    /** Modrinth project metadata: a mod that needs the server and does not support clients. */
    private boolean lookupServerOnlyProject(HttpClient client, String projectId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(MODRINTH_PROJECT + projectId))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "modsync-server/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            Map<String, Object> project = Json.asObject(Json.parse(response.body()));
            if (project == null) {
                return false;
            }
            return "required".equalsIgnoreCase(Json.str(project, "server_side", ""))
                    && "unsupported".equalsIgnoreCase(Json.str(project, "client_side", ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ manifest

    /** Manifest body using the configured (or default) base url; used by {@code --once}. */
    public String manifestJson() {
        return manifestView(effectiveBaseUrl())[0];
    }

    public String packName() {
        return packName;
    }

    public String serverId() {
        return serverId;
    }

    public int fileCount() {
        return entries.size();
    }

    public long totalBytes() {
        return totalBytes;
    }

    private String[] manifestView(String baseUrl) {
        String[] cached = viewCache.get(baseUrl);
        if (cached != null) {
            return cached;
        }
        String body = renderManifest(baseUrl);
        String etag = "\"" + sha256Hex(body.getBytes(StandardCharsets.UTF_8)) + "\"";
        String[] view = {body, etag};
        if (viewCache.size() < MAX_CACHED_VIEWS) {
            viewCache.put(baseUrl, view);
        }
        return view;
    }

    private String renderManifest(String baseUrl) {
        if (!generated) {
            throw new IllegalStateException("generate() has not run yet");
        }
        Map<String, Object> root = Json.map();
        root.put("schema", 1L);
        root.put("packName", packName);
        root.put("serverId", serverId);
        root.put("generatedAt", generatedAt);
        root.put("baseUrl", baseUrl);
        String mc = blankToNull(opt.mcVersion());
        if (mc != null) {
            root.put("minecraft", mc);
        }
        String loaderVersion = blankToNull(opt.loaderVersion());
        if (loaderVersion != null) {
            Map<String, Object> loader = Json.map();
            String type = blankToNull(opt.loaderType());
            loader.put("type", type == null ? "neoforge" : type);
            loader.put("version", loaderVersion);
            root.put("loader", loader);
        }
        List<Object> files = Json.list();
        for (FileEntry entry : entries) {
            Map<String, Object> item = Json.map();
            item.put("path", entry.path());
            item.put("sha256", entry.sha256());
            item.put("size", entry.size());
            if (entry.modId() != null) {
                item.put("modId", entry.modId());
            }
            if (entry.url() != null) {
                item.put("url", entry.url());
            }
            files.add(item);
        }
        root.put("files", files);
        return Json.write(root);
    }

    /** Base url used when no request context exists ({@code --once}, {@code --write-manifest}). */
    public String effectiveBaseUrl() {
        String fixed = blankToNull(opt.baseUrl());
        if (fixed != null) {
            return trimTrailingSlash(fixed);
        }
        return "http://" + defaultHostPort() + PREFIX;
    }

    private String dynamicBaseUrl(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            host = defaultHostPort();
        } else if (host.indexOf(':') < 0) {
            host = host + ":" + opt.port();
        }
        return "http://" + host + PREFIX;
    }

    private String defaultHostPort() {
        String bind = blankToNull(opt.bind());
        if (bind != null && !bind.equals("0.0.0.0") && !bind.equals("::") && !bind.equals("[::]")) {
            return formatHost(bind, opt.port());
        }
        return formatHost(hostName(), opt.port());
    }

    private static String formatHost(String host, int port) {
        String h = host;
        if (h.indexOf(':') >= 0 && !h.startsWith("[")) {
            h = "[" + h + "]";
        }
        return h + ":" + port;
    }

    private static String hostName() {
        try {
            String name = InetAddress.getLocalHost().getHostName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "Minecraft Server";
    }

    private static String trimTrailingSlash(String url) {
        String out = url;
        while (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    // -------------------------------------------------------------- serving

    /** Binds the socket and starts serving; manifest must already be {@link #generate() generated}. */
    public void start() throws IOException {
        if (!generated) {
            generate();
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(opt.bind(), opt.port()), 0);
        ExecutorService executor = Executors.newFixedThreadPool(HTTP_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "modsync-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext(PREFIX, this::handle);
        server.start();
        this.pool = executor;
        this.http = server;
    }

    /** Blocks until {@link #stop(int)} is called; used by {@link ServerMain} to stay alive. */
    public void awaitTermination() throws InterruptedException {
        stopped.await();
    }

    public void stop(int delaySeconds) {
        HttpServer server = http;
        http = null;
        if (server != null) {
            server.stop(Math.max(0, delaySeconds));
        }
        ExecutorService executor = pool;
        pool = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        stopped.countDown();
    }

    @Override
    public void close() {
        stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        int status = 500;
        try {
            status = route(exchange);
        } catch (Exception e) {
            status = 500;
            try {
                sendError(exchange, isHead(exchange), 500, "internal error");
            } catch (Exception ignored) {
                // headers already sent; nothing more we can do
            }
            System.out.println("[modsync-server] ERROR " + exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getPath() + ": " + e);
        } finally {
            try {
                exchange.close();
            } catch (Exception ignored) {
                // ignore
            }
            long millis = (System.nanoTime() - started) / 1_000_000L;
            System.out.println("[modsync-server] " + status + " " + exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getPath() + " " + millis + "ms");
        }
    }

    private int route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean head = isHead(exchange);
        if (!head && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            return sendError(exchange, false, 405, "method not allowed");
        }
        if (path.equals(PREFIX + "/manifest.json")) {
            return manifest(exchange, head);
        }
        if (path.startsWith(PREFIX + "/files/")) {
            return file(exchange, head, path.substring((PREFIX + "/files/").length()));
        }
        if (path.equals(PREFIX + "/health")) {
            return health(exchange, head);
        }
        return sendError(exchange, head, 404, "not found");
    }

    private int manifest(HttpExchange exchange, boolean head) throws IOException {
        String baseUrl = blankToNull(opt.baseUrl());
        String[] view = manifestView(baseUrl != null ? trimTrailingSlash(baseUrl) : dynamicBaseUrl(exchange));
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("ETag", view[1]);
        if (etagMatches(exchange.getRequestHeaders().getFirst("If-None-Match"), view[1])) {
            exchange.sendResponseHeaders(304, -1);
            return 304;
        }
        byte[] body = view[0].getBytes(StandardCharsets.UTF_8);
        sendHeaders(exchange, head, 200, body.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        return 200;
    }

    private int file(HttpExchange exchange, boolean head, String sha256) throws IOException {
        if (!SHA256.matcher(sha256).matches()) {
            return sendError(exchange, head, 400, "invalid sha256");
        }
        FileEntry entry = byHash.get(sha256);
        if (entry == null) {
            return sendError(exchange, head, 404, "not found");
        }
        long size;
        try {
            size = Files.size(entry.source());
        } catch (IOException e) {
            return sendError(exchange, head, 404, "not found");
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/java-archive");
        headers.set("Cache-Control", "public, max-age=31536000, immutable");
        sendHeaders(exchange, head, 200, size);
        if (!head) {
            try (InputStream in = Files.newInputStream(entry.source());
                 OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            }
        }
        return 200;
    }

    private int health(HttpExchange exchange, boolean head) throws IOException {
        String body = "{\"status\":\"ok\",\"files\":" + entries.size()
                + ",\"packName\":" + jsonString(packName) + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        sendHeaders(exchange, head, 200, bytes.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        return 200;
    }

    private int sendError(HttpExchange exchange, boolean head, int code, String message) throws IOException {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        sendHeaders(exchange, head, code, body.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        return code;
    }

    private static boolean isHead(HttpExchange exchange) {
        return "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
    }

    /**
     * Sends the response headers for a fixed-length body.
     *
     * <p>For {@code HEAD} the JDK rejects a positive length (it logs a warning and drops the
     * header), so the length is set explicitly and {@code -1} is passed to mark "no body". GET/HEAD
     * therefore both report the real {@code Content-Length}.
     */
    private static void sendHeaders(HttpExchange exchange, boolean head, int code, long length)
            throws IOException {
        if (head) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(length));
            exchange.sendResponseHeaders(code, -1);
        } else {
            exchange.sendResponseHeaders(code, length);
        }
    }

    /** Accepts {@code "etag"}, {@code W/"etag"}, comma separated lists and {@code *}. */
    static boolean etagMatches(String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        for (String raw : header.split(",")) {
            String candidate = raw.trim();
            if (candidate.equals("*")) {
                return true;
            }
            if (candidate.startsWith("W/") || candidate.startsWith("w/")) {
                candidate = candidate.substring(2);
            }
            if (candidate.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------- helpers

    private static String sha256Hex(byte[] data) {
        try {
            return ModMeta.hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Diagnostics go to stdout, except in {@code --once} mode where stdout is the manifest. */
    private void diag(String line) {
        if (opt.once()) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
    }
}
