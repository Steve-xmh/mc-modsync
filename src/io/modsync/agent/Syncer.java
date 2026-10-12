package io.modsync.agent;

import io.modsync.common.Json;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 * 增量对账式同步（v1.2.0，规格见 {@code docs/design-reconcile.md}）。
 *
 * <p>{@code mods/} 是玩家真实生效目录，{@code mods-server/} 是服务端镜像（账本），清单是权威内容。
 * 一次同步固定按下面的顺序走：
 *
 * <ol>
 *   <li>拉取清单（304 感知），判定清单是否有效、是否允许删除；</li>
 *   <li>计划：diff（清单 ↔ 账本）+ {@code mods/} 补齐扫描 + 玩家碰撞扫描；</li>
 *   <li>下载全部需要的新文件到 {@code modsync/tmp/} 并逐个校验 sha；</li>
 *   <li>REMOVE（先从 {@code mods/} 与账本里删，避免出现两个同 modId 的 jar）；</li>
 *   <li>RENAME / EVICT（旧版进 trash，玩家版进 archive）；</li>
 *   <li>ADD / UPDATE / DEPLOY（tmp → 原子 move 进 {@code mods/}，随后更新账本）；</li>
 *   <li>写 last-manifest / history / state / removal-log，校验扫描并汇总。</li>
 * </ol>
 *
 * <p>不变量：删除只能由账本差集驱动；{@code mods/} 里不在账本、modId 也不冲突的文件永不触碰；
 * 服务器模组不进 archive；清单不可信时本轮完全不动。
 */
public final class Syncer {

    /** 时间戳目录名（trash 批次）。 */
    private static final DateTimeFormatter BATCH_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    /** 历史清单文件名（毫秒精度，保证同一秒内多次同步不互相覆盖）。 */
    private static final DateTimeFormatter HISTORY_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    /** How many trash batches to keep before pruning the oldest. */
    private static final int TRASH_KEEP = 10;
    /** How many manifest history snapshots to keep. */
    private static final int HISTORY_KEEP = 5;

    private static final String LAST_MANIFEST = "last-manifest.json";
    private static final String HISTORY_DIR = "manifest-history";
    private static final String ARCHIVE_DIR = "archive";
    private static final String TRASH_DIR = "trash";
    private static final String TMP_DIR = "tmp";
    private static final String REMOVAL_LOG = "removal-log.jsonl";
    private static final String LOCK_FILE = "lock";
    private static final String STATE_FILE = "state.json";

    /** 最近一次失败原因（仅用于日志），不参与判定。 */
    public static final class Result {
        public boolean manifestFetched;
        /** 账本里已符合清单、无需下载的文件数。 */
        public int verified;
        public int downloaded;
        /** 因清单里路径消失（且 modId 也不在清单）而删除的服务器模组数。 */
        public int removed;
        /** 移入 {@code modsync/archive/} 的玩家模组数。 */
        public int archived;
        /** 同 modId 换文件名的非破坏性改名数（不计入删除）。 */
        public int renamed;
        /** 本轮放进 {@code mods/} 的文件数（ADD + UPDATE + DEPLOY）。 */
        public int deployed;
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

    /** 清单里的一个期望文件。{@code modId}/{@code url} 可能缺失。 */
    private record Desired(String sha256, long size, String modId, String url) {
    }

    /** 本轮用到的目录，集中一处避免到处拼路径。 */
    private record Layout(Path serverDir, Path targetDir, Path work) {
        Path tmp() {
            return work.resolve(TMP_DIR);
        }

        Path archive() {
            return work.resolve(ARCHIVE_DIR);
        }

        Path history() {
            return work.resolve(HISTORY_DIR);
        }

        Path lastManifest() {
            return work.resolve(LAST_MANIFEST);
        }

        Path removalLog() {
            return work.resolve(REMOVAL_LOG);
        }

        Path stateFile() {
            return work.resolve(STATE_FILE);
        }
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
        Layout l = new Layout(
                gameDir.resolve(cfg.serverDirName),
                gameDir.resolve(cfg.targetDirName),
                AgentConfig.workDir(gameDir));
        Files.createDirectories(l.serverDir());
        Files.createDirectories(l.targetDir());
        Files.createDirectories(l.work());

