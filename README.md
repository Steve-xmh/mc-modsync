# ModSync

[![CI](https://github.com/Steve-xmh/mc-modsync/actions/workflows/ci.yml/badge.svg)](https://github.com/Steve-xmh/mc-modsync/actions/workflows/ci.yml)

NeoForge 1.21.1 服务端的模组同步工具。服务端用一个 HTTP 端口提供清单和文件下载，客户端在游戏启动前把 `mods/` 同步成服务端状态。

- 同步在 Minecraft 主类加载之前完成：Prism/MultiMC 的预启动命令、PCL 的启动前执行命令，或 `-javaagent`。
- `mods/` 里玩家自装、mod id 又不与服务器冲突的文件不会被改动；冲突时以服务器版本为准，玩家那份移入 `modsync/archive/`。
- 服务端只装一个模组即可发布；发布源是 FML 实际加载的 jar，不需要登记目录。
- 不发布自己、不发布 jar-in-jar 内嵌件、不发布纯服务端模组。
- 只用 JDK 21，无第三方依赖。

## 构建

```bash
./build.sh        # build/modsync-agent.jar、build/modsync-server.jar
./build-mod.sh    # build/modsync-neoforge-<ver>.jar（对着本地 NeoForge 依赖 javac，无 Gradle）
```

`build-mod.sh` 找不到 NeoForge 依赖时用 `--neo-libs <dir>` 或环境变量 `MODSYNC_NEO_LIBS` 指定。

## 服务端

### 方式 A：NeoForge 模组

把 `modsync-neoforge-<ver>.jar` 放进服务端 `mods/`，重启。首次启动生成 `config/modsync-server.json`：

```json
{
  "enabled": true,
  "publishLoadedMods": true,
  "modDirs": [],
  "extraDirs": ["modsync/extra"],
  "excludes": ["modsync-neoforge-*.jar", "modsync-server*.jar"],
  "port": 25590,
  "bind": "0.0.0.0",
  "baseUrl": "",
  "packName": "",
  "serverId": "",
  "modrinthLookup": false,
  "filterServerOnlyMods": true,
  "forcePublish": []
}
```

改配置或增删模组后需重启服务端：清单只在启动时生成一次，不热重载。

**发布范围**

| 服务器上的情况 | 是否发布 |
| --- | --- |
| FML 加载的模组（不限目录） | 发布 |
| FML 以 `FMLModType: LIBRARY` 加载的库（如 KotlinForForge） | 发布；读 `MANIFEST.MF` 识别 |
| jar-in-jar 内嵌件 | 不发布；它是父 jar 内部路径，磁盘上没有这个文件 |
| 加载器自身（`minecraft` / `neoforge`）、`libraries/` 下的产物 | 不发布 |
| ModSync 自己 | 不发布 |
| 客户端专用、服务端不加载的模组（Sodium / 光影 / 小地图） | 不发布；放进 `modsync/extra/` 或 `modDirs` 才发布 |
| 判定为纯服务端的模组 | 不发布；`forcePublish` 可覆盖 |
| `mods/` 里有但服务器未加载的 jar | 不发布 |

侧向判定依据：Fabric `fabric.mod.json` 的 `environment`、NeoForge `@Mod(dist = ...)`（解析 class 注解）、Modrinth 项目元数据（需开 `modrinthLookup`）。判不出侧向时按发布处理。

**启动日志**

```
[modsync] publishing 123 loaded jar(s): 122 mod(s) + 1 FML library jar(s); ignored 46 jar-in-jar/loader entry(ies)
[modsync] publishing 123 files (379.3 MB) at http://host:25590/modsync
[modsync-server] skipping server-only mod (clients do not need it): Chunky-1.4.36.jar [from mod metadata]
```

**接口**：`GET /modsync/health`、`GET /modsync/manifest.json`、`GET /modsync/files/{sha256}`。

### 方式 B：独立进程

```bash
java -jar modsync-server.jar \
  --mods ./mods --extra ./modsync/extra \
  --port 25590 --bind 0.0.0.0 \
  --base-url http://play.example.com:25590/modsync \
  --minecraft-version 1.21.1 --loader-version 21.1.248
```

参数：`--mod-file <jar>`（精确发布单个文件，可重复）、`--exclude <glob>`、`--force-publish <glob>`、`--no-filter-server-only`、`--modrinth-lookup`、`--state <file>`、`--pack-name <name>`。

### 方式 C：静态托管

```bash
java -jar modsync-server.jar --mods ./mods --extra ./modsync/extra \
  --base-url https://cdn.example.com/modsync --once --write-manifest out/manifest.json
```

把清单和 `files/` 目录交给任意静态托管；客户端只发 HTTP GET。代价是没有按玩家鉴权。

## 客户端

玩家侧需要 `modsync-agent.jar` 加一个启动前钩子。

### Prism Launcher / MultiMC

实例 → Settings → Custom Commands → Pre-launch command：

```
"$INST_JAVA" -jar "/绝对路径/modsync-agent.jar" --game-dir "$INST_MC_DIR"
```

退出码非 0 会中断启动。`$INST_JAVA`、`$INST_MC_DIR` 由 Prism 替换。

### PCL2 / PCL2 CE

版本设置 → 高级 → 启动前执行命令：

```
"<java.exe>" -jar "<绝对路径>\modsync-agent.jar" --game-dir "<实例目录>"
```

同时写入等待开关（该选项在界面中是隐藏的，不写的话 PCL 不等命令结束就启动游戏）：

| 范围 | 配置文件 | 键 |
| --- | --- | --- |
| 单实例 | `<实例目录>\PCL\config.v1.yml` | `VersionAdvanceRun`、`VersionAdvanceRunWait: true` |
| 全局 | PCL 安装目录的 `config.v1.yml` | `LaunchAdvanceRun`、`LaunchAdvanceRunWait: true` |

PCL 执行命令时的工作目录是 `.minecraft` 根目录；版本隔离时 `mods/` 在实例目录里，`--game-dir` 必须显式写实例目录。

没有启动前命令的启动器改用 `-javaagent`（版本设置 → 高级 → JVM 参数）：

```
-javaagent:/绝对路径/modsync-agent.jar=--manifest-url=http://host:25590/modsync/manifest.json
```

agent 自己的参数写在 `=` 之后、用逗号分隔。

### 手动

```bash
java -jar modsync-agent.jar --game-dir /path/to/.minecraft \
  --manifest-url http://host:25590/modsync/manifest.json
```

参数：`--dry-run`、`--strict`、`--force-sync`、`--verbose`、`--parallel N`、`--warn-hold N`、`--abort-on-mismatch`、`--no-version-check`、`--client-mc-version`、`--client-loader-version`、`--print-game-dir`、`--help`。

### 配置文件

`<gameDir>/modsync/config.json`，首次运行生成模板；清单地址也可用 `--manifest-url`、`-Dmodsync.manifestUrl=`、`MODSYNC_MANIFEST_URL` 指定。字段见 [docs/deploy.md](docs/deploy.md#24-客户端配置)。

## 同步行为

| 情况 | 结果 |
| --- | --- |
| 本地文件 sha256 与清单一致 | 不下载、不重写 |
| 文件缺失或哈希不符 | 下载到 `modsync/tmp/`，校验 sha256 后原子改名 |
| 服务器删除模组 | 从 `mods-server/` 与 `mods/` 移除，旧文件进 `modsync/trash/` |
| 同 mod id 换了文件名 | 视为更新，先删旧再放新 |
| 服务器换了 `serverId` | 视为换服，全量重同步 |
| 清单未变化 | HTTP 304 快速路径 |
| `mods/` 里有服务器没有、mod id 也不冲突的 jar | 玩家自己的模组，不触碰 |
| `mods/` 里有 mod id 与服务器冲突的 jar | 服务器版本优先，玩家那份移入 `modsync/archive/` |
| 清单拉取失败 / JSON 损坏 / `files` 为空 / 无本地清单 | 本轮不做改动，照常启动 |
| 服务器未开机 | 沿用上次结果继续启动；`--strict` 可改为中止 |
| 加载器版本不一致 | `loaderMismatch`：`warn`（默认）/ `abort` / `ignore` |
| Minecraft 版本不一致 | `minecraftMismatch`：`skip`（默认，跳过本次同步）/ `warn` / `abort` |

## 目录结构

客户端：

```
.minecraft/
├── mods/           生效目录：服务器模组 + 玩家自装模组
├── mods-server/    服务端模组镜像（agent 私有，勿手动维护）
└── modsync/
    ├── config.json
    ├── state.json            serverId / ETag / 清单指纹 / 已部署文件
    ├── last-manifest.json    上次成功应用的清单
    ├── manifest-history/     历史清单（最多 5 份）
    ├── archive/              mod id 冲突时被顶掉的那一份
    ├── trash/                被替换或删除的旧文件（最近 10 批）
    ├── tmp/                  下载临时目录
    └── removal-log.jsonl     增删改审计
```

仓库：

```
mc-modsync/
├── build.sh / build-mod.sh
├── docs/                      deploy.md、protocol.md、design.md
├── src/io/modsync/common/     Json、Args、ModMeta（哈希 / modId / FMLModType）、ModEnv（侧向判定）
├── src/io/modsync/server/     SyncServer、ServerMain
├── src/io/modsync/agent/      Agent（premain / main）、Syncer、Manifest、State、Progress
├── src/io/modsync/neoforge/   NeoForge 服务端模组外壳
└── test/                      端到端测试
```

## 测试

```bash
bash test/run-all.sh
# 169 项：agent 46 + 版本比对 19 + 发布范围/过滤 27 + 同步 54 + 全栈 23
MODSYNC_TEST_ONLINE=1 bash test/run-all.sh   # 额外跑一条需要外网的 Modrinth 用例
```

需要 JDK 21、`python3`、`curl`、`jar`。

## 安全

- 同步内容会被写进客户端 `mods/` 并执行，只对可信服务器启用。
- 建议给 25590 挂 HTTPS 反向代理；下载接口按 sha256 查表，非法哈希 400，未知哈希 404。
- 客户端所有下载校验 sha256；替换与删除先进回收站。

## 已知限制

- 只同步 `mods/*.jar`；配置、资源包、光影未支持。
- 服务端清单只在启动时生成，改模组后需重启服务端。
- 一个实例只对应一个服务端；换服触发全量重同步。
- GUI 进度窗需要桌面 JVM，headless 环境退回控制台。
- 版本检查只提示，不修改启动器配置。
- 判不出侧向的模组一律发布；用 `excludes` / `forcePublish` 调整。

## 许可证

未指定。
