# ModSync 协议 v1

客户端 agent 与服务端发布器之间的接口：一个 JSON 清单（manifest）加一组按内容寻址的文件下载路径。

## 1. 目录与角色

服务端：

```
<server>/mods/              服务器加载的模组（默认发布来源）
<server>/modsync/extra/     只发给客户端的模组（可选）
```

客户端（`<gameDir>` 下）：

```
<gameDir>/mods/             生效目录：服务端模组 + 玩家自装模组
<gameDir>/mods-server/      服务端模组镜像（agent 私有）
<gameDir>/modsync/          配置、状态与回收站
```

## 2. 清单 manifest.json

```json
{
  "schema": 1,
  "packName": "My Server",
  "serverId": "9f1c0f4a2b7d5e10",
  "generatedAt": "2026-10-08T15:04:05.123Z",
  "baseUrl": "http://play.example.com:25590/modsync",
  "minecraft": "1.21.1",
  "loader": { "type": "neoforge", "version": "21.1.248" },
  "files": [
    {
      "path": "mods/create-1.21.1-6.0.10.jar",
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
| `serverId` | 是 | 服务器标识；变化即视为换服，客户端全量重同步 |
| `generatedAt` | 是 | 生成时间（UTC，ISO-8601，毫秒精度）。客户端用它做顺序检查：缺失、相等或更旧时不执行删除 |
| `baseUrl` | 是 | 下载前缀；回退地址为 `baseUrl + "/files/" + sha256` |
| `minecraft` | 否 | 服务器 Minecraft 版本，客户端启动前比对 |
| `loader` | 否 | `{"type": "...", "version": "..."}`，服务器加载器 |
| `files[].path` | 是 | 相对游戏目录的路径。`mods/xxx.jar` 映射到 `mods-server/xxx.jar`；其他路径 v1 只告警不处理 |
| `files[].sha256` | 是 | 文件内容哈希（小写十六进制） |
| `files[].size` | 是 | 字节数，用于快速判断与进度显示 |
| `files[].modId` | 否 | 模组 id，用于「同 id 以服务器版本为准」的判定 |
| `files[].url` | 否 | 直链；存在则优先走直链，仍以 `sha256` 校验 |

约定：

- `files` 按 `path` 升序排列，保证同一内容生成的清单字节稳定。
- 同一 `path` 只出现一次；同一 `modId` 在发布集合内应只有一个文件（服务端会告警）。
- 不在清单里的 `mods-server` 文件视为已删除，客户端移入 `modsync/trash/`。
- 纯服务端模组不进入清单（Fabric `environment: "server"`、NeoForge `@Mod(dist = { DEDICATED_SERVER })`、Modrinth 判定 `client_side=unsupported`）。
- 加载器自身与 jar-in-jar 内嵌件不进入清单。

## 3. HTTP 接口

默认监听 `0.0.0.0:25590`，前缀 `/modsync`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/HEAD | `/modsync/manifest.json` | 清单。响应头 `ETag: "<manifest sha256>"`；`If-None-Match` 命中返回 `304` |
| GET/HEAD | `/modsync/files/{sha256}` | 按内容下载。命中 `200` + `Content-Length`；非法哈希 `400`；未知哈希 `404` |
| GET | `/modsync/health` | `{"status":"ok","files":123,"packName":"..."}` |

响应头：清单 `application/json; charset=utf-8` + `Cache-Control: no-cache`；文件 `application/java-archive` + `Cache-Control: public, max-age=31536000, immutable`。服务端只读，不带鉴权。

## 4. 客户端同步流程

1. 读取 `modsync/config.json`，缺失时用默认值并写入模板。
2. `GET manifest.json`。`304` 时跳过下载阶段，版本检查与一致性检查仍然执行。
3. 版本检查：清单带 `minecraft` / `loader` 时与本地实例比对，按 `minecraftMismatch` / `loaderMismatch` 处理。
4. 同步镜像 `mods-server/`：清单里的每个文件
   - 本地 `mods-server/<name>` 与清单的 `sha256`、`size` 一致 → 保留，不重写；
   - `mods/<name>` 里有内容一致的文件 → 直接用作镜像，不重新下载；
   - 否则下载（优先 `url`，回退 `baseUrl/files/<sha256>`）到 `modsync/tmp/`，校验 `sha256` 后原子改名；
   - 不在新清单里的镜像文件 → 移入 `modsync/trash/`，同时从 `mods/` 移除。
5. 同步生效目录 `mods/`：
   - 清单新增 → 从镜像复制进 `mods/`；
   - 同 `path` 内容变化 → 替换，旧文件进 `trash/`；
   - 同 `modId` 换了文件名 → 视为更新，先移除旧的再放新的；
   - 镜像里有、`mods/` 里缺失 → 从镜像补回；
   - `mods/` 里 mod id 与清单冲突的玩家文件 → 移入 `modsync/archive/`，服务器版本保留；
   - `mods/` 里不在镜像、mod id 也不冲突的文件 → 视为玩家自装，不触碰。
6. 删除只由镜像差集驱动，并需同时满足：`serverId` 与本地记录一致、清单指纹可信、`generatedAt` 比上次更新、本地存在上次成功应用的清单。任一不满足时本轮只增改、不删除（日志 `本轮只增改，不删除`）。
7. 写入 `modsync/last-manifest.json`、`manifest-history/`、`state.json`、`removal-log.jsonl`。

## 5. 状态文件 state.json

```json
{
  "schema": 1,
  "serverId": "9f1c0f4a2b7d5e10",
  "manifestHash": "…",
  "etag": "…",
  "lastSyncAt": "2026-10-08T15:04:05.123Z",
  "manifestGeneratedAt": "2026-10-08T15:04:05.123Z",
  "serverFiles": { "create-1.21.1-6.0.10.jar": { "sha256": "…", "size": 1234567 } },
  "deployed": { "create-1.21.1-6.0.10.jar": { "sha256": "…", "size": 1234567, "origin": "server", "mtime": 1791470768000, "modId": "create" } }
}
```

- `serverFiles`：`mods-server/` 的目标内容。
- `deployed`：agent 已知的 `mods/` 内容，`origin` 记录来源。`mtime` 与实际文件一致时跳过哈希（快速路径），不一致才重算。
- state.json 是缓存与审计记录，不是唯一真源；损坏时忽略并从清单与目录重建。

## 6. 兼容与降级

- 清单 `schema` 高于客户端支持版本 → 客户端报错并保持现状。
- 清单不可用（服务器未开、断网、JSON 损坏、`files` 为空）→ 本轮不做改动，照常启动；`--strict` 可改为中止。
- GUI 不可用（headless）→ 退回控制台进度输出。