        State state = State.load(l.stateFile());
        Result result = new Result();
        Path lock = null;
        try {
            if (!cfg.dryRun) {
                lock = tryAcquireLock(l.work());
                if (lock == null) {
                    return result; // 另一个活着的实例正在同步，本轮让位
                }
            }
            sync(l, state, result);
            return result;
        } finally {
            if (lock != null) {
                releaseLock(lock);
            }
        }
    }

    // ------------------------------------------------------------------ 主流程

    private void sync(Layout l, State state, Result result) throws Exception {
        // ---------------------------------------------------------- 1. 拉取清单
        progress.phase("获取服务器清单");
        String previousServerId = state.serverId;
        Manifest manifest;
        try {
            manifest = Manifest.fetch(cfg.manifestUrl, state.etag, cfg.timeoutSeconds);
        } catch (IOException e) {
            unusableManifest(result, e.getMessage());
            return;
        }
        if (manifest != null && (manifest.rawBody == null || manifest.rawBody.isBlank())) {
            unusableManifest(result, "响应体为空");
            return;
        }
        if (manifest != null && manifest.files.isEmpty()) {
            unusableManifest(result, "files 数组为空");
            return;
        }

        Map<String, Desired> desired = new LinkedHashMap<>();
        String base = "";
        if (manifest != null) {
            result.manifestFetched = true;
            for (Manifest.Entry e : manifest.files) {
                if (!e.path.startsWith("mods/")) {
                    progress.warn("清单包含暂不支持的路径，已跳过: " + e.path);
                    continue;
                }
                desired.put(e.fileName(), new Desired(e.sha256, e.size, e.modId, e.url));
            }
            if (desired.isEmpty()) {
                unusableManifest(result, "清单没有 mods/ 条目");
                return;
            }
            base = manifest.downloadBase();
            progress.info("清单: " + manifest.summary());
        } else {
            progress.info("清单未变化 (HTTP 304)，沿用上次结果");
            for (Map.Entry<String, State.FileInfo> e : state.serverFiles.entrySet()) {
                desired.put(e.getKey(), new Desired(e.getValue().sha256, e.getValue().size, e.getValue().modId, null));
            }
        }

        // ---------------------------------------------------------- 1b. 版本比对
        if (manifest != null && checkVersionCompatibility(manifest, result)) {
            progress.info("按配置跳过本次同步（Minecraft 版本不匹配）");
            state.lastSyncAt = now();
            if (!cfg.dryRun) {
                state.save(l.stateFile());
            }
            return;
        }

        // ---------------------------------------------------------- 2. 可信度检查
        boolean mayDelete = false;
        if (manifest != null) {
            String reason = deletionBlockReason(manifest, state, previousServerId, l.lastManifest());
            if (reason == null) {
                mayDelete = true;
            } else {
                progress.info("本轮只增改，不删除（原因：" + reason + "）");
            }
            if (!previousServerId.isBlank() && !manifest.serverId.isBlank()
                    && !previousServerId.equals(manifest.serverId)) {
                state.serverFiles.clear();
                state.manifestHash = "";
                state.manifestGeneratedAt = "";
            }
            if (!manifest.serverId.isBlank()) {
                state.serverId = manifest.serverId;
            }
            state.etag = manifest.etag;
        }

        // ---------------------------------------------------------- 3. 计划：账本预检
        progress.phase("对账 " + cfg.serverDirName);
        Map<String, Path> ledger = listJarsMap(l.serverDir());
        Map<String, String> ledgerSha = new LinkedHashMap<>();
        Set<String> ledgerOk = new HashSet<>();
        List<String> adopted = new ArrayList<>();
        List<Map.Entry<String, Desired>> pending = new ArrayList<>();
        long pendingBytes = 0;
        for (Map.Entry<String, Desired> e : desired.entrySet()) {
            String name = e.getKey();
            Path file = ledger.get(name);
            if (file != null && ledgerMatches(file, name, e.getValue(), state)) {
                ledgerOk.add(name);
                ledgerSha.put(name, e.getValue().sha256());
                result.verified++;
                continue;
            }
            if (modsMatches(l.targetDir().resolve(name), name, e.getValue(), state)) {
                // mods/ 里已经有内容完全一致的文件：玩家自己装过整套模组、或删过账本。
                // 直接用它填账本——既不重新下载那几百 MB，也不该把它当成"玩家模组"归档。
                ledgerOk.add(name);
                ledgerSha.put(name, e.getValue().sha256());
                adopted.add(name);
                result.verified++;
                continue;
            }
            if (file != null) {
                // 旧账本内容的 sha：决定 mods/ 里的同名文件是服务器旧版（进 trash）还是玩家的（进 archive）
                ledgerSha.put(name, ledgerSha(file, name, state));
            }
            pending.add(e);
            pendingBytes += Math.max(0, e.getValue().size());
        }
        if (!adopted.isEmpty()) {
            progress.info("沿用 " + cfg.targetDirName + "/ 里已有的 " + adopted.size()
                    + " 个文件（内容与清单一致，未重复下载）");
        }
        progress.info("校验 " + cfg.serverDirName + ": 已符合 " + result.verified + " 个"
                + (pending.isEmpty() ? "" : "，需下载 " + pending.size() + " 个 (" + humanBytes(pendingBytes) + ")"));

        // ---------------------------------------------------------- 3b. 下载（先全部落到 tmp 并校验）
        if (!pending.isEmpty()) {
            if (cfg.dryRun) {
                for (Map.Entry<String, Desired> e : pending) {
                    progress.info("[dry-run] 将下载 " + e.getKey());
                }
                result.downloaded = pending.size();
            } else {
                progress.phase("下载模组");
                progress.setTotal(pendingBytes, pending.size());
                downloadAll(pending, base, l.tmp());
                result.downloaded = pending.size();
            }
        }
        if (progress.isCancelled()) {
            throw new CancelledException();
        }

        // ---------------------------------------------------------- 3c. 计划：REMOVE / RENAME / EVICT
        progress.phase("对账 " + cfg.targetDirName);
        Map<String, Path> modsFiles = listJarsMap(l.targetDir());
        Set<String> manifestModIds = new HashSet<>();
        Map<String, String> manifestNameByModId = new LinkedHashMap<>();
        for (Map.Entry<String, Desired> e : desired.entrySet()) {
            String id = normalizeId(e.getValue().modId());
            if (id != null) {
                manifestModIds.add(id);
                manifestNameByModId.putIfAbsent(id, e.getKey());
            }
        }

        List<String> removals = new ArrayList<>();
        Map<String, String> renames = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : ledger.entrySet()) {
            String name = e.getKey();
            if (desired.containsKey(name)) {
                continue;
            }
            String sha = ledgerSha(e.getValue(), name, state);
            ledgerSha.put(name, sha);
            String modId = ledgerModId(e.getValue(), name, state);
            String newName = modId == null ? null : manifestNameByModId.get(modId);
            if (newName != null) {
                renames.put(name, newName);
            } else {
                removals.add(name);
            }
        }

