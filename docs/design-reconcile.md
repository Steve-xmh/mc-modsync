# 设计：增量对账式同步（v1.2.0）

本文记录 v1.2.0 对同步模型的改造。**它是实现规格**：改 `Syncer` 前先读这里。

## 背景：为什么要改

旧模型是「重建式」：`mods/` 是构建产物，每次启动由 `mods-server/` + `mods-client/` 重新拼出来；
玩家丢进 `mods/` 的未知 jar 会被**迁移**到 `mods-client/`（`Syncer.java` 旧代码）。

后果：玩家用启动器的模组管理器删掉一个客户端模组，`mods-client/` 里的存档下一次重组又把它放回
`mods/` —— 删除永远不生效。

新模型改成「增量对账式」：`mods/` 是玩家真实的生效目录（启动器管的就是它），`mods-server/` 是服务端
镜像（账本），清单是权威内容。agent 只做增量增删改，不再整目录重拼。

## 目录与职责

| 路径 | 职责 |
| --- | --- |
| `mods/` | 玩家真实生效目录：服务器模组 + 玩家自装模组。**agent 只按规则动服务器模组** |
| `mods-server/` | 账本 = 服务端镜像。agent 私有目录（像数据库），手动往里塞东西会被当成"服务器删除"处理 |
| `modsync/config.json` | agent 配置 |
| `modsync/state.json` | ETag、上次应用的 `generatedAt`、每文件 `size+mtime+sha` 快路径缓存、实例锁信息 |
| `modsync/last-manifest.json` | 上次**成功应用**的清单原文（配合 state 里的 raw sha 校验） |
| `modsync/manifest-history/*.json` | 历史清单，只留 JSON，默认 5 份（审计/回滚参考） |
| `modsync/archive/` | **只存玩家模组**：因 modId 冲突被服务器版本顶掉时移入，每个文件名只留一份，永不自动放回 |
| `modsync/trash/` | 服务器模组的旧版本/删除件的短期回收（保留最近 10 批） |
| `modsync/removal-log.jsonl` | 每次改动的审计日志：文件名、sha、原因、当时的清单指纹 |

**服务器模组不进归档**：删掉就删掉，需要时从服务器重新下载。

## 判定规则

- **谁是服务器模组**：`mods-server/`（账本）里有的文件，或路径+sha 命中当前清单的文件。
- **谁是玩家模组**：`mods/` 里不在账本、且 modId 不在清单中的文件。**一律不碰**。
- **玩家碰撞**：`mods/` 里非账本文件，若其 modId ∈ 清单 modId 集合 → 服务器优先，玩家那份移入
  `modsync/archive/`，并计入破坏性变更。
- **删除**：账本里有、新清单里路径消失 → 且旧 modId **也不在**新清单 → 真删除（破坏性）；
  若该 modId 仍在新清单 → 视为**更新/改名**（非破坏性，旧的进 trash）。

## 同步流程

### 阶段 0 准备
1. 读配置；取实例锁 `modsync/lock`（含 PID，陈旧可接管），避免同一实例被启动两次时互删。
2. 读 `state.json`；读 `last-manifest.json` 并校验其 raw sha —— **校验失败当作不存在**。

### 阶段 1 拉取清单
3. `GET manifest.json`（带 `If-None-Match`）。
   - `304` → 无变更，跳到阶段 5（`mods/` 仍可能被玩家动过）。
   - 网络失败 / JSON 不可解析 → **不做任何破坏性操作**；非 `--strict` 时照常启动。

### 阶段 2 可信度检查（决定本轮是否允许删除）
4. 以下四条全满足才允许删除，否则**只增改、不删除**：
   - `schema` 支持，`serverId` 与本地一致（不一致 = 视为首次，禁删）；
   - `generatedAt`（毫秒精度）不早于上次成功应用的值；缺失/相等/更旧 → 禁删（`--force-sync` 可覆盖）；
   - `last-manifest.json` 存在且校验通过（没有本地清单 = 没有删除依据）；
   - 清单 body 非空、`files` 数组存在且**非空**。
5. **不做批量删除阈值，也不做确认弹窗。** 删除的安全性由上面四条 + "删除只能来自账本差集"
   这条不变量保证：能删的只可能是"上次服务器清单里确实有、这次确实没有"的文件。
   - `files` 为空数组、body 为空/空白、JSON 解析失败 → 一律按**获取清单失败**处理：本轮不做任何
     改动（既不删也不加），非 `--strict` 时照常启动。
   - 已知取舍：管理员真把服务器模组清空时客户端不跟着清（空清单按失败处理）。需要时手动清一次
     `mods-server/` 与 `mods/` 里对应的 jar。

