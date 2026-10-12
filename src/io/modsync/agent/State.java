package io.modsync.agent;

import io.modsync.common.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 持久化的 agent 状态：清单指纹/ETag、上次应用的 {@code generatedAt}、账本快照与 {@code mods/} 缓存。
 *
 * <p>v1.2.0 起它是「缓存 + 审计」，不再是唯一真源：真源是服务器清单与 {@code mods-server/} 账本目录。
 */
public final class State {

    public int schema = 1;
    public String serverId = "";
    /** 清单原文 sha256（清单指纹），配合 {@code last-manifest.json} 判断删除是否可信。 */
    public String manifestHash = "";
    public String etag = "";
    public String lastSyncAt = "";
    /** 上次成功应用清单的 {@code generatedAt}（删除护栏的时间戳依据）。 */
    public String manifestGeneratedAt = "";
    /** name -> info, the desired contents of {@code mods-server}. */
    public final Map<String, FileInfo> serverFiles = new LinkedHashMap<>();
    /** name -> info, what the agent knows about the jars currently in {@code mods}. */
    public final Map<String, FileInfo> deployed = new LinkedHashMap<>();

    public static final class FileInfo {
        public String sha256 = "";
        public long size;
        public String origin = "";
        /** Last-modified time recorded when the agent saw the file; 0 when unknown. */
        public long mtime;
        /** 缓存的 modId（玩家模组的 (size,mtime) 快速路径），未知时为空串。 */
        public String modId = "";

        public FileInfo() {
        }

        public FileInfo(String sha256, long size, String origin, long mtime) {
            this(sha256, size, origin, mtime, "");
        }

        public FileInfo(String sha256, long size, String origin, long mtime, String modId) {
            this.sha256 = sha256 == null ? "" : sha256;
            this.size = size;
            this.origin = origin == null ? "" : origin;
            this.mtime = mtime;
            this.modId = modId == null ? "" : modId;
        }
    }

    public static State load(Path file) {
        State s = new State();
        if (!Files.isRegularFile(file)) {
            return s;
        }
        try {
            Map<String, Object> root = Json.asObject(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            if (root == null) {
                return s;
            }
            s.schema = (int) Json.num(root, "schema", 1);
            s.serverId = Json.str(root, "serverId", "");
            s.manifestHash = Json.str(root, "manifestHash", "");
            s.etag = Json.str(root, "etag", "");
            s.lastSyncAt = Json.str(root, "lastSyncAt", "");
            s.manifestGeneratedAt = Json.str(root, "manifestGeneratedAt", "");
            readMap(root.get("serverFiles"), s.serverFiles);
            readMap(root.get("deployed"), s.deployed);
        } catch (IOException | RuntimeException e) {
            // A corrupt state file must never block a sync; it is rebuilt from scratch instead.
            System.out.println("[modsync] 警告: 状态文件损坏，忽略: " + e.getMessage());
        }
        return s;
    }

    private static void readMap(Object node, Map<String, FileInfo> target) {
        Map<String, Object> map = Json.asObject(node);
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Map<String, Object> fi = Json.asObject(e.getValue());
            if (fi == null) {
                continue;
            }
            target.put(e.getKey(), new FileInfo(Json.str(fi, "sha256", ""), Json.num(fi, "size", -1),
                    Json.str(fi, "origin", ""), Json.num(fi, "mtime", 0), Json.str(fi, "modId", "")));
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(toJson()), StandardCharsets.UTF_8);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> root = Json.map();
        root.put("schema", (long) schema);
        root.put("serverId", serverId);
        root.put("manifestHash", manifestHash);
        root.put("etag", etag);
        root.put("lastSyncAt", lastSyncAt);
        root.put("manifestGeneratedAt", manifestGeneratedAt);
        root.put("serverFiles", writeMap(serverFiles));
        root.put("deployed", writeMap(deployed));
        return root;
    }

    private static Map<String, Object> writeMap(Map<String, FileInfo> source) {
        Map<String, Object> out = Json.map();
        for (Map.Entry<String, FileInfo> e : source.entrySet()) {
            Map<String, Object> fi = Json.map();
            fi.put("sha256", e.getValue().sha256);
            fi.put("size", e.getValue().size);
            if (!e.getValue().origin.isEmpty()) {
                fi.put("origin", e.getValue().origin);
            }
            if (e.getValue().mtime > 0) {
                fi.put("mtime", e.getValue().mtime);
            }
            if (!e.getValue().modId.isEmpty()) {
                fi.put("modId", e.getValue().modId);
            }
            out.put(e.getKey(), fi);
        }
        return out;
    }
}