        List<String> evictions = new ArrayList<>();
        for (Map.Entry<String, Path> e : modsFiles.entrySet()) {
            if (ledger.containsKey(e.getKey()) || desired.containsKey(e.getKey())) {
                // 账本里的文件由对账处理；名字本身就是清单条目的（无论内容是否一致）由安装流程
                // 负责替换或沿用，不该再被当成"玩家的模组"归档一次。
                continue;
            }
            String modId = targetModId(e.getValue(), e.getKey(), state);
            if (modId != null && manifestModIds.contains(modId)) {
                evictions.add(e.getKey());
            }
        }

        if (!mayDelete) {
            // 阶段 2 判定「不允许删除」时，只保留 ADD / UPDATE / DEPLOY。
            //
            // RENAME 与 EVICT 不在这里清理：它们不是"服务器删掉了某个模组"，而是"同一个 modId 换了
            // 文件"和"玩家那份与服务器撞车"。若把它们一起拦下，本轮就会"加了新的却留着旧的"，
            // 两个同 modId 的 jar 同时躺在 mods/ 里会直接让游戏崩。两者都只进 trash/archive，可恢复。
            removals.clear();
        }

        String fingerprint = manifest != null ? manifest.rawHash : state.manifestHash;

        // ---------------------------------------------------------- 4. REMOVE（先删后加）
        for (String name : removals) {
            String sha = ledgerSha.get(name);
            Path inMods = modsFiles.get(name);
            if (cfg.dryRun) {
                progress.info("[dry-run] 将移除 " + cfg.targetDirName + "/" + name);
                result.removed++;
                continue;
            }
            if (inMods != null) {
                if (sameSha(inMods, name, sha, state)) {
                    moveToTrash(inMods, l.work());
                } else {
                    archivePlayer(inMods, name, l, fingerprint, "", result);
                }
            }
            Files.deleteIfExists(l.serverDir().resolve(name));
            state.serverFiles.remove(name);
            progress.info("服务器已删除 " + cfg.targetDirName + "/" + name);
            appendRemovalLog(l, "remove", cfg.targetDirName + "/" + name, sha,
                    "服务器清单不再包含该路径，modId 也不再出现", fingerprint);
            result.removed++;
        }

