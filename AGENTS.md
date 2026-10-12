# AGENTS.md

仓库速查。先读本页，再看 `README.md` 与 `docs/`。

## 简介

ModSync：NeoForge 1.21.1 服务端的模组同步工具。

- 服务端装一个 NeoForge 模组即可发布：发布源是 FML 实际加载的 jar，公开一个 HTTP 端口提供清单与下载。
- 客户端 agent 在游戏启动前把 `mods/` 同步到服务端状态。
- `mods/` 里玩家自装、mod id 又不与服务器冲突的文件不触碰；冲突时服务器版本优先，玩家那份移入 `modsync/archive/`。

## 仓库结构

| 路径 | 作用 |
| --- | --- |
| `src/io/modsync/common/` | `Json`、`Args`、`ModMeta`（哈希 / modId / `FMLModType` / `isRealJar`）、`ModEnv`（侧向判定） |
| `src/io/modsync/server/` | `SyncServer`（清单生成 + HTTP + 过滤）、`ServerMain`（独立进程 CLI） |
| `src/io/modsync/agent/` | `Agent`（`premain` / `main`）、`Syncer`、`Manifest`、`State`、`ClientVersion`、`Progress` / `GuiProgress`、`AgentConfig` |
| `src/io/modsync/neoforge/` | `ModSyncMod`：把 `SyncServer` 挂到 NeoForge 生命周期的外壳 |
| `build.sh` / `build-mod.sh` | 编译 agent + 独立服务端 jar / NeoForge 模组 jar（javac + jar，无 Gradle） |
| `test/` | 6 套端到端测试 + `run-all.sh` |
| `docs/protocol.md` | 清单与 HTTP 协议（v1） |
| `docs/design.md` | 客户端同步模型（改 `Syncer` 前必读） |
| `docs/deploy.md` | 部署手册与排查表 |

## 构建

```bash
./build.sh                 # → build/modsync-agent.jar、build/modsync-server.jar
./build-mod.sh             # → build/modsync-neoforge-<ver>.jar
./build-mod.sh --neo-libs <dir>   # 或设 MODSYNC_NEO_LIBS
```

`build-mod.sh` 会在 `$MODSYNC_NEO_LIBS`、`./server/libraries`、`./libraries`、Prism 的 `libraries/` 里找 NeoForge 依赖，找不到时退出 1。构建后断言产物含 `META-INF/neoforge.mods.toml`、`ModSyncMod.class`、`modId="modsync"`，任一缺失即失败。

## 测试

```bash
bash test/run-all.sh                 # 5 套 + 构建，共 169 项
MODSYNC_TEST_ONLINE=1 bash test/run-all.sh   # 额外跑一条需要外网的 Modrinth 用例
bash test/e2e-filter.sh              # 单跑某一套
```

只依赖 JDK 21、`python3`、`curl`、`jar`。CI 在 ubuntu-latest 上跑 `run-all.sh`。

## 必须保持的约束

