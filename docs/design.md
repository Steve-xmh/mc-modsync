# 客户端同步模型

`Syncer` 的实现规格。改 `Syncer` 前先读本文，并同步更新 [protocol.md](protocol.md)。

## 目录与职责

| 路径 | 职责 |
| --- | --- |
| `mods/` | 玩家生效目录：服务器模组 + 玩家自装模组。agent 只按规则改动服务器模组 |
| `mods-server/` | 服务端模组镜像。agent 私有；手动改动会被当成「服务器删除」处理 |
| `modsync/config.json` | agent 配置 |
| `modsync/state.json` | ETag、上次应用的 `generatedAt`、每文件 `size` + `mtime` + `sha256` 缓存、实例锁 |
| `modsync/last-manifest.json` | 上次成功应用的清单原文 |
| `modsync/manifest-history/*.json` | 历史清单，最多 5 份 |
| `modsync/archive/` | 因 modId 冲突被服务器版本顶掉的玩家模组，每个文件名一份，不会自动放回 |
| `modsync/trash/` | 被替换或删除的服务器模组旧版本，保留最近 10 批 |
| `modsync/tmp/` | 下载暂存，校验后原子改名 |
| `modsync/lock` | 实例锁：PID + 时间戳；PID 不存在即可接管 |
| `modsync/removal-log.jsonl` | 追加式审计：`{"time","action","path","sha256","reason","manifestHash"}` |

服务器模组不进 `archive/`：删掉即删除，需要时重新下载。

## 判定规则

- 服务器模组：`mods-server/` 里有的文件，或路径与 sha 命中当前清单的文件。
- 玩家模组：`mods/` 里不在镜像、modId 也不在清单中的文件。不触碰。
- 玩家碰撞：`mods/` 里非镜像文件，modId 属于清单 → 玩家那份移入 `modsync/archive/`，保留服务器版本。
- 沿用：清单里的文件在 `mods/` 里同名且内容一致（先比 `size`，再比 `sha256`）→ 直接用作镜像内容，不下载、不重写。
- 删除：镜像里有、新清单里路径消失，且该 modId 也不在新清单 → 删除（旧文件进 `trash/`）；modId 仍在新清单 → 视为更新或改名。

## 流程

### 阶段 0 准备

1. 读配置；取实例锁。
2. 读 `state.json`；读 `last-manifest.json` 并校验 sha256，校验失败当作不存在。

### 阶段 1 拉取清单

3. `GET manifest.json`（带 `If-None-Match`）。
   - `304`：跳过删除与改名，仍执行补回与碰撞处理。
   - 失败（网络、JSON 不可解析、body 或 `files` 为空）：本轮不做任何改动，非 `--strict` 时照常启动。

### 阶段 2 删除条件

4. 以下四条全满足才允许删除，否则本轮只增改：
   - `schema` 受支持，`serverId` 与本地一致；
   - `generatedAt` 严格晚于上次成功应用的值（缺失、相等或更旧 → 禁止删除，`--force-sync` 可覆盖）；
   - `last-manifest.json` 存在且 sha256 与 state 记录一致；
   - 清单 body 非空、`files` 数组存在且非空。
5. 不设批量删除阈值，也没有确认弹窗：删除只能来自镜像差集。

### 阶段 3 计划

6. `diff(清单, 镜像)`：

   | 清单 | 镜像 | 操作 |
   | --- | --- | --- |
   | 有 | 无 | ADD |
   | 有 | 有，sha 相同 | 无 |
   | 有 | 有，sha 不同 | UPDATE |
   | 无 | 有，modId 也不在清单 | REMOVE |
   | 无 | 有，modId 仍在清单 | RENAME |

7. `mods/` 侧两类操作：补回（清单里有、`mods/` 里缺失或 sha 不符）与碰撞（玩家模组 modId 与清单冲突）。

### 阶段 4 执行（顺序固定）