        // ---------------------------------------------------------- 5. RENAME / EVICT
        for (Map.Entry<String, String> r : renames.entrySet()) {
            String oldName = r.getKey();
            String newName = r.getValue();
            String sha = ledgerSha.get(oldName);
            Path inMods = modsFiles.get(oldName);
            if (cfg.dryRun) {
                progress.info("[dry-run] 将改名 " + cfg.targetDirName + "/" + oldName + " -> " + newName);
                continue;
            }
            if (inMods != null) {
                if (sameSha(inMods, oldName, sha, state)) {
                    moveToTrash(inMods, l.work());
                } else {
                    // sha 传空：归档日志要记玩家那份的真实 sha，而不是账本旧版的
                    archivePlayer(inMods, oldName, l, fingerprint, "", result);
                }
            }
            Files.deleteIfExists(l.serverDir().resolve(oldName));
            state.serverFiles.remove(oldName);
            result.renamed++;
            progress.info("服务器更新 " + cfg.targetDirName + "/" + oldName + " -> " + cfg.targetDirName + "/" + newName);
            appendRemovalLog(l, "rename", cfg.targetDirName + "/" + oldName, sha,
                    "同 modId 换成新文件名 " + newName, fingerprint);
        }

        for (String name : evictions) {
            Path inMods = modsFiles.get(name);
            if (inMods == null) {
                continue;
            }
            if (cfg.dryRun) {
                progress.info("[dry-run] 将归档 " + cfg.targetDirName + "/" + name);
                result.archived++;
                continue;
            }
            archivePlayer(inMods, name, l, fingerprint, "", result);
        }

        // ---------------------------------------------------------- 6. ADD / UPDATE / DEPLOY
        List<String> installs = new ArrayList<>();
        Map<String, String> modes = new HashMap<>();
        for (Map.Entry<String, Desired> e : desired.entrySet()) {
            String name = e.getKey();
            boolean ledgerFine = ledgerOk.contains(name);
            if (ledgerFine && modsMatches(l.targetDir().resolve(name), name, e.getValue(), state)) {
                continue;
            }
            installs.add(name);
            modes.put(name, ledgerFine ? "deploy" : (ledger.containsKey(name) ? "update" : "add"));
        }

        if (!cfg.dryRun && !adopted.isEmpty()) {
            // 账本里还没有这些文件，用 mods/ 里的同一份内容补上（不联网）
            for (String name : adopted) {
                putLedger(l, name, l.targetDir().resolve(name), desired.get(name), state);
            }
        }

