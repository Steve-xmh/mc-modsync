# ModSync 协议 v1

客户端 agent 与服务器同步服务之间只依赖一个 JSON 摘要清单（manifest）和一组按内容寻址的文件下载接口。
设计目标：**服务器只公开一个端口**，客户端在游戏启动前完成「清单比对 → 增量下载 → 重组 mods」。

## 1. 目录与角色

服务端（独立进程或后续内嵌 NeoForge 模组）：

```
<server>/mods/              服务器实际加载的模组（默认发布来源）
<server>/modsync/extra/     额外发布给客户端的文件（客户端专用模组等，可选）
```

客户端（.minecraft 目录下，与 `mods` 并列）：

```
.minecraft/mods/            构建产物：由 agent 重组生成，玩家不要手动维护
.minecraft/mods-server/     与服务器清单完全同步（服务器拥有）
.minecraft/mods-client/     玩家自己安装、手动维护的模组
.minecraft/modsync/         agent 配置与状态
.minecraft/modsync/config.json
.minecraft/modsync/state.json
.minecraft/modsync/trash/   被替换/移除的文件的回收站（可安全删除）
```

## 2. 摘要清单 manifest.json

```json
{
  "schema": 1,
  "packName": "My Server",
  "serverId": "9f1c0f4a2b7d5e10",
  "generatedAt": "2026-10-08T15:04:05Z",
  "baseUrl": "http://play.example.com:25590/modsync",
  "minecraft": "1.21.1",
  "loader": { "type": "neoforge", "version": "21.1.248" },
  "files": [
    {
      "path": "mods/create-1.21.1-6.0.4.jar",
      "sha256": "3f786850e387550fdab836ed7e6dc881de23001b4b8f3d0f1c1f0f1e6f4a7c2b",
      "size": 1234567,
      "modId": "create",
      "url": "https://cdn.modrinth.com/data/....jar"
    }
  ]
}
```

| 字段 | 必需 | 说明 |
| --- | --- | --- |
| `schema` | 是 | 协议版本，当前为 `1` |
| `packName` | 否 | 展示名 |
| `serverId` | 是 | 服务器标识；变化即视为换服，客户端会全量重同步 |
| `generatedAt` | 是 | 清单生成时间（UTC，ISO-8601，**毫秒精度**，如 `2026-10-08T15:04:05.123Z`）。客户端把它当作列表版本的顺序护栏：缺失/相等/更旧时不执行删除 |
| `baseUrl` | 是 | 下载前缀，客户端回退下载地址为 `baseUrl + "/files/" + sha256` |
| `minecraft` | 否 | 服务器运行的 Minecraft 版本，客户端启动前比对 |
| `loader` | 否 | `{"type": "...", "version": "..."}`，服务器运行的加载器（如 `neoforge` / `21.1.248`） |
| `files[].path` | 是 | 相对游戏目录的路径。`mods/xxx.jar` 会被客户端映射到 `mods-server/xxx.jar`；其他路径 v1 仅告警不处理 |
| `files[].sha256` | 是 | 文件内容哈希（小写十六进制），客户端据此判断「存在就不动、不同就更」 |
| `files[].size` | 是 | 字节数，用于快速判断与进度显示 |
| `files[].modId` | 否 | 模组 id，用于 `mods-server` 与 `mods-client` 冲突时判定「服务器优先」 |
| `files[].url` | 否 | 直链（如 Modrinth/CurseForge CDN）。存在则优先走直链，服务器带宽可省；校验仍以 `sha256` 为准 |

约定：
- `files` 按 `path` 升序排列，保证同一内容生成的清单字节稳定。
- 同一 `path` 只出现一次；同一 `modId` 在服务端发布集合内应只有一个文件（服务端会告警）。
- 未列出的 `mods-server` 文件视为过期，客户端会移入 `trash/`。
- 纯服务端模组（Fabric `environment: "server"`、NeoForge `@Mod(dist = { DEDICATED_SERVER })`、或 Modrinth 判定 `client_side=unsupported`）不进入清单。

## 3. HTTP 接口

