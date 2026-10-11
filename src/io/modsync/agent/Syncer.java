package io.modsync.agent;

import io.modsync.common.ModMeta;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The whole pre-launch sync:
 *
 * <ol>
 *   <li>fetch the server manifest (304-aware),</li>
 *   <li>verify {@code mods-server} by sha256 and download only what is missing/changed,</li>
 *   <li>move stale server files to {@code modsync/trash},</li>
 *   <li>migrate player-dropped jars from {@code mods/} into {@code mods-client/},</li>
 *   <li>rebuild {@code mods/} from {@code mods-server} + {@code mods-client}, server winning on
 *       duplicated mod ids.</li>
 * </ol>
 */
public final class Syncer {

    private static final DateTimeFormatter TRASH_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    /** How many trash batches to keep before pruning the oldest. */
    private static final int TRASH_KEEP = 10;

    public static final class Result {
        public boolean manifestFetched;
        public int verified;
        public int downloaded;
        public int removed;
        public int migrated;
        public int conflicts;
        public int deployed;
        public boolean rebuilt;
        /** True when the server announced a different Minecraft or loader version. */
        public boolean versionMismatch;
        /** True when the sync was skipped because of a Minecraft version mismatch. */
        public boolean skippedByVersion;
    }

    public static final class CancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("用户取消同步");
        }
    }

    /** Thrown when the server/client versions differ and the config says to abort the launch. */
    public static final class MismatchAbortException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MismatchAbortException(String message) {
            super(message);
        }
    }

    private record Desired(String sha256, long size, String modId, String url) {
    }

    /** A file that must exist in {@code mods/} after the rebuild, with its verified hash. */
    private record PlanItem(Path src, long size, String sha256, String origin) {
    }

    private final AgentConfig cfg;
    private final Path gameDir;
    private final Progress progress;
    private final PrintStream log;
    private final HttpClient http;

    public Syncer(AgentConfig cfg, Path gameDir, Progress progress, PrintStream log) {
        this.cfg = cfg;
        this.gameDir = gameDir;
        this.progress = progress;
        this.log = log;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(Math.max(5, cfg.timeoutSeconds)))
                .build();
    }

    public Result run() throws Exception {
        Path serverDir = gameDir.resolve(cfg.serverDirName);
        Path clientDir = gameDir.resolve(cfg.clientDirName);
        Path targetDir = gameDir.resolve(cfg.targetDirName);
        Path work = AgentConfig.workDir(gameDir);
        Path stateFile = work.resolve("state.json");
        Files.createDirectories(serverDir);
        Files.createDirectories(clientDir);
        Files.createDirectories(work);

        State state = State.load(stateFile);
        Result result = new Result();

        // ---------------------------------------------------------- 1. manifest
        progress.phase("获取服务器清单");
        Manifest manifest = Manifest.fetch(cfg.manifestUrl, state.etag, cfg.timeoutSeconds);
        Map<String, Desired> desired = new LinkedHashMap<>();
        String base = "";
        if (manifest != null) {
            result.manifestFetched = true;
            if (!state.serverId.isEmpty() && !manifest.serverId.isEmpty() && !manifest.serverId.equals(state.serverId)) {
                progress.info("服务器标识变化 " + state.serverId + " -> " + manifest.serverId + "，清空本地同步记录");
                state.serverFiles.clear();
                state.deployed.clear();
            }
            for (Manifest.Entry e : manifest.files) {
                if (!e.path.startsWith("mods/")) {
                    progress.warn("清单包含暂不支持的路径，已跳过: " + e.path);
                    continue;
                }
                desired.put(e.fileName(), new Desired(e.sha256, e.size, e.modId, e.url));
            }
            if (!manifest.serverId.isEmpty()) {
                state.serverId = manifest.serverId;
            }
            state.manifestHash = manifest.rawHash;
            state.etag = manifest.etag;
            base = manifest.downloadBase();
            progress.info("清单: " + manifest.summary());
        } else {
            progress.info("清单未变化 (HTTP 304)，沿用上次结果");
            for (Map.Entry<String, State.FileInfo> e : state.serverFiles.entrySet()) {
                desired.put(e.getKey(), new Desired(e.getValue().sha256, e.getValue().size, null, null));
            }
        }

        // ---------------------------------------------------------- 1b. version check
        if (manifest != null && checkVersionCompatibility(manifest, result)) {
            progress.info("按配置跳过本次同步（Minecraft 版本不匹配）");
            state.lastSyncAt = now();
            if (!cfg.dryRun) {
                state.save(stateFile);
            }
            return result;
        }

        // ---------------------------------------------------------- 2. verify + download
        List<Map.Entry<String, Desired>> pending = new ArrayList<>();
        long pendingBytes = 0;
        for (Map.Entry<String, Desired> e : desired.entrySet()) {
            if (matches(serverDir.resolve(e.getKey()), e.getValue())) {
                result.verified++;
                continue;
            }
            pending.add(e);
            pendingBytes += Math.max(0, e.getValue().size());
        }
        progress.info("校验 " + cfg.serverDirName + ": 已符合 " + result.verified + " 个"
                + (pending.isEmpty() ? "" : "，需下载 " + pending.size() + " 个 (" + humanBytes(pendingBytes) + ")"));
        if (!pending.isEmpty()) {
            if (cfg.dryRun) {
                for (Map.Entry<String, Desired> e : pending) {
                    progress.info("[dry-run] 将下载 " + e.getKey());
                }
                result.downloaded = pending.size();
            } else {
                progress.phase("下载模组");
                progress.setTotal(pendingBytes, pending.size());
                downloadAll(pending, base, serverDir, work);
                result.downloaded = pending.size();
            }
        }
        if (progress.isCancelled()) {
            throw new CancelledException();
        }

        // ---------------------------------------------------------- 3. remove stale server files
        progress.phase("清理过期文件");
        Set<String> keep = new HashSet<>(desired.keySet());
        for (Path jar : listJars(serverDir)) {
            String name = jar.getFileName().toString();
            if (keep.contains(name)) {
                continue;
            }
            if (cfg.dryRun) {
                progress.info("[dry-run] 将移除 " + cfg.serverDirName + "/" + name);
            } else {
                moveToTrash(jar, work);
                progress.info("移除过期文件 " + cfg.serverDirName + "/" + name + " (已放入 modsync/trash)");
            }
            result.removed++;
        }
        state.serverFiles.clear();
        for (Map.Entry<String, Desired> e : desired.entrySet()) {
            state.serverFiles.put(e.getKey(), new State.FileInfo(e.getValue().sha256(), e.getValue().size(), "server"));
        }

        // ---------------------------------------------------------- 4. rescue player-dropped jars
        progress.phase("对齐 mods 目录");
        Map<String, Path> inTarget = listJarsMap(targetDir);
        if (cfg.autoMigrateUnknownMods && !inTarget.isEmpty()) {
            for (Map.Entry<String, Path> e : inTarget.entrySet()) {
                String name = e.getKey();
                if (state.deployed.containsKey(name)) {
                    continue;
                }
                Path src = e.getValue();
                Path dst = clientDir.resolve(name);
                if (cfg.dryRun) {
                    progress.info("[dry-run] 将迁移 mods/" + name + " -> " + cfg.clientDirName + "/");
                } else if (Files.exists(dst) && sameContent(src, dst)) {
                    moveToTrash(src, work);
                    progress.info("mods/" + name + " 与 " + cfg.clientDirName + " 中的副本一致，已放入 modsync/trash");
                } else {
                    if (Files.exists(dst)) {
                        dst = uniqueName(clientDir, name);
                    }
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    progress.info("迁移玩家自装模组 mods/" + name + " -> " + cfg.clientDirName + "/" + dst.getFileName());
                }
                result.migrated++;
            }
        }

        // ---------------------------------------------------------- 5. plan
        LinkedHashMap<String, PlanItem> plan = new LinkedHashMap<>();
        Map<String, String> modIdCache = new HashMap<>();
        Set<String> serverIds = new HashSet<>();
        for (Path jar : listJars(serverDir)) {
            String name = jar.getFileName().toString();
            Desired d = desired.get(name);
            String sha = (d != null && matches(jar, d)) ? d.sha256() : ModMeta.sha256(jar);
            plan.put(name, new PlanItem(jar, Files.size(jar), sha, "server"));
            String id = modIdOf(jar, modIdCache);
            if (id != null) {
                serverIds.add(id);
            }
        }
        for (Path jar : listJars(clientDir)) {
            String name = jar.getFileName().toString();
            String id = modIdOf(jar, modIdCache);
            if (plan.containsKey(name)) {
                result.conflicts++;
                progress.info("跳过客户端模组 " + name + "（与服务器文件同名，以服务器版本为准）");
                continue;
            }
            if (id != null && serverIds.contains(id)) {
                result.conflicts++;
                progress.info("跳过客户端模组 " + name + "（mod id=" + id + " 与服务器重复，以服务器版本为准）");
                continue;
            }
            plan.put(name, new PlanItem(jar, Files.size(jar), ModMeta.sha256(jar), "client"));
        }
        String fingerprint = fingerprint(state.manifestHash, plan);

        boolean upToDate = targetMatches(targetDir, plan, state) && !cfg.forceRebuild;
        if (upToDate) {
            progress.info(cfg.targetDirName + " 已是最新（" + plan.size() + " 个模组），无需重组");
        } else {
            progress.phase("重组 " + cfg.targetDirName + " 目录");
            if (cfg.dryRun) {
                progress.info("[dry-run] 将重组 " + plan.size() + " 个模组到 " + cfg.targetDirName + "/");
            } else {
                rebuild(targetDir, plan, work, state);
                result.deployed = plan.size();
                state.lastBuildAt = now();
                progress.info("已重组 " + cfg.targetDirName + "/: 服务器 " + serverIds.size() + " 个 id, 共部署 " + plan.size() + " 个 jar"
                        + (result.conflicts > 0 ? "，跳过低优先级重复 " + result.conflicts + " 个" : ""));
            }
            result.rebuilt = true;
        }

        state.lastSyncAt = now();
        state.lastFingerprint = fingerprint;
        if (!cfg.dryRun) {
            state.save(stateFile);
        }

        progress.info("完成: 校验 " + result.verified + "，下载 " + result.downloaded + "，移除 " + result.removed
                + "，迁移 " + result.migrated + "，冲突跳过 " + result.conflicts
                + (result.rebuilt ? "，已重组" : "，未重组"));
        return result;
    }

    // ------------------------------------------------------------------ version check

    /**
     * Compares the server's announced Minecraft/loader versions with the local instance.
     *
     * @return {@code true} when the sync must be skipped (Minecraft mismatch with policy {@code skip})
     * @throws MismatchAbortException when the configured policy is {@code abort}
     */
    private boolean checkVersionCompatibility(Manifest manifest, Result result) {
        String serverMc = manifest.minecraft == null ? "" : manifest.minecraft.trim();
        String serverLoader = manifest.loaderVersion == null ? "" : manifest.loaderVersion.trim();
        if (serverMc.isEmpty() && serverLoader.isEmpty()) {
            return false;
        }
        ClientVersion client = ClientVersion.detect(gameDir, cfg);
        String serverType = manifest.loaderType == null || manifest.loaderType.isBlank()
                ? "neoforge" : manifest.loaderType.trim();
        String clientType = client.loaderType == null || client.loaderType.isBlank() ? serverType : client.loaderType.trim();

        if (!serverMc.isEmpty() && !client.minecraft.isEmpty() && !serverMc.equals(client.minecraft)) {
            result.versionMismatch = true;
            String head = "Minecraft 版本不一致：服务器 " + serverMc + "，本地 " + client.minecraft;
            if ("abort".equalsIgnoreCase(cfg.minecraftMismatch)) {
                progress.banner(head + "\n按配置中止启动。请在启动器里把该实例换成 Minecraft " + serverMc
                        + " 再试。（本地版本读取自: " + client.source + "）");
                throw new MismatchAbortException(head);
            }
            if (!"warn".equalsIgnoreCase(cfg.minecraftMismatch)) {
                progress.banner(head + "\n跨版本的模组会导致崩溃，因此本次已跳过模组同步。\n"
                        + "请在启动器里把该实例换成 Minecraft " + serverMc + " 再启动。\n"
                        + "（本地版本读取自: " + client.source + "；想改成仅提醒请把 config.json 的 "
                        + "\"minecraftMismatch\" 设为 \"warn\"）");
                return true;
            }
            progress.banner(head + "\n按配置仅提醒，继续同步（本地版本读取自: " + client.source + "）");
        }

        if (!serverLoader.isEmpty() && !client.loaderVersion.isEmpty() && !serverLoader.equals(client.loaderVersion)) {
            result.versionMismatch = true;
            String head = "模组加载器版本不一致：服务器 " + serverType + " " + serverLoader
                    + "，本地 " + clientType + " " + client.loaderVersion;
            String hint = "请通过启动器把 " + serverType + " 升到 " + serverLoader + " 再启动：\n"
                    + "  Prism / MultiMC：实例 → Edit → Version → 把 " + serverType + " 改成 " + serverLoader + "\n"
                    + "  PCL2：版本设置 → 设置 → 游戏版本 → 选择 " + serverType + " " + serverLoader + "\n"
                    + "（本地版本读取自: " + client.source + "；同一个 21.1.x 内通常可兼容，但建议对齐）";
            if ("abort".equalsIgnoreCase(cfg.loaderMismatch)) {
                progress.banner(head + "\n" + hint + "\n按配置中止启动。");
                throw new MismatchAbortException(head);
            }
            if (!"ignore".equalsIgnoreCase(cfg.loaderMismatch)) {
                progress.banner(head + "\n" + hint);
                progress.holdWarning("ModSync：请升级 " + serverType, head + "\n\n" + hint, cfg.warnHoldSeconds);
            }
        } else if (!serverLoader.isEmpty() && client.loaderVersion.isEmpty() && cfg.verbose) {
            progress.info("无法确定本地 " + serverType + " 版本，已跳过版本检查（来源: " + client.source + "）");
        }
        return false;
    }

    // ------------------------------------------------------------------ downloads

    private void downloadAll(List<Map.Entry<String, Desired>> pending, String base, Path serverDir, Path work)
            throws Exception {
        Path tmpDir = work.resolve("tmp");
        Files.createDirectories(tmpDir);
        int threads = Math.max(1, Math.min(cfg.parallelDownloads, pending.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "modsync-download");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger failures = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Map.Entry<String, Desired> entry : pending) {
                futures.add(pool.submit(() -> {
                    if (progress.isCancelled()) {
                        return null;
                    }
                    try {
                        downloadOne(entry.getKey(), entry.getValue(), base, serverDir, tmpDir);
                    } catch (CancelledException ce) {
                        throw ce;
                    } catch (Exception ex) {
                        failures.incrementAndGet();
                        progress.warn("下载失败 " + entry.getKey() + ": " + ex.getMessage());
                        throw new CompletionException(ex);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ignored) {
                    // already reported above
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (progress.isCancelled()) {
            throw new CancelledException();
        }
        if (failures.get() > 0) {
            throw new IOException(failures.get() + " 个文件下载失败");
        }
    }

    private void downloadOne(String name, Desired desired, String base, Path serverDir, Path tmpDir) throws Exception {
        progress.fileStart(name, desired.size());
        Path tmp = tmpDir.resolve("dl-" + desired.sha256() + ".part");
        if (!matches(tmp, desired)) {
            String url;
            if (desired.url() != null && !desired.url().isBlank()) {
                url = desired.url();
            } else if (base != null && !base.isBlank()) {
                url = base + "/files/" + desired.sha256();
            } else {
                throw new IOException("清单未提供 url 且 baseUrl 为空，无法定位下载地址");
            }
            Files.deleteIfExists(tmp);
            downloadTo(url, tmp);
        }
        if (!matches(tmp, desired)) {
            Files.deleteIfExists(tmp);
            throw new IOException("sha256 校验失败");
        }
        Path dst = serverDir.resolve(name);
        try {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        progress.fileDone(name);
    }

    private void downloadTo(String url, Path dest) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30, cfg.timeoutSeconds * 10L)))
                .header("User-Agent", "modsync-agent/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("HTTP " + resp.statusCode() + " " + url);
        }
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                progress.addBytes(n);
                if (progress.isCancelled()) {
                    throw new CancelledException();
                }
            }
        }
    }

    // ------------------------------------------------------------------ rebuild

    private void rebuild(Path targetDir, Map<String, PlanItem> plan, Path work, State state) throws IOException {
        Files.createDirectories(targetDir);
        Map<String, Path> present = listJarsMap(targetDir);
        Map<String, State.FileInfo> deployed = new LinkedHashMap<>();
        for (Map.Entry<String, PlanItem> e : plan.entrySet()) {
            String name = e.getKey();
            PlanItem item = e.getValue();
            Path existing = present.get(name);
            if (existing != null && sameContent(existing, item)) {
                // Already exactly right: keep the file in place (no copy, no trash churn).
                deployed.put(name, new State.FileInfo(item.sha256(), item.size(), item.origin(),
                        Files.getLastModifiedTime(existing).toMillis()));
                continue;
            }
            if (existing != null) {
                moveToTrash(existing, work);
            }
            Path dst = targetDir.resolve(name);
            Files.copy(item.src(), dst, StandardCopyOption.REPLACE_EXISTING);
            deployed.put(name, new State.FileInfo(item.sha256(), item.size(), item.origin(),
                    Files.getLastModifiedTime(dst).toMillis()));
        }
        // Leftovers that the agent itself deployed and that are no longer wanted.
        for (Map.Entry<String, Path> e : present.entrySet()) {
            if (!plan.containsKey(e.getKey()) && state.deployed.containsKey(e.getKey())) {
                moveToTrash(e.getValue(), work);
            }
        }
        state.deployed.clear();
        state.deployed.putAll(deployed);
        pruneTrash(work);
    }

    /** True when the on-disk file already matches the planned content. */
    private static boolean sameContent(Path file, PlanItem item) {
        try {
            return Files.size(file) == item.size() && ModMeta.sha256(file).equalsIgnoreCase(item.sha256());
        } catch (IOException e) {
            return false;
        }
    }

    /** Keeps the newest {@link #TRASH_KEEP} trash batches so the folder cannot grow forever. */
    private static void pruneTrash(Path work) {
        Path trash = work.resolve("trash");
        if (!Files.isDirectory(trash)) {
            return;
        }
        try (Stream<Path> stream = Files.list(trash)) {
            List<Path> batches = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = TRASH_KEEP; i < batches.size(); i++) {
                deleteRecursively(batches.get(i));
            }
        } catch (IOException ignored) {
            // Trash pruning is best-effort; never fail a sync over it.
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private boolean targetMatches(Path targetDir, Map<String, PlanItem> plan, State state) {
        if (!Files.isDirectory(targetDir)) {
            return false;
        }
        Map<String, Path> have = listJarsMap(targetDir);
        for (Map.Entry<String, PlanItem> e : plan.entrySet()) {
            String name = e.getKey();
            PlanItem item = e.getValue();
            Path p = have.get(name);
            if (p == null) {
                return false;
            }
            try {
                if (Files.size(p) != item.size()) {
                    return false;
                }
                long mtime = Files.getLastModifiedTime(p).toMillis();
                State.FileInfo deployed = state.deployed.get(name);
                if (deployed != null && deployed.sha256.equalsIgnoreCase(item.sha256()) && deployed.mtime == mtime) {
                    continue; // cheap path: unchanged since we deployed it
                }
                String actual = ModMeta.sha256(p);
                if (!actual.equalsIgnoreCase(item.sha256())) {
                    return false;
                }
                state.deployed.put(name, new State.FileInfo(actual, Files.size(p),
                        deployed == null ? "" : deployed.origin, mtime));
            } catch (IOException ex) {
                return false;
            }
        }
        for (String name : have.keySet()) {
            if (!plan.containsKey(name) && state.deployed.containsKey(name)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ utilities

    public static String humanBytes(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private static boolean matches(Path file, Desired desired) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            if (desired.size() >= 0 && Files.size(file) != desired.size()) {
                return false;
            }
            return ModMeta.sha256(file).equalsIgnoreCase(desired.sha256());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean sameContent(Path a, Path b) {
        try {
            return Files.size(a) == Files.size(b) && ModMeta.sha256(a).equals(ModMeta.sha256(b));
        } catch (IOException e) {
            return false;
        }
    }

    private String modIdOf(Path jar, Map<String, String> cache) {
        String key = jar.toString();
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        String id = ModMeta.readModId(jar);
        if (id != null) {
            id = id.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                id = null;
            }
        }
        cache.put(key, id);
        return id;
    }

    private static List<Path> listJars(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(Syncer::isModJar)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Map<String, Path> listJarsMap(Path dir) {
        Map<String, Path> out = new LinkedHashMap<>();
        for (Path p : listJars(dir)) {
            out.put(p.getFileName().toString(), p);
        }
        return out;
    }

    private static boolean isModJar(Path p) {
        String name = p.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        if (name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.endsWith(".disabled") && !lower.endsWith(".part") && !lower.endsWith(".tmp");
    }

    static void moveToTrash(Path file, Path work) throws IOException {
        Path dir = work.resolve("trash").resolve(TRASH_STAMP.format(Instant.now()));
        Files.createDirectories(dir);
        Path dst = dir.resolve(file.getFileName().toString());
        int i = 1;
        while (Files.exists(dst)) {
            dst = dir.resolve(file.getFileName().toString() + "." + i++);
        }
        Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path uniqueName(Path dir, String name) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            Path candidate = dir.resolve(base + "-modsync" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return dir.resolve(base + "-modsync-" + System.currentTimeMillis() + ext);
    }

    private static String fingerprint(String manifestHash, Map<String, PlanItem> plan) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((manifestHash == null ? "" : manifestHash).getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, PlanItem> e : plan.entrySet()) {
                md.update((e.getKey() + ":" + e.getValue().size() + ":" + e.getValue().sha256() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return ModMeta.hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
}