        for (String name : installs) {
            Desired d = desired.get(name);
            String mode = modes.get(name);
            boolean deploy = "deploy".equals(mode);
            Path source = deploy
                    ? l.serverDir().resolve(name)
                    : l.tmp().resolve("dl-" + d.sha256() + ".part");
            if (cfg.dryRun) {
                progress.info("[dry-run] 将" + modeVerb(mode) + " " + cfg.targetDirName + "/" + name);
                result.deployed++;
                continue;
            }
            Path dst = l.targetDir().resolve(name);
            if (Files.isRegularFile(dst)) {
                String actual = modsSha(dst, name, state);
                if (!actual.equalsIgnoreCase(d.sha256())) {
                    String previous = ledgerSha.get(name);
                    if (previous != null && previous.equalsIgnoreCase(actual)) {
                        moveToTrash(dst, l.work());
                    } else {
                        archivePlayer(dst, name, l, fingerprint, actual, result);
                    }
                }
            }
            installIntoMods(l, name, source, d);
            if (deploy) {
                progress.info("补回 " + cfg.targetDirName + "/" + name);
                appendRemovalLog(l, "deploy", cfg.targetDirName + "/" + name, d.sha256(),
                        "mods/ 缺失或被改动，从账本补回", fingerprint);
            } else {
                putLedger(l, name, source, d, state);
                if ("update".equals(mode)) {
                    progress.info("服务器更新 " + cfg.targetDirName + "/" + name + " -> "
                            + cfg.targetDirName + "/" + name);
                } else {
                    progress.info("新增 " + cfg.targetDirName + "/" + name);
                }
                appendRemovalLog(l, mode, cfg.targetDirName + "/" + name, d.sha256(),
                        "update".equals(mode) ? "服务器同路径内容更新" : "服务器新增模组", fingerprint);
            }
            result.deployed++;
        }

        // ---------------------------------------------------------- 7. 落盘 + 校验扫描 + 汇总
        if (!cfg.dryRun) {
            if (manifest != null) {
                Files.writeString(l.lastManifest(), manifest.rawBody, StandardCharsets.UTF_8);
                writeHistory(l, manifest.rawBody);
                state.manifestHash = manifest.rawHash;
                state.manifestGeneratedAt = manifest.generatedAt == null ? "" : manifest.generatedAt;
            }
            state.lastSyncAt = now();
            refreshServerFiles(l, state);
            refreshDeployed(l, state);
            pruneTrash(l.work());
            cleanTmp(l);
            state.save(l.stateFile());
        }
        progress.info("完成: 校验 " + result.verified + "，下载 " + result.downloaded + "，移除 " + result.removed
                + "，归档 " + result.archived + "，补齐 " + result.deployed);
    }

    /** 清单为空/坏/拉不动：本轮完全不动（不删也不加），非 strict 时照常启动。 */
    private void unusableManifest(Result result, String detail) throws IOException {
        result.manifestFetched = false;
        progress.info("清单为空或无法解析，按获取失败处理：本轮不做任何改动"
                + (detail == null || detail.isBlank() ? "" : "（" + detail + "）"));
        if (cfg.strict) {
            throw new IOException("清单不可用: " + detail);
        }
    }

    // ------------------------------------------------------------------ 删除护栏

