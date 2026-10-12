# AGENTS.md

给在本仓库里工作的 AI 代理（以及第一次读代码的人）的速查说明。**先读这一页，再看 `README.md` 与 `docs/deploy.md`。**

## 这是什么

ModSync：给 Minecraft **NeoForge 1.21.1** 服务器用的模组同步方案。

- 服务端只需要装一个 NeoForge 模组：启动时向 FML 读取**实际加载的模组文件**，发布成一份 JSON 清单，公开一个 HTTP 端口（也可用独立进程形态扫目录）；
- 客户端 agent 在**游戏启动前**把 `mods/` 增量对账到服务器状态（不整目录重组）；
- `mods/` 里玩家自装、且 mod id 不与服务器冲突的模组**永不被触碰**；冲突时服务器版本优先，玩家那份进 `modsync/archive/`。

## 仓库结构

| 路径 | 作用 |
| --- | --- |
| `src/io/modsync/common/` | `Json`（无依赖 JSON）、`Args`（CLI/agent 参数）、`ModMeta`（sha256/sha1、modId 提取）、`ModEnv`（服务端/客户端侧向判定） |
| `src/io/modsync/server/` | `SyncServer`（清单生成 + HTTP 服务 + 过滤）、`ServerMain`（独立进程 CLI） |
| `src/io/modsync/agent/` | `Agent`（`premain`/`main` 双入口）、`Syncer`（同步核心）、`Manifest`、`State`、`ClientVersion`、`Progress`/`GuiProgress`、`AgentConfig` |
| `src/io/modsync/neoforge/` | `ModSyncMod`：把 `SyncServer` 挂到 NeoForge 生命周期的外壳（`mods/` 里一个 jar 即可） |
| `build.sh` | 编译 agent + 独立服务端 jar（javac + jar，**不需要 Gradle**） |
| `build-mod.sh` | 编译 NeoForge 模组（对着本地已有的 NeoForge 依赖编译） |
| `test/` | 6 套端到端测试 + `run-all.sh` |
| `docs/protocol.md` | manifest / HTTP 协议规范（v1） |
| `docs/deploy.md` | 真实服务器部署手册（含排查表与踩坑记录） |

## 构建

```bash
./build.sh                 # → build/modsync-agent.jar, build/modsync-server.jar
./build-mod.sh             # → build/modsync-neoforge-<ver>.jar（需要本地 NeoForge 依赖）
./build-mod.sh --neo-libs /path/to/neoforge/libraries   # 或显式指定依赖来源
```

`build-mod.sh` 会按顺序自动找依赖：`$MODSYNC_NEO_LIBS` → 仓库旁的 `server/libraries`、`libraries` → Prism 的 `libraries/`（macOS / Linux / Windows）。找不到时它会退出 1，`run-all.sh` 会把它记为 skipped 而不是失败。

构建完它会对产物做硬断言（缺 `META-INF/neoforge.mods.toml`、缺 `ModSyncMod.class`、`mods.toml` 里没有 `modId="modsync"` 都直接 exit 1）；CI 会下载三个 NeoForge API 依赖并设置 `MODSYNC_NEO_LIBS`，让这个断言在 CI 上真正执行。

## 测试

```bash
bash test/run-all.sh                 # 6 套：agent / 版本比对 / 服务端模组过滤 / 对账 / 全栈
MODSYNC_TEST_ONLINE=1 bash test/run-all.sh   # 额外跑一条需要外网的 Modrinth 直链用例
bash test/e2e-filter.sh              # 也可以单独跑任意一套
```

测试只依赖 JDK 21、`python3`、`curl`、`jar`/`javac`；CI 在 ubuntu-latest 上跑 `run-all.sh`。

## 改动时必须保持的约束

这些不是风格偏好，破坏它们会造成真实事故：

1. **零第三方依赖。** 两个 jar 只允许用 JDK（`java.net.http`、`com.sun.net.httpserver`、`java.util.zip`…）。不要引入 Gson/Netty/Guava，也不要引入 Gradle 工程；模组编译靠 `build-mod.sh` 对着本地依赖 javac。
2. **玩家的文件不可删。** `mods/` 里不在账本、且 mod id 不与清单冲突的 jar 一律不触碰；删除只能由**账本差集**驱动（绝不能由「`mods/` 里多出来的文件」驱动）；被顶掉的玩家版本进 `modsync/archive/`，服务器旧版进 `modsync/trash/`。
3. **同 mod id 冲突：服务器优先。** 玩家版本移入 `modsync/archive/`（可手动恢复），绝不自动放回 `mods/`。
4. **协议向后兼容。** `schema` 保持 `1`，新字段必须是可选的（如 `minecraft`、`loader`）；老客户端遇到不认识的字段要能忽略。改协议必须同步更新 `docs/protocol.md`。
5. **发布器永不发布自己。** 两种形态都靠运行时拿自身 jar 文件名排除（模组用反射，独立进程用 `CodeSource`）；默认 glob 只是兜底，不要删。
6. **服务端的发布源是「FML 实际加载的模组」**（`publishLoadedMods`，`ModSyncMod.loadedModFiles()` 反射读 `ModList`），
   目录扫描（`modDirs`/`extraDirs`）只是补充与回退。不要把它改回"只扫目录"：那样会漏掉不在 `mods/` 的模组，
   也会把服务器没在运行的 jar 推给玩家。独立进程用 `--mod-file` 对应同一能力。