### 阶段 3 计划
6. `diff(清单, 账本)` 得到四类操作：
   | 清单 | 账本 | 结果 |
   | --- | --- | --- |
   | 有 | 无 | ADD |
   | 有 | 有，sha 同 | OK（不动文件） |
   | 有 | 有，sha 异 | UPDATE（= 删旧 + 加新） |
   | 无 | 有，旧 modId 也不在清单 | REMOVE（破坏性） |
   | 无 | 有，旧 modId 仍在清单 | RENAME（非破坏性） |
7. `mods/` 侧两件事：
   - **补齐（DEPLOY）**：清单里的文件在 `mods/` 缺失或 sha 不符 → 需要部署（含"玩家手删了服务器模组"的补回）；
   - **碰撞（EVICT）**：见判定规则。
8. 不需要计算"破坏性数量"——没有阈值门。

### 阶段 4 执行（顺序固定，保证崩溃安全）
9. 若阶段 2 判定"不允许删除"，本阶段只保留 ADD / UPDATE / DEPLOY 三类操作。
10. **先把所有需要的新文件下载到 `modsync/tmp/` 并逐个校验 sha**（全部成功才继续下一步）。
11. **再执行 REMOVE**：`mods/<name>` 与账本 sha 相同 → 删（旧版进 trash）；不同 → 是玩家的同名文件 →
    移入 archive。然后从账本删除。
12. **执行 EVICT / RENAME**：`mods/` 中对应文件移入 archive / trash。
13. **执行 ADD / UPDATE / DEPLOY**：`tmp` → 原子 move 进 `mods/`（同名冲突先归档），随后更新账本。
14. 写 `last-manifest.json` + `manifest-history/`、`state.json`、`removal-log.jsonl`。

## 不变量（改动时必须保持）

1. `mods/` 里不在账本、且 modId 不冲突的文件 → **永不触碰**。
2. 删除只能由**账本差集**驱动；绝不能由「`mods/` 里多出来的文件」驱动。
3. 服务器模组不进归档，缺了就重新下载。
4. 归档只保留玩家文件，每个文件名一份。
5. 清单不可信（拉取失败/解析失败/body 或 `files` 为空/时间戳倒退/无本地清单）→ 一律不动。
6. 稳态启动不做全量哈希；玩家模组的 modId 扫描按 `(path,size,mtime)` 缓存。

## 性能要求

- 200 个模组、1GB 的包，稳态启动只允许做 stat 级操作 + 少量哈希（玩家新增/变化的 jar）。
- 只有内容可能变化的文件才重算 sha；命中 `size+mtime+已记 sha` 直接跳过。

## 测试清单（先写测试）

1. 服务器新增模组 → 只下载新增的那一个。
2. 服务器删模组 → 账本与 `mods/` 同时移除，旧版进 trash。
3. 服务器改名/更新（同 modId 换文件名）→ 旧文件清除、新文件就位，且**不计入**删除。
4. **玩家删掉客户端模组 → 不再复活**（本次改造的核心验收）。
5. 玩家删掉服务器模组 → 下次启动被补回。
6. 玩家模组 modId 与服务器冲突 → 玩家那份进 `modsync/archive/`，`mods/` 里只剩服务器版本。
7. 首次同步（无账本）→ 只增不删，玩家已有模组原封不动。
8. 清单时间戳更旧/相同 → 不删除；`--force-sync` 可绕过。
9. 清单拉取失败 / JSON 损坏 → 不删除、不报错退出（非 strict）。
10. 清单 `files` 为空数组 / body 空白 / JSON 损坏 → 本轮**完全不动**（既不删也不加），且非 strict 继续启动。
11. 中断（模拟只有部分 tmp 下载成功）→ 复跑后 `mods/` 与 `mods-server/` 收敛，无重复 modId。
12. `mods/` 里相同 sha 的文件不被重写（mtime 不变）。

## 接口契约（实现与测试共同遵守）

### 配置键（`modsync/config.json`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `manifestUrl` | 空 | 清单地址 |
| `serverDir` | `mods-server` | 账本目录 |
| `targetDir` | `mods` | 玩家生效目录 |
| `strict` | `false` | true 时同步失败会中止启动 |
| `dryRun` | `false` | 只预览 |
| `verbose` | `false` | 详细日志 |
| `timeoutSeconds` | `30` | HTTP 超时 |
| `parallelDownloads` | `4` | 并发下载 |
| `forceSync` | `false` | 忽略 `generatedAt` 顺序护栏（`--force-sync`） |
| `clientMinecraftVersion` / `clientLoaderVersion` | 空 | 覆盖本地版本探测 |
| `loaderMismatch` | `warn` | `warn` / `abort` / `ignore` |
| `minecraftMismatch` | `skip` | `skip` / `warn` / `abort` |
| `warnHoldSeconds` | `8` | 版本提示窗口停留秒数 |