服务端默认监听 `0.0.0.0:25566`，路径前缀 `/modsync`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/HEAD | `/modsync/manifest.json` | 返回清单。响应头 `ETag: "<manifest sha256>"`；带 `If-None-Match` 命中时返回 `304` |
| GET/HEAD | `/modsync/files/{sha256}` | 按内容下载文件。命中返回 `200` + `Content-Length`；未知哈希返回 `404` |
| GET | `/modsync/health` | `{"status":"ok","files":27,"packName":"..."}` |

所有响应：
- `Content-Type: application/json; charset=utf-8`（清单/健康检查）、`application/java-archive`（文件）。
- `Cache-Control: no-cache`（清单）、`Cache-Control: public, max-age=31536000, immutable`（文件，内容寻址）。
- 服务端只读，不需要鉴权；如需限制，可在前面挂反向代理。建议在防火墙只放行该端口给玩家。

## 4. 客户端同步流程

1. 读取 `modsync/config.json`（缺失时用默认值并写入模板）。
2. `GET manifest.json`；`304` 时跳过下载阶段，但仍执行第 2b 步的版本检查与第 5 步的指纹检查。
2b. **版本检查**：清单带 `minecraft`/`loader` 时与本地实例比对（本地版本来自配置覆盖、Prism/MultiMC 的
   `mmc-pack.json` 或 `versions/*/*.json`）。加载器不一致 → 提示并可选中止；Minecraft 不一致 → 默认跳过本次同步。
3. **服务器目录同步**：对每个 `mods/*` 条目
   - 本地 `mods-server/<name>` 存在且 `sha256` 与 `size` 匹配 → 保持原样，不重写文件；
   - 否则下载（优先 `url`，回退 `baseUrl/files/<sha256>`）到 `.part`，校验 `sha256` 后原子改名。
4. 清理 `mods-server/` 中不在清单内的 `*.jar` → 移入 `modsync/trash/`。
5. **重组判定**：计算指纹 `sha256(manifestHash + sorted(mods-server/*) + sorted(mods-client/*))`。
   指纹变化、或 `mods/` 缺失、或 `--force-rebuild` 时执行重组：
   1. 把 `mods/*.jar` 中**不是上次由 agent 生成**的文件迁移到 `mods-client/`（记录日志，不删除）；
   2. 目标集合 = `mods-server` 全部 jar，再加入 `mods-client` 中 modId 与服务器不冲突的 jar（**同 id 以服务器版本为准**，被跳过的文件记录日志）；
   3. `mods/*.jar` 移入 `trash/`，随后按目标集合复制（普通复制，避免硬链接导致误改影响到源）；
   4. 写入 `state.json` 的新指纹与生成清单。
6. 只有在重建发生时才打印「已更新，需重启」以外的情况：预启动模式下天然不需要重启。

## 5. 状态文件 state.json

```json
{
  "schema": 1,
  "serverId": "9f1c0f4a2b7d5e10",
  "manifestHash": "…",
  "etag": "…",
  "lastSyncAt": "2026-10-08T15:04:05Z",
  "lastBuildAt": "2026-10-08T15:04:06Z",
  "serverFiles": { "create-1.21.1-6.0.4.jar": { "sha256": "…", "size": 1234567 } },
  "deployed": { "create-1.21.1-6.0.4.jar": { "sha256": "…", "size": 1234567, "origin": "server", "mtime": 1791470768000 } },
  "lastFingerprint": "…"
}
```

`deployed` 用于识别 `mods/` 里哪些文件是 agent 自己生成的；不在此列且位于 `mods/*.jar` 的文件视为玩家手动放入，重组时迁移到 `mods-client/`。`deployed[].mtime` 是部署时记录的最后修改时间：与实际文件一致时跳过哈希计算（快速路径），不一致才重新哈希比对，避免每次启动都全量哈希。

## 6. 兼容性与降级

- 服务器返回 `schema` 大于客户端支持版本 → 客户端报错并保持现状（不破坏本地实例）。
- 清单下载失败（服务器未开、断网）→ 默认沿用上次结果直接启动游戏（`--strict` 可改为中止启动）。
- GUI 不可用（headless / 启动器设置 `java.awt.headless=true`）→ 自动退回控制台进度输出。