7. **纯服务端模组不发布给客户端**，但判定不出侧向时**宁可多发**。判定依据：Fabric `fabric.mod.json.environment`、NeoForge `@Mod(dist = {...})`（必须真解析 class 注解，见 `ModEnv`）、Modrinth 项目元数据（兜底）。逃生门是 `forcePublish` / `--force-publish`。
8. **`--once` 模式下 stdout 只能有清单**，所有诊断输出必须走 `diag()`（在 `--once` 时落到 stderr）。
9. **服务端不得被拖垮。** 模组里的同步逻辑必须 `catch Throwable` 并只打日志；端口被占用、配置写坏都不能影响 Minecraft 服务器。
10. **同步失败默认放行游戏启动**（只告警），`--strict` 才中止；版本不匹配的默认策略是"加载器提醒 / MC 版本跳过同步"。
11. **改行为就要改文档**：`README.md` 的行为表、`docs/deploy.md`、`docs/protocol.md` 三处。

## 代码风格

- JDK 21：`record`、`switch` 表达式、`var` 都用了；不要为了兼容旧版本而降级写法。
- 面向用户的日志、注释、文档一律中文；标识符与代码英文。
- `javac -Xlint:all` 必须零警告（`build.sh` 已开启）。
- 不写魔法数字到业务逻辑里；协议/端口等常量集中在各自类顶部。
- 新增工具方法要么被用到，要么别留（评审时会被当死代码）。

## 常见陷阱（踩过的）

- **`@Mod.dist` 是数组且默认 `{CLIENT, DEDICATED_SERVER}`**：不写 `dist` = 双端；显式写两个值也是双端。别把"代码里出现 `DEDICATED_SERVER`"当成服务端专属（`ModEnv` 用注解解析就是为了避免这个误判）。
- **glob 转成正则时必须转义**：`SyncServer.globPattern` 用 `Pattern.quote`，新增匹配逻辑要保持。
- **`state.json` 的 `mtime` 快速路径**：文件大小 + 已记录 sha + mtime 都对上才跳过哈希；改动对账/校验逻辑时别把这条捷径弄丢，否则每次启动都会全量哈希。
- **`trash/` 只保留最近 `TRASH_KEEP` 批**，内容未变的文件要原地保留（否则每次对账都产生垃圾）；**服务器模组不进 `archive/`**（缺了重新下载），`archive/` 只放玩家模组。
- **网络环境可能很慢/有代理**：Modrinth 查询的连接超时已放宽到 15 秒且失败只告警一次；Forge/NeoForge 官方安装器把超时硬编码成 5 秒（`sipush 5000`），在网络慢的环境必然失败——`docs/deploy.md` 的排查表里有记录，`build-mod.sh` 不用 Gradle 也是为了绕开它。
- **测试可移植性**：不要用 `stat -f`（BSD）或 `find -quit`；用测试里的 `file_mtime` / `file_size` / `has_file` 助手。
- **`set -o pipefail` 下别把 `外部命令 | grep -q` 当断言**：`grep -q` 命中即退出会让左端收到 SIGPIPE（退出码 141），整条管道被判失败，而且只在输出够大时才稳定复现。先把输出落到文件再匹配（`build-mod.sh` 的产物断言就是这么写的）。
- **产物校验必须断言，不能只打印**：曾经把 `unzip -l | grep ... | sed` 当校验用，结果 heredoc 被误删导致发布的模组 jar 少了 `neoforge.mods.toml`（FML 直接不载入，等于装了不生效）而没人发现。凡是「构建产物应该长什么样」的检查，写成 失败即 exit 1 的断言，并让 CI 真的跑到它。

## 提交约定

- 一个改动一个主题；提交信息写清"为什么"，而不只是"改了什么"。
- 推之前跑 `bash test/run-all.sh`；涉及服务端过滤/版本比对的改动，对应套件必须新增断言而不是"手动验过"。
- 不要把 `build/` 提交进仓库（已在 `.gitignore`）；要发布二进制走 GitHub Release。