**删除的键/参数**：`clientDir`、`autoMigrateUnknownMods`、`forceRebuild`、`gui`；
CLI 上的 `--force` / `--force-rebuild` / `--client-dir` / `--no-gui` 一并删除，新增 `--force-sync`。

### 运行期文件

| 路径 | 说明 |
| --- | --- |
| `modsync/last-manifest.json` | 上次成功应用的清单**原文** |
| `modsync/manifest-history/<时间戳>.json` | 历史清单，只留 JSON，最多 5 份 |
| `modsync/archive/<name>` | 被服务器版本顶掉的**玩家**模组，每个文件名一份，永不自动放回 |
| `modsync/trash/<批次>/` | 服务器模组旧版本/删除件的短期回收，最多 10 批 |
| `modsync/removal-log.jsonl` | 追加式审计：`{"time","action","path","sha256","reason","manifestHash"}` |
| `modsync/lock` | 实例锁：PID + ISO 时间戳；PID 已消失即视为陈旧可接管 |
| `modsync/tmp/` | 下载暂存，校验后才原子 move 进 `mods/` |

### State 新增/保留字段

保留 `schema`、`serverId`、`manifestHash`（清单原文 sha256，即"清单指纹"）、`etag`、`lastSyncAt`、
`serverFiles`（账本快照：名字 → sha/size/mtime）、`deployed`（`mods/` 缓存：sha/size/mtime/origin）。
新增 `manifestGeneratedAt`（已应用清单的 `generatedAt`）。删除 `lastBuildAt`、`lastFingerprint`。

### 清单有效性判定（任一不满足 → 本轮**完全不动**）

body 为空/空白、JSON 解析失败、`files` 数组缺失或为空。

### 删除护栏（四条全满足才允许删除，否则只增改）

`serverId` 与 state 一致（state 为空时视为首次，禁删）；`generatedAt` 不早于 `manifestGeneratedAt`
（缺失/相等/更旧 → 禁删，`--force-sync` 覆盖）；`last-manifest.json` 存在且 sha 等于 `manifestHash`；
清单有效性通过。

### 日志契约（测试依赖这些子串）

| 场景 | 日志子串 |
| --- | --- |
| 清单为空/坏 | `清单为空或无法解析，按获取失败处理：本轮不做任何改动` |
| 只增改不删 | `本轮只增改，不删除（原因：` |
| 归档玩家模组 | `归档玩家模组 mods/<name> -> modsync/archive/<name>` |
| 服务器删除 | `服务器已删除 mods/<name>` |
| 服务器更新/改名 | `服务器更新 mods/<old> -> mods/<new>` |
| 补齐缺失 | `补回 mods/<name>`（玩家手删服务器模组时） |
| 汇总 | `完成: 校验 X，下载 Y，移除 Z，归档 W，补齐 V` |
| 未变化 | 保留既有 `已符合 N，需下载 M` 预检行 |

### 操作顺序（固定）

1. 计划（diff 清单 ↔ 账本 + `mods/` 补齐扫描 + 玩家碰撞扫描）
2. 下载全部所需新文件到 `tmp` 并校验 sha（全部成功才继续）
3. REMOVE（`mods/` 同名且 sha 与账本一致 → 删/进 trash；不一致 → 是玩家文件，进 archive）→ 账本删
4. EVICT / RENAME（进 archive / trash）
5. ADD / UPDATE / DEPLOY（`tmp` → 原子 move 进 `mods/`，随后更新账本）
6. 写 `last-manifest.json`、history、state、removal-log
7. 校验扫描 + 汇总

## 明确不做的事

- **不做批量删除阈值与确认弹窗**：有本地清单交叉比对，破坏性大删除不会出现；空/坏清单已按失败处理。
- **不考虑"服务器被入侵后要求客户端删文件"**：那是服务器侧安全问题，与本项目无关。
- **不做多服务器切换**：一个实例只对应一个服务端。

## 与旧实现的差异（要删掉的东西）

- 删除 `Syncer.rebuild()`、`Result.migrated`、`clientDirName` / `mods-client/` 相关逻辑与文档。
- 删除 `--force`（强制重组）语义；不再有"组装"这一步。
- `state.json` 从"唯一真源"降级为"缓存 + 审计"。