    /**
     * 四条护栏，全满足才允许删除。
     *
     * @return {@code null} 表示允许删除，否则是拒绝原因
     */
    private String deletionBlockReason(Manifest manifest, State state, String previousServerId, Path lastManifestFile) {
        if (previousServerId.isBlank() || manifest.serverId.isBlank() || !previousServerId.equals(manifest.serverId)) {
            return "服务器标识与本地记录不同或首次同步";
        }
        if (cfg.forceSync) {
            progress.info("已按 --force-sync 忽略清单 generatedAt 顺序护栏");
        } else {
            Instant previous = parseInstant(state.manifestGeneratedAt);
            Instant current = parseInstant(manifest.generatedAt);
            if (previous == null || current == null) {
                return "缺少可比对的 generatedAt（本地或服务器）";
            }
            if (!current.isAfter(previous)) {
                return "清单时间戳未前进（本地 " + state.manifestGeneratedAt + "，服务器 " + manifest.generatedAt + "）";
            }
        }
        if (state.manifestHash.isBlank() || !lastManifestMatches(lastManifestFile, state.manifestHash)) {
            return "缺少上次成功应用的清单（" + LAST_MANIFEST + "）";
        }
        return null;
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean lastManifestMatches(Path file, String expectedSha) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return ModMeta.sha256(file).equalsIgnoreCase(expectedSha);
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 实例锁

    /**
     * 取实例锁（{@code modsync/lock}，含 PID）。
     *
     * @return 锁文件路径；已有一个活着的实例持锁时返回 {@code null}
     */
    private Path tryAcquireLock(Path work) throws IOException {
        Path lock = work.resolve(LOCK_FILE);
        if (Files.isRegularFile(lock)) {
            long pid = lockPid(lock);
            long self = ProcessHandle.current().pid();
            if (pid > 0 && pid != self && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                progress.warn("另一个 ModSync 实例正在运行（PID " + pid + "），本轮跳过同步，避免互相删除文件");
                return null;
            }
            if (pid > 0) {
                progress.info("接管陈旧的实例锁（PID " + pid + " 已不存在）");
            }
        }
        Map<String, Object> body = Json.map();
        body.put("pid", ProcessHandle.current().pid());
        body.put("time", now());
        Files.createDirectories(work);
        Files.writeString(lock, Json.write(body), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return lock;
    }

    private static long lockPid(Path lock) {
        try {
            Map<String, Object> root = Json.asObject(Json.parse(Files.readString(lock, StandardCharsets.UTF_8)));
            return root == null ? -1L : Json.num(root, "pid", -1L);
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    private static void releaseLock(Path lock) {
        try {
            if (lockPid(lock) == ProcessHandle.current().pid()) {
                Files.deleteIfExists(lock);
            }
        } catch (IOException ignored) {
            // 释放锁失败不影响同步结果
        }
    }

    // ------------------------------------------------------------------ 版本比对

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

    // ------------------------------------------------------------------ 下载

    private void downloadAll(List<Map.Entry<String, Desired>> pending, String base, Path tmpDir) throws Exception {
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
                        downloadOne(entry.getKey(), entry.getValue(), base, tmpDir);
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

    private void downloadOne(String name, Desired desired, String base, Path tmpDir) throws Exception {
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

    // ------------------------------------------------------------------ 文件操作

    /** tmp → 原子 move 进 {@code mods/}；同名冲突由调用方先归档。 */
    private void installIntoMods(Layout l, String name, Path source, Desired d) throws IOException {
        Files.createDirectories(l.tmp());
        Path stage = l.tmp().resolve("install-" + d.sha256() + ".part");
        Files.copy(source, stage, StandardCopyOption.REPLACE_EXISTING);
        Path dst = l.targetDir().resolve(name);
        try {
            Files.move(stage, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(stage, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 把新内容写进账本（旧账本文件进 trash）。 */
    private void putLedger(Layout l, String name, Path source, Desired d, State state) throws IOException {
        Path dst = l.serverDir().resolve(name);
        if (!source.equals(dst)) {
            if (Files.isRegularFile(dst)) {
                moveToTrash(dst, l.work());
            }
            Files.copy(source, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        String modId = normalizeId(d.modId());
        state.serverFiles.put(name, stamp(dst, d.sha256(), "server", modId == null ? "" : modId));
    }

    /** 玩家模组进 {@code modsync/archive/}：每个文件名只留一份，永不自动放回。 */
    private void archivePlayer(Path file, String name, Layout l, String fingerprint, String sha, Result result)
            throws IOException {
        Path dir = l.archive();
        Files.createDirectories(dir);
        Path dst = dir.resolve(name);
        String actual = sha;
        if (actual == null || actual.isEmpty()) {
            try {
                actual = ModMeta.sha256(file);
            } catch (IOException e) {
                actual = "";
            }
        }
        if (Files.exists(dst)) {
            progress.warn("归档中已有 " + name + "，本次副本改放 " + TRASH_DIR);
            moveToTrash(file, l.work());
        } else {
            Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        result.archived++;
        progress.info("归档玩家模组 " + cfg.targetDirName + "/" + name + " -> "
                + AgentConfig.CONFIG_DIR + "/" + ARCHIVE_DIR + "/" + name);
        appendRemovalLog(l, "archive", cfg.targetDirName + "/" + name, actual,
                "玩家模组与服务器模组 modId 冲突，服务器版本优先", fingerprint);
    }

    private void appendRemovalLog(Layout l, String action, String path, String sha, String reason, String manifestHash) {
        if (cfg.dryRun) {
            return;
        }
        Map<String, Object> entry = Json.map();
        entry.put("time", now());
        entry.put("action", action);
        entry.put("path", path);
        entry.put("sha256", sha == null ? "" : sha);
        entry.put("reason", reason);
        entry.put("manifestHash", manifestHash == null ? "" : manifestHash);
        try {
            Files.createDirectories(l.work());
            Files.writeString(l.removalLog(), Json.writeCompact(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            progress.warn("无法写入 " + REMOVAL_LOG + ": " + e.getMessage());
        }
    }

    private void writeHistory(Layout l, String body) {
        try {
            Path dir = l.history();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(HISTORY_STAMP.format(Instant.now()) + ".json"), body, StandardCharsets.UTF_8);
            try (Stream<Path> stream = Files.list(dir)) {
                List<Path> snapshots = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                        .toList();
                for (int i = HISTORY_KEEP; i < snapshots.size(); i++) {
                    Files.deleteIfExists(snapshots.get(i));
                }
            }
        } catch (IOException e) {
            progress.warn("无法写入 " + HISTORY_DIR + ": " + e.getMessage());
        }
    }

    /** Keeps the newest {@link #TRASH_KEEP} trash batches so the folder cannot grow forever. */
    private static void pruneTrash(Path work) {
        Path trash = work.resolve(TRASH_DIR);
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

    /** 同步成功后清空 tmp：只有失败的残片会留下。 */
    private static void cleanTmp(Layout l) {
        Path tmp = l.tmp();
        if (!Files.isDirectory(tmp)) {
            return;
        }
        try (Stream<Path> stream = Files.list(tmp)) {
            for (Path p : stream.toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    static void moveToTrash(Path file, Path work) throws IOException {
        Path dir = work.resolve(TRASH_DIR).resolve(BATCH_STAMP.format(Instant.now()));
        Files.createDirectories(dir);
        Path dst = dir.resolve(file.getFileName().toString());
        int i = 1;
        while (Files.exists(dst)) {
            dst = dir.resolve(file.getFileName().toString() + "." + i++);
        }
        Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ 缓存与哈希

    /** {@code (size,mtime)} 命中才复用记录：稳态启动不做全量哈希。 */
    private static State.FileInfo cached(State.FileInfo rec, Path file) throws IOException {
        if (rec == null) {
            return null;
        }
        if (rec.size != Files.size(file) || rec.mtime != Files.getLastModifiedTime(file).toMillis()) {
            return null;
        }
        return rec;
    }

    private static State.FileInfo stamp(Path file, String sha, String origin, String modId) throws IOException {
        return new State.FileInfo(sha == null ? "" : sha, Files.size(file), origin,
                Files.getLastModifiedTime(file).toMillis(), modId == null ? "" : modId);
    }

    private boolean ledgerMatches(Path file, String name, Desired d, State state) throws IOException {
        long size = Files.size(file);
        if (d.size() >= 0 && size != d.size()) {
            return false;
        }
        State.FileInfo rec = cached(state.serverFiles.get(name), file);
        if (rec != null && !rec.sha256.isEmpty()) {
            return rec.sha256.equalsIgnoreCase(d.sha256());
        }
        String sha = ModMeta.sha256(file);
        state.serverFiles.put(name, stamp(file, sha, "server", rec == null ? "" : rec.modId));
        return sha.equalsIgnoreCase(d.sha256());
    }

    private boolean modsMatches(Path file, String name, Desired d, State state) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        long size = Files.size(file);
        if (d.size() >= 0 && size != d.size()) {
            return false;
        }
        State.FileInfo rec = cached(state.deployed.get(name), file);
        if (rec != null && !rec.sha256.isEmpty()) {
            return rec.sha256.equalsIgnoreCase(d.sha256());
        }
        String sha = ModMeta.sha256(file);
        state.deployed.put(name, stamp(file, sha, originOf(state, name), rec == null ? "" : rec.modId));
        return sha.equalsIgnoreCase(d.sha256());
    }

    /** {@code mods/<name>} 的内容是不是账本记录的那一份。 */
    private boolean sameSha(Path file, String name, String sha, State state) throws IOException {
        if (sha == null || sha.isEmpty() || !Files.isRegularFile(file)) {
            return false;
        }
        State.FileInfo rec = cached(state.deployed.get(name), file);
        if (rec != null && !rec.sha256.isEmpty()) {
            return rec.sha256.equalsIgnoreCase(sha);
        }
        String actual = ModMeta.sha256(file);
        state.deployed.put(name, stamp(file, actual, originOf(state, name), rec == null ? "" : rec.modId));
        return actual.equalsIgnoreCase(sha);
    }

    private String modsSha(Path file, String name, State state) throws IOException {
        State.FileInfo rec = cached(state.deployed.get(name), file);
        if (rec != null && !rec.sha256.isEmpty()) {
            return rec.sha256;
        }
        String sha = ModMeta.sha256(file);
        state.deployed.put(name, stamp(file, sha, originOf(state, name), rec == null ? "" : rec.modId));
        return sha;
    }

    private String ledgerSha(Path file, String name, State state) throws IOException {
        State.FileInfo rec = cached(state.serverFiles.get(name), file);
        if (rec != null && !rec.sha256.isEmpty()) {
            return rec.sha256;
        }
        String sha = ModMeta.sha256(file);
        state.serverFiles.put(name, stamp(file, sha, "server", rec == null ? "" : rec.modId));
        return sha;
    }

    /** 账本文件里的 modId；用 {@code (size,mtime)} 缓存，避免每轮都开 zip。 */
    private String ledgerModId(Path file, String name, State state) throws IOException {
        State.FileInfo rec = cached(state.serverFiles.get(name), file);
        if (rec != null && rec.modId != null && !rec.modId.isEmpty()) {
            return rec.modId;
        }
        String id = normalizeId(ModMeta.readModId(file));
        state.serverFiles.put(name, stamp(file, rec == null ? "" : rec.sha256, "server", id));
        return id;
    }

    /** {@code mods/} 文件里的 modId；玩家文件同样按 {@code (size,mtime)} 缓存。 */
    private String targetModId(Path file, String name, State state) throws IOException {
        State.FileInfo rec = cached(state.deployed.get(name), file);
        if (rec != null && rec.modId != null && !rec.modId.isEmpty()) {
            return rec.modId;
        }
        String id = normalizeId(ModMeta.readModId(file));
        state.deployed.put(name, stamp(file, rec == null ? "" : rec.sha256, originOf(state, name), id));
        return id;
    }

    private static String originOf(State state, String name) {
        return state.serverFiles.containsKey(name) ? "server" : "player";
    }

    private static String normalizeId(String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 收尾重扫账本：保证 sha/modId 缓存与真实文件一致。 */
    private void refreshServerFiles(Layout l, State state) {
        Map<String, State.FileInfo> next = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : listJarsMap(l.serverDir()).entrySet()) {
            String name = e.getKey();
            Path file = e.getValue();
            try {
                State.FileInfo rec = cached(state.serverFiles.get(name), file);
                String sha = rec != null && !rec.sha256.isEmpty() ? rec.sha256 : ModMeta.sha256(file);
                String modId = rec == null ? null : rec.modId;
                if (modId == null || modId.isEmpty()) {
                    modId = normalizeId(ModMeta.readModId(file));
                }
                next.put(name, stamp(file, sha, "server", modId));
            } catch (IOException ex) {
                State.FileInfo old = state.serverFiles.get(name);
                if (old != null) {
                    next.put(name, old);
                }
            }
        }
        state.serverFiles.clear();
        state.serverFiles.putAll(next);
    }

    /** 收尾重扫 {@code mods/}：服务器模组记 sha，玩家模组只记 modId，绝不重写文件。 */
    private void refreshDeployed(Layout l, State state) {
        Map<String, State.FileInfo> next = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : listJarsMap(l.targetDir()).entrySet()) {
            String name = e.getKey();
            Path file = e.getValue();
            String origin = originOf(state, name);
            try {
                State.FileInfo rec = cached(state.deployed.get(name), file);
                String sha = rec == null || rec.sha256 == null ? "" : rec.sha256;
                if (sha.isEmpty() && "server".equals(origin)) {
                    sha = ModMeta.sha256(file);
                }
                String modId = rec == null ? null : rec.modId;
                if (modId == null || modId.isEmpty()) {
                    modId = normalizeId(ModMeta.readModId(file));
                }
                next.put(name, stamp(file, sha, origin, modId));
            } catch (IOException ex) {
                State.FileInfo old = state.deployed.get(name);
                if (old != null) {
                    next.put(name, old);
                }
            }
        }
        state.deployed.clear();
        state.deployed.putAll(next);
    }

    // ------------------------------------------------------------------ 扫描工具

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

    private static String modeVerb(String mode) {
        return switch (mode) {
            case "deploy" -> "补回";
            case "update" -> "更新";
            default -> "新增";
        };
    }

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

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
