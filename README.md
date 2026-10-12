# ModSync

[![CI](https://github.com/Steve-xmh/mc-modsync/actions/workflows/ci.yml/badge.svg)](https://github.com/Steve-xmh/mc-modsync/actions/workflows/ci.yml)
[![License: 未指定](https://img.shields.io/badge/license-未指定-lightgrey)](#许可证)

给 Minecraft **NeoForge 1.21.1** 服务器用的一套模组同步方案：**服务器公开一个端口提供摘要清单和文件下载，客户端在游戏启动前完成校验与增量下载，然后以**增量对账**的方式把 `mods/` 对齐到服务器状态再进游戏。**

针对现有方案（AutoModpack 等）的差异点：

- **真正的启动前同步**：同步发生在 Minecraft 主类加载之前（`-javaagent` 或 Prism 预启动命令），不存在「进服务器才下载、下载完还得重启」的循环。
- **玩家自装模组是一等公民**：你自己的模组直接放在 `mods/` 里，只要 mod id 不与服务器冲突就**永不被 agent 触碰**；冲突时服务器版本优先，你那份移入 `modsync/archive/`，随时可以手动放回。
- **服务端只需要装一个模组**：模组启动时直接读取 FML **实际加载**的模组文件作为发布源（`publishLoadedMods`），完全不依赖目录布局；端口、排除规则等都在 `config/modsync-server.json` 里改。
- **自动过滤纯服务端模组**：性能/管理类的服务端专属模组不会推给玩家（识别 Fabric `environment: server`、NeoForge `@Mod(dist=DEDICATED_SERVER)`——真解析 class 注解，不会把「代码里引用了 Dist 的双端模组」误判）。
- **发布的是「FML 实际加载的 jar」**：`mods/` 里声明了 `FMLModType: LIBRARY` 的库（KotlinForForge 等）也会发布——它们不是 mod，FML 的任何 mod 列表里都没有它们，却常是客户端能不能启动的关键；反过来，jar-in-jar 内嵌件永不单独发布（那只是父 jar 内部的虚拟路径，服务器磁盘上并没有这个文件）。
- **发布器永远不会把自己发给玩家**：模组形态与独立进程形态都在运行时排除自身 jar（**改了文件名字也不会漏**），默认 glob 只是兜底。
- **服务器升级会提醒玩家**：清单带上服务器的 Minecraft / 加载器版本，客户端启动前比对并给出「去启动器把 NeoForge 改成 X」的具体路径，可配置为仅提醒或直接中止启动。
- **零第三方依赖**：两个 jar 只用 JDK 21（`java.net.http`、`com.sun.httpserver`、`java.util.zip`），没有 Gradle/Netty/Guava；服务端既可以做成 NeoForge 模组（`java -jar` 都不用），也可以是独立进程。
- **可审计**：清单是纯 JSON，替换与删除全部先入 `modsync/trash/`，`--dry-run` 可预览任何改动。

## 目录模型

```
.minecraft/
├── mods/           你（和启动器）真正使用的目录：服务器模组 + 你自己的模组
├── mods-server/    账本：与服务器清单一致（agent 私有，请勿手动改动）
└── modsync/
    ├── config.json          客户端配置（清单地址等）
    ├── state.json           同步状态（serverId / ETag / 清单指纹 / 已部署文件 + sha256 + mtime）
    ├── last-manifest.json   上次成功应用的服务器清单（交叉比对与时间戳判定用）
    ├── manifest-history/    历史清单（只留 JSON，最多 5 份）
    ├── archive/             因 mod id 冲突被你顶掉的服务器版本、或你的版本被服务器顶掉时的备份
    ├── trash/               服务器模组旧版本/删除件的短期回收（最近 10 批）
    └── removal-log.jsonl    每次增删改的审计记录
```

**对账规则**（agent 只在「服务器清单」与「`mods-server/` 账本」之间算差异，再把同一串增删改作用到 `mods/`）：

| 情况 | 结果 |
| --- | --- |
| 清单里有、账本里没有 | 下载并放进 `mods/` |
| 同路径但 sha 变了 | 更新（= 删旧 + 加新），`mods/` 里换成新版本 |
| 账本里有、新清单里没有 | 服务器删除了它 → 从账本与 `mods/` 同时移除（旧版进 `trash/`） |
| 同 mod id 但换了文件名 | 视为更新，先清除旧的再放新的，不会出现两个同 id 的 jar |
| 清单里有、`mods/` 里已有内容一致的同名文件 | **沿用**它填账本：不重复下载、不归档、不重写 |
| `mods/` 里有、账本里没有、mod id 也不冲突 | **这是你自己的模组，永远不碰** |
| `mods/` 里有、mod id 与服务器冲突 | 服务器版本优先，你那份移入 `modsync/archive/` |
| 清单不可信（拉取失败 / JSON 损坏 / `files` 为空 / 时间戳倒退 / 无本地清单） | **本轮完全不动**，照常启动 |

> 关键点：**`mods/` 里"多出来的文件"永远不会被当成服务器删除的模组** —— 删除只能来自账本差集。

## 快速开始

### 1. 构建（只需 JDK 21）

```bash
./build.sh
# build/modsync-server.jar   服务端（独立进程）
# build/modsync-agent.jar    客户端（同时是 -javaagent 和可执行 jar）
./build-mod.sh                # 可选：NeoForge 服务端模组（对着本地 NeoForge 依赖编译，不需要 Gradle）
# build/modsync-neoforge-1.2.2.jar
```

### 2. 服务端

#### 方式 A：NeoForge 模组（推荐：装完就完事）

把 `modsync-neoforge-1.2.2.jar` 丢进服务器 `mods/`，重启服务器即可 —— 不用起第二个进程，也不用登记任何目录。
模组启动时会问 FML **这台服务器实际加载了哪些模组文件**，直接把那些 jar 当作发布源：

```jsonc
// config/modsync-server.json（首次启动自动生成）
{
  "enabled": true,
  "publishLoadedMods": true,       // 发布 FML 实际加载的模组（推荐）
  "modDirs": [],                   // 额外发布的目录：只有服务器不加载的 jar 才需要填
  "extraDirs": ["modsync/extra"],  // 只给客户端、服务端不加载的模组放这里（自动创建）
  "port": 25590,                   // 想换端口改这里
  "bind": "0.0.0.0"
  // ...其余见 docs/deploy.md
}
```

意义在于：**服务器跑什么就发布什么** —— 模组放在 `mods/`、子目录还是别的加载器目录都无所谓，既不会漏，
也不会把服务器没在运行的 jar 推给玩家。加载器自身（`minecraft`/`neoforge`）与 ModSync 自己会自动排除。

```
[modsync] publishing 27 loaded jar(s): 26 mod(s) + 1 FML library jar(s); ignored 45 jar-in-jar/loader entry(ies)
[modsync] publishing 26 files (412.3 MB) at http://...:25590/modsync
[modsync-server] skipping server-only mod (clients do not need it): Chunky-1.4.36.jar [from mod metadata]
```

> 客户端专用、服务端不加载的模组（Sodium / 光影 / 小地图等）放进 `modsync/extra/`，或写进 `modDirs`。

#### 方式 B：独立进程（不方便改 `mods/`，或想静态托管时）

```bash
java -jar modsync-server.jar \
  --mods ./mods \                 # 发布目录（默认 ./mods）
  --extra ./modsync/extra \       # 额外发布目录：客户端专用模组放这里（可重复）
  --mod-file /srv/shared/x.jar \  # 直接发布某个文件，优先级高于目录（可重复）
  --exclude 'secret-*.jar' \      # 不发布的文件名 glob（可重复）
  --port 25590 --bind 0.0.0.0 \
  --base-url http://play.example.com:25590/modsync \
  --pack-name "我的整合包"
```

防火墙只需放行这一个端口。启动后会打印：

```
[modsync-server] published 27 files, 128.4 MB, pack=我的整合包, id=1f3c..., listening on http://0.0.0.0:25590/modsync
```

`--modrinth-lookup` 会按 sha1 查询 Modrinth 并把官方直链写进清单，客户端优先走 CDN，省服务器带宽且作者能拿到下载量。

> 只想要清单、不想跑常驻进程？`--once --write-manifest manifest.json` 生成清单，连同 `files/` 目录丢到任意静态托管（GitHub Pages / Netlify / 对象存储）也能用：agent 只需要 HTTP GET。

### 3. 客户端

`<gameDir>/modsync/config.json`（首次运行会自动生成模板）：

```json
{
  "manifestUrl": "http://play.example.com:25566/modsync/manifest.json",
  "serverDir": "mods-server",
  "targetDir": "mods",
  "strict": false,
  "forceSync": false,        // true：忽略清单时间戳的顺序护栏
  "forceRebuild": false,
  "timeoutSeconds": 30,
  "parallelDownloads": 4
}
```

#### Prism Launcher / MultiMC（推荐：独立 JVM 预启动，改动最小）

实例 → Settings → Custom Commands → 勾选 **Custom Commands**，在 **Pre-launch command** 填：

```
"$INST_JAVA" -jar "/绝对路径/modsync-agent.jar" --game-dir "$INST_MC_DIR"
```

Prism 会在启动游戏前运行这条命令；成功（退出码 0）才继续启动。进度窗口与确认弹窗由 agent 自己显示（无显示环境自动退回控制台），没有关闭开关。
`$INST_JAVA` / `$INST_MC_DIR` 由 Prism 自动替换。

#### PCL2（Plain Craft Launcher）

1. 把 `modsync-agent.jar` 放到实例的 `.minecraft/modsync/` 下（放这里没有空格，路径最省事）；
2. 版本设置 → 高级 → **JVM 参数**，追加：

```
-javaagent:/绝对路径/modsync-agent.jar=--manifest-url=http://<服务器IP>:25590/modsync/manifest.json
  # agent 自己的参数必须写在 "=" 之后、用逗号分隔；写成空格分隔的独立 --xxx 会让 JVM 拒绝启动
```

3. 若你的启动器不是以 `.minecraft` 为工作目录，再补一个系统属性：

```
-Dmodsync.gameDir=/绝对路径/.minecraft
```

`-javaagent` 的 `premain` 在 Minecraft 主类之前执行，所以模组在加载前就已经就位；同步失败默认只告警并继续启动（想改成失败即中止：`--strict` 或配置里 `"strict": true`）。

#### 手动 / 其他启动器

```bash
java -jar modsync-agent.jar --game-dir /path/to/.minecraft --manifest-url http://host:25566/modsync/manifest.json
```

常用参数：`--dry-run`（只预览不改动）、`--strict`、`--parallel N`、`--verbose`、`--print-game-dir`、`--help`。

## 同步行为细则

| 场景 | 行为 |
| --- | --- |
| 本地文件 sha256 与清单一致 | **不重写、不下载**（mtime 保持不变） |
| 文件中缺失 / 哈希不符 | 下载到 `.part` 校验后原子改名进 `mods-server/` |
| 清单提供 `url` 直链 | 优先走直链，仍以清单 sha256 校验 |
| 服务器删除了模组 | 从 `mods-server/` 与 `mods/` 同时移除，旧版进 `modsync/trash/` |
| 服务器换了 serverId | 视为换服，清空同步记录做全量重同步 |
| 清单未变化 | HTTP 304 快速路径，跳过下载阶段 |
| 清单、账本、`mods/` 三者一致 | 什么都不做（只做 stat 级校验，不重算全量哈希） |
| `mods/` 里出现非 agent 部署的 jar | 视为玩家自己的模组，不触碰（mod id 冲突时才归档） |
| 客户端模组与服务器 mod id 相同 | 服务器版本优先，玩家那份移入 `modsync/archive/`（可手动恢复） |
| 玩家点了「跳过同步」 | 保留当前 `mods/` 直接启动 |
| 服务器未开机 / 断网 | 默认沿用上次结果继续启动（`--strict` 可改为中止） |
| 服务器 NeoForge 版本与本地不同 | 启动前大字提示 + 弹窗写明升级路径，默认仍继续同步（`loaderMismatch: abort` 可改为中止启动） |
| 服务器 Minecraft 版本与本地不同 | 默认**跳过同步**并提示换成对应版本（跨版本模组会崩），仍放行启动 |
| 服务器模组被判定为纯服务端 | 根本不发布给客户端（Fabric `environment` / NeoForge `@Mod(dist)` / Modrinth 元数据，见 deploy.md 1.4） |
| jar 在 `mods/` 里但服务器没加载它 | 模组形态下不会发布（发布源是「已加载」而非「目录里有」）；要强行发布就放进 `modsync/extra/` 或 `modDirs` |
| 模组把别的模组打包在自己 jar 里（jar-in-jar） | 内嵌的那份**不单独发布**（它只存在于父 jar 内部，服务器磁盘上并没有这个文件）；客户端由父 jar 自己带进去 |
| `mods/` 里声明 `FMLModType: LIBRARY` 的库（如 KotlinForForge） | **发布**：它不是 mod（没有 `mods.toml`，既不在 `ModList.getMods()` 也不在 `getModFiles()` 里），发布器读 `MANIFEST.MF` 把它找出来。客户端常常离了它就无法加载 |

## 端到端验证

```bash
bash test/run-all.sh
# agent 46 + 版本比对 19 + 发布范围/过滤 27 + 对账 54 + 全栈（真服务端 + 真 agent）23 = 169 项

MODSYNC_TEST_ONLINE=1 bash test/run-all.sh   # 额外跑一条需要外网的 Modrinth 直链回填用例
```

覆盖：首次同步（无账本只增不删、已有一致文件则沿用不重下）、幂等不重写（mtime 不变）、`--dry-run`、玩家自装模组永不被触碰、同 id 冲突进归档、
**玩家删掉客户端模组不再复活**、玩家删服务器模组被补回、服务器增删改、改名不重复下载、时间戳护栏、空/坏清单完全不动、
服务器增删改的增量下载、本地篡改（同大小不同内容）检测、ETag/304、404/400、`-javaagent` 入口、
`--once` 清单结构、`--exclude` 与 `--extra`、加载器/MC 版本比对（含 Prism `mmc-pack.json` 自动探测）、
纯服务端模组过滤（Fabric 环境字段、NeoForge `dist` 注解的四种形态、`force-publish` 覆盖）、
发布范围（`--mod-file` 精确指定、同一文件同时来自目录与显式指定时只算一次且不误报重名、
jar-in-jar 内嵌件不会被当成独立文件发布、`FMLModType: LIBRARY` 的库仍会被发布）。

## 安全模型（重要）

和 AutoModpack 一样，这个方案允许服务器把 **可执行 jar** 推送到客户端 `mods/`，因此：

- 只对你完全信任的服务器启用；不要在公共/陌生服务器上开 `-javaagent` 自动同步；
- 部署时用反向代理上 HTTPS，并给玩家提供一个可信的清单地址；
- 服务端只读、无写接口；`files/{sha256}` 只暴露已发布内容，路径不可穿越；
- 客户端所有下载都校验 sha256，替换与删除先入回收站，`state.json` 记录了来源 `origin`。

## 已知边界与后续可做

- **服务端有两种形态**：NeoForge 模组（`build-mod.sh`，丢进 `mods/` 即可，无需 Gradle）或独立进程（`build.sh`），
  两者共用同一个 loader 无关的 `io.modsync.server.SyncServer`。
- **版本比对是"提醒"而非"自动升级"**：agent 只能读取本地实例的加载器版本并给出升级路径，不会替你改启动器配置（改启动器文件风险太高）。
- **单服务器**：一个实例只对应一个服务端；多服务器切换未实现（会给 `mods-server/` 换整套账本）。
- **GUI 只在 Windows/macOS/Linux 桌面 JVM 上可用**，headless 环境自动退回控制台进度条。
- 只管理 `mods/*.jar`；配置、资源包、光影等其它路径的同步在做清单 v2 时再扩。
- 服务端模组判定是「宁可多发、不可漏发」：判定不出侧向时一律发布，靠 `excludes` / `forcePublish` 兜底。

## 目录

```
mc-modsync/
├── build.sh                  构建 agent + 独立服务端 jar（javac + jar，无 Gradle）
├── build-mod.sh              构建 NeoForge 服务端模组（对着本地 NeoForge 依赖编译）
├── README.md / docs/deploy.md        说明与真实服务器部署手册
├── docs/protocol.md          manifest/HTTP 协议规范（v1）
├── src/io/modsync/common/    Json / Args / ModMeta（哈希、modId）/ ModEnv（服务端-客户端侧向判定）
├── src/io/modsync/server/    SyncServer（HTTP + 清单 + 过滤）+ ServerMain（CLI）
├── src/io/modsync/agent/     Agent（premain/main）/ Syncer / Manifest / State / ClientVersion / Progress(+Gui)
├── src/io/modsync/neoforge/  NeoForge 模组外壳（生命周期 + config/modsync-server.json）
└── test/                     make-mod.sh、gen-manifest.py、e2e-agent.sh、e2e-version.sh、
                              e2e-filter.sh、e2e-server.sh、run-all.sh
```

## 许可证

仓库当前**尚未指定开源许可证**；NeoForge 模组的 jar 元数据里暂记为 `LGPL-3.0-or-later`
（`build-mod.sh` 生成的 `neoforge.mods.toml`）。确定最终许可证（MIT / Apache-2.0 / LGPL-3.0 / …）后，
需要同时补 `LICENSE` 文件并把这行元数据改成一致的值。
