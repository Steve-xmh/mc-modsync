package io.modsync.agent;

import io.modsync.common.Json;
import io.modsync.common.ModMeta;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parsed {@code manifest.json} plus the HTTP fetch helper. */
public final class Manifest {

    public static final int SUPPORTED_SCHEMA = 1;

    public String packName = "";
    public String serverId = "";
    public String generatedAt = "";
    public String baseUrl = "";
    /** Minecraft version the server runs (optional; empty when the server did not announce it). */
    public String minecraft = "";
    /** Loader the server runs, e.g. {@code neoforge} (optional). */
    public String loaderType = "";
    /** Loader version the server runs, e.g. {@code 21.1.248} (optional). */
    public String loaderVersion = "";
    public final List<Entry> files = new ArrayList<>();

    /** sha256 of the raw response body, used for logging and the local state file. */
    public String rawHash = "";
    /** 清单原文，成功应用后原样写进 {@code modsync/last-manifest.json} 与 history。 */
    public String rawBody = "";
    /** ETag from the server (may be empty). */
    public String etag = "";

    public static final class Entry {
        public String path = "";
        public String sha256 = "";
        public long size;
        public String modId;
        public String url;

        public String fileName() {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    /**
     * Fetches the manifest.
     *
     * @return the parsed manifest, or {@code null} when the server answered {@code 304 Not Modified}
     */
    public static Manifest fetch(String manifestUrl, String etag, int timeoutSeconds)
            throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(manifestUrl);
        } catch (RuntimeException e) {
            throw new IOException("清单地址无效: " + manifestUrl, e);
        }
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .build();
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds * 4L)))
                .header("User-Agent", "modsync-agent/1.0")
                .header("Accept", "application/json")
                .GET();
        if (etag != null && !etag.isBlank()) {
            req.header("If-None-Match", etag);
        }
        HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 304) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new IOException("清单请求失败: HTTP " + resp.statusCode() + " (" + manifestUrl + ")");
        }
        String body = new String(resp.body(), StandardCharsets.UTF_8);
        Manifest m = parse(body);
        m.rawHash = ModMeta.hex(sha256Bytes(resp.body()));
        m.rawBody = body;
        m.etag = resp.headers().firstValue("ETag").orElse("");
        m.sourceUrl = manifestUrl;
        return m;
    }

    static Manifest parse(String body) throws IOException {
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (RuntimeException e) {
            throw new IOException("清单不是合法 JSON: " + e.getMessage(), e);
        }
        Map<String, Object> root = Json.asObject(parsed);
        if (root == null) {
            throw new IOException("清单根节点不是对象");
        }
        long schema = Json.num(root, "schema", -1);
        if (schema < 0) {
            throw new IOException("清单缺少 schema 字段");
        }
        if (schema > SUPPORTED_SCHEMA) {
            throw new IOException("清单 schema=" + schema + " 高于本 agent 支持的 " + SUPPORTED_SCHEMA + "，请升级 agent");
        }
        Manifest m = new Manifest();
        m.packName = Json.str(root, "packName", "");
        m.serverId = Json.str(root, "serverId", "");
        m.generatedAt = Json.str(root, "generatedAt", "");
        m.baseUrl = Json.str(root, "baseUrl", "");
        m.minecraft = Json.str(root, "minecraft", "");
        Map<String, Object> loader = Json.asObject(root.get("loader"));
        if (loader != null) {
            m.loaderType = Json.str(loader, "type", "");
            m.loaderVersion = Json.str(loader, "version", "");
        }
        List<Object> files = Json.asArray(root.get("files"));
        if (files == null) {
            throw new IOException("清单缺少 files 数组");
        }
        for (Object o : files) {
            Map<String, Object> fo = Json.asObject(o);
            if (fo == null) {
                continue;
            }
            Entry e = new Entry();
            e.path = Json.str(fo, "path", "");
            e.sha256 = Json.str(fo, "sha256", "").toLowerCase(java.util.Locale.ROOT);
            e.size = Json.num(fo, "size", -1);
            e.modId = Json.str(fo, "modId", null);
            e.url = Json.str(fo, "url", null);
            if (e.path.isBlank() || e.sha256.length() != 64) {
                throw new IOException("清单条目缺少 path/sha256: " + Json.write(fo));
            }
            m.files.add(e);
        }
        return m;
    }

    /** Fallback download base derived from the manifest URL when {@code baseUrl} is absent. */
    public String downloadBase() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        if (sourceUrl != null && sourceUrl.endsWith("manifest.json")) {
            return sourceUrl.substring(0, sourceUrl.length() - "manifest.json".length());
        }
        return "";
    }

    /** URL this manifest was fetched from; used by {@link #downloadBase()}. */
    private String sourceUrl = "";

    private static byte[] sha256Bytes(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String summary() {
        long total = 0;
        for (Entry e : files) {
            total += Math.max(0, e.size);
        }
        return files.size() + " 个文件, " + Syncer.humanBytes(total) + ", 服务器 " + (serverId.isBlank() ? "未知" : serverId);
    }

}