8. 不允许删除时只跳过 REMOVE；RENAME 与碰撞处理照常执行，只进 `trash/` / `archive/`，可恢复。
9. 先把所有新文件下载到 `modsync/tmp/` 并逐个校验 sha256，全部成功才继续。
10. REMOVE：`mods/<name>` 与镜像 sha 相同 → 移入 `trash/`；不同 → 是玩家同名文件，移入 `archive/`。随后从镜像删除。
11. RENAME / 碰撞：`mods/` 中对应文件移入 `trash/` / `archive/`。
12. ADD / UPDATE / 补回：`tmp` → 原子移动进 `mods/`，随后更新镜像。
13. 写 `last-manifest.json`、`manifest-history/`、`state.json`、`removal-log.jsonl`；校验扫描并汇总。

## 不变量

1. `mods/` 里不在镜像、modId 也不冲突的文件永不触碰。
2. 删除只由镜像差集驱动，不由「`mods/` 里多出来的文件」驱动。
3. 服务器模组不进 `archive/`。
4. `archive/` 只存玩家文件，每个文件名一份。
5. 清单不可信时一律不动。
6. 稳态启动不做全量哈希：命中 `size` + `mtime` + 已记 sha256 直接跳过；玩家模组的 modId 按 `(path,size,mtime)` 缓存。

## 契约

### 配置键

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `manifestUrl` | 空 | 清单地址 |
| `serverDir` | `mods-server` | 镜像目录 |
| `targetDir` | `mods` | 生效目录 |
| `strict` | `false` | `true` 时同步失败中止启动 |
| `forceSync` | `false` | 忽略 `generatedAt` 检查 |
| `timeoutSeconds` | `30` | HTTP 超时 |
| `parallelDownloads` | `4` | 并发下载 |
| `clientMinecraftVersion` / `clientLoaderVersion` | 空 | 覆盖本地版本探测 |
| `loaderMismatch` | `warn` | `warn` / `abort` / `ignore` |
| `minecraftMismatch` | `skip` | `skip` / `warn` / `abort` |
| `warnHoldSeconds` | `8` | 版本提示窗停留秒数 |

CLI 另有 `--dry-run`、`--verbose`、`--force-sync`、`--parallel`、`--timeout`、`--warn-hold`、`--abort-on-mismatch`、`--no-version-check`。

已删除的键与参数：`clientDir`、`autoMigrateUnknownMods`、`forceRebuild`、`gui`、`--force`、`--force-rebuild`、`--client-dir`、`--no-gui`。

### state.json 字段

`schema`、`serverId`、`manifestHash`（清单原文 sha256）、`etag`、`lastSyncAt`、`manifestGeneratedAt`、`serverFiles`（名字 → sha256/size）、`deployed`（sha256/size/origin/mtime/modId）。已删除 `lastBuildAt`、`lastFingerprint`。

### 宽容解析

- `generatedAt`：优先 `Instant.parse`，失败退 `OffsetDateTime.parse`，仍失败视为缺失（禁止删除），不报错退出。
- 本地清单可用 = 文件存在且 sha256 与 `state.manifestHash` 一致；不一致当作不存在。

### 日志子串（测试依赖）

| 场景 | 子串 |
| --- | --- |
| 清单为空或坏 | `清单为空或无法解析，按获取失败处理：本轮不做任何改动` |
| 只增改不删 | `本轮只增改，不删除（原因：` |
| 归档玩家模组 | `归档玩家模组 mods/<name> -> modsync/archive/<name>` |
| 服务器删除 | `服务器已删除 mods/<name>` |
| 服务器更新或改名 | `服务器更新 mods/<old> -> mods/<new>` |
| 补回缺失 | `补回 mods/<name>` |
| 汇总 | `完成: 一致 X，下载 Y，移除 Z，归档 W，补回 V` |
| 稳态 | `已一致 N，需下载 M` |

## 明确不做

- 不做批量删除阈值与确认弹窗。
- 不做多服务器切换：一个实例对应一个服务端。
- 不处理「服务器被入侵后要求客户端删除文件」这类服务器侧安全问题。