1. 零第三方依赖：只用 JDK（`java.net.http`、`com.sun.net.httpserver`、`java.util.zip` 等）。不引入 Gson/Netty/Guava，也不引入 Gradle。
2. 玩家的文件不可删。`mods/` 里不在镜像、mod id 也不冲突的 jar 不触碰；删除只能由镜像差集驱动；被顶掉的玩家版本进 `modsync/archive/`，服务器旧版进 `modsync/trash/`。
3. 同 mod id 冲突时服务器优先，玩家版本进 `archive/`，不自动放回 `mods/`。
4. 协议保持向后兼容：`schema` 固定为 `1`，新字段可选；改协议同时更新 `docs/protocol.md`。
5. 发布器不发布自己：两种形态都按运行时自身 jar 文件名排除（模组用反射，独立进程用 `CodeSource`）；默认 glob 只是兜底，不要删。
6. 服务端发布源是「FML 实际加载的 jar」（`publishLoadedMods`）：模组按 `ModList.getMods()`，`FMLModType: LIBRARY` 的库按 `mods/` 目录 + `MANIFEST.MF` 识别（这类 jar 不在任何 ModList API 里）；`modDirs` / `extraDirs` 只作补充。不要改回「只扫目录」。
7. 不发布 jar-in-jar 内嵌件：判定用 `ModMeta.isRealJar`（要求位于默认文件系统）。FML 给 JIJ 子模组返回的虚拟路径 `Files.isRegularFile()` 为 true，只靠它过滤不住。
8. 纯服务端模组不发布，判不出侧向时按发布处理。依据：Fabric `fabric.mod.json.environment`、NeoForge `@Mod(dist = {...})`（解析 class 注解，见 `ModEnv`）、Modrinth 元数据（兜底）。逃生门是 `forcePublish` / `--force-publish`。
9. `--once` 模式下 stdout 只能有清单，诊断输出走 `diag()`（该模式下落到 stderr）。
10. 服务端不被同步逻辑拖垮：模组里的启动逻辑 `catch Throwable` 并只打日志；端口占用、配置损坏都不影响 Minecraft 服务端。
11. 同步失败默认放行启动（只告警），`--strict` 才中止；版本不匹配默认「加载器提醒 / MC 版本跳过同步」。
12. 改行为就要改文档：`README.md` 的行为表、`docs/deploy.md`、`docs/protocol.md`（协议相关时）。

## 代码风格

- JDK 21：`record`、`switch` 表达式、`var` 都在用，不要为兼容旧版本降级写法。
- 面向用户的日志、注释、文档用中文；标识符用英文。术语统一：同步（不用「对账」）、镜像（`mods-server`）、补回、归档、回收站。
- `javac -Xlint:all` 必须零警告（`build.sh` 已开）。
- 不写魔法数字到业务逻辑；协议、端口等常量集中在各类顶部。
- 不留死代码：新增的工具方法要么被用到，要么别加。

## 容易踩的地方

- `@Mod.dist` 是数组且默认 `{CLIENT, DEDICATED_SERVER}`：不写 `dist` 即双端。不要用字符串匹配 `DEDICATED_SERVER` 判断侧向。
- glob 转正则必须转义：`SyncServer.globPattern` 用 `Pattern.quote`。
- `state.json` 的 `(size, mtime, sha)` 快速路径不要弄丢，否则每次启动都全量哈希。
- `trash/` 只保留最近 `TRASH_KEEP` 批；内容未变的文件原地保留（否则每次同步都产生垃圾）；服务器模组不进 `archive/`。
- FML 的 `ModList` 不暴露 libraries：`getMods()` 与 `getModFiles()` 都没有 `FMLModType: LIBRARY` 的 jar。
- 网络环境可能很慢或有代理：Modrinth 查询连接超时已放宽到 15 秒且只告警一次；NeoForge 官方安装器把超时硬编码为 5 秒（`sipush 5000`），慢网必然失败，`build-mod.sh` 不用 Gradle 就是为了绕开它。
- 测试可移植性：不要用 `stat -f`（BSD）或 `find -quit`；用 `test/` 里的 `file_mtime`、`file_size`、`has_file` 助手。
- `set -o pipefail` 下不要把 `外部命令 | grep -q` 当断言：`grep -q` 命中即退出会让左端收到 SIGPIPE（退出码 141），整条管道被判失败。先把输出落文件再匹配。
- 产物校验要写成失败即 `exit 1` 的断言，不能只打印：曾经因为 heredoc 被误删导致发布的模组 jar 少了 `neoforge.mods.toml`，FML 直接不载入。

## 提交

- 一个改动一个主题；提交信息写清原因。
- 推送前跑 `bash test/run-all.sh`；涉及发布范围或版本比对的改动，对应套件要新增断言。
- 不要提交 `build/`（已在 `.gitignore`）。
- 本机 MSYS 的 bash 跑不了 `run-all.sh`（`build.sh` 生成的 `sources.txt` 是 POSIX 绝对路径，Windows 版 javac 读不了）；在 macOS/Linux 上跑。
