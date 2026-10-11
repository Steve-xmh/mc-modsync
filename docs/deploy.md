# ModSync 部署手册（真实服务器）

面向「一台 NeoForge 服务器 + 一群玩家」的落地步骤。目标是：玩家只装一个 agent 启动钩子，进服前自动对齐模组；玩家自己装的模组不被改动；服务器升级 NeoForge 时玩家收到明确提示。

## 0. 组件与端口

| 组件 | 在哪 | 说明 |
| --- | --- | --- |
| `modsync-neoforge-<ver>.jar` | 服务器 `mods/` | **推荐**：装上就完事。启动时自动读取 FML 已加载的模组并发布（单端口，无额外进程、无需配置目录） |
| `modsync-server.jar` | 服务器任意目录 | 等价替代：独立进程，适合非 NeoForge/不能改 mods 的场景 |
| `modsync-agent.jar` | 玩家实例内 | 预启动同步；同时是 `-javaagent` 和可执行 jar |

默认端口 **25590**（刻意避开 SCS 的 25566）。只这一个端口需要放行，走 TCP。

## 1. 服务端部署

### 1.1 方式 A：NeoForge 模组（推荐）

```bash
# 在有 JDK 21 的机器上构建（无需 Gradle，对着本地 NeoForge 依赖编译即可）
./build.sh
./build-mod.sh                        # 产出 build/modsync-neoforge-1.1.0.jar
./build-mod.sh --neo-libs /path/to/neoforge/libraries   # 或显式指定依赖来源
```

把 `build/modsync-neoforge-1.1.0.jar` 放进服务器 `mods/`，重启服务器即可 —— **不需要第二个进程，也不需要登记要发布哪些目录**。
模组启动时会向 FML 询问这台服务器**实际加载了哪些模组文件**，直接以那些 jar 作为发布源（`publishLoadedMods`）。
首次启动会生成 `config/modsync-server.json`：

```json
{
  "enabled": true,
  "publishLoadedMods": true,           // 发布 FML 实际加载的模组（推荐；服务器跑什么就发什么）
  "modDirs": [],                       // 额外发布目录（相对服务器根目录），可多个。
                                       // 只有"服务器不加载、但玩家需要"的 jar 才需要填这里
  "extraDirs": ["modsync/extra"],      // 同上，专门给客户端专用模组用的投放目录（自动创建）
  "excludes": ["modsync-neoforge-*.jar", "modsync-server*.jar"],
                                       // 不发布的文件名 glob（可重复）。模组启动时会自动把
                                       // 「自己这个 jar」也加进来，所以就算你改了 jar 名也不会被发出去
  "port": 25590,
  "bind": "0.0.0.0",
  "baseUrl": "",                       // 留空则按请求的 Host 头动态生成；有域名/反代时建议显式写死
  "packName": "",                      // 留空取主机名
  "serverId": "",                      // 留空则生成并持久化到 config/modsync-server-state.json
  "modrinthLookup": false,             // true：按 sha1 查 Modrinth，命中则让客户端走 CDN 直链
  "filterServerOnlyMods": true,        // 自动跳过纯服务端模组（见 1.4）
  "forcePublish": []                   // 即使被判定为服务端专属也要发布的文件名 glob
}
```

改完配置重启服务器。启动日志应出现：

```
[modsync] publishing the 27 mod jar(s) FML actually loaded (no directory scan needed)
[modsync] publishing 26 files (412.3 MB) at http://play.example.com:25590/modsync - clients sync from http://play.example.com:25590/modsync/manifest.json
[modsync-server] skipping server-only mod (clients do not need it): Chunky-1.4.36.jar [from mod metadata]
[modsync] announcing Minecraft 1.21.1 / NeoForge 21.1.248 so clients can be told to upgrade their loader when needed
```

**发布范围（谁会被发出去）**

| 情况 | 结果 |
| --- | --- |
| 服务器加载的模组（不论放在哪个目录） | 发布 |
| 客户端专用、服务器不加载的模组（Sodium / 光影 / 小地图…） | 默认**不发布**；放进 `modsync/extra/` 或写进 `modDirs` 才会发布 |
| `mods/` 里存在但服务器**没有加载**的 jar（被 FML 拒绝、放错地方） | 不发布。这正是"发布已加载"的用意：推给玩家的一定是服务器真在跑的东西 |
| 加载器自身（`minecraft` / `neoforge`）、jar-in-jar 嵌套的虚拟文件 | 不发布（自动） |
| ModSync 模组自己 | 不发布（自动，改名也一样） |
| 判定为纯服务端的模组 | 不发布（见 1.4，可用 `forcePublish` 覆盖） |

自检（服务器本机或外网）：

```bash
curl http://<服务器IP>:25590/modsync/health
curl http://<服务器IP>:25590/modsync/manifest.json | head -40
```

**要点**
- **发布器永不发布自己**：模组形态在启动时反射读出自身 jar 的文件名并加进排除（**改文件名也不会漏**，日志 `not publishing our own mod jar: ...`）；独立进程形态同样按运行位置排除（日志 `not publishing our own jar: ...`）。默认 glob `modsync-neoforge-*.jar` / `modsync-server*.jar` 只是兜底。
- 只想给客户端、服务端不加载的模组（Sodium/Iris/光影类）：放进 `modsync/extra/`，模组会自动创建这个目录。它们同样会被同步，但不会被服务端加载。注意这类模组若声明了 `displayTest="IGNORE_SERVER_VERSION"`（或 `@Mod(dist=CLIENT)`）时才允许玩家带着它进服。
- 两端都要装的模组 = 服务器加载的那批（自动发布）；只有客户端要装的 = `modsync/extra/` 或 `modDirs`。这个划分决定玩家能否顺利进服。
- 关掉自动识别（`"publishLoadedMods": false`）会退回「扫描目录」模式，此时 `modDirs` 为空会自动退回 `["mods"]`。
- `--modrinth-lookup`/`modrinthLookup` 打开后，命中 Modrinth 的文件会让客户端走 CDN，省服务器上行带宽，同时作者的下载量也记上了。

### 1.2 方式 B：独立进程

```bash
java -jar modsync-server.jar \
  --mods ./mods --extra ./modsync/extra \
  --mod-file /srv/shared/special.jar \   # 精确发布某个文件，优先级高于目录（可重复）
  --exclude 'secret-*.jar' \
  --port 25590 --bind 0.0.0.0 \
  --base-url http://play.example.com:25590/modsync \
  --pack-name "我的整合包" \
  --minecraft-version 1.21.1 --loader-version 21.1.248 \
  --state ./modsync-server-state.json
```

systemd 示例：

```ini
[Unit]
Description=ModSync publisher
After=network.target

[Service]
WorkingDirectory=/srv/minecraft
ExecStart=/usr/bin/java -Xmx256M -jar /srv/minecraft/modsync-server.jar --mods /srv/minecraft/mods --extra /srv/minecraft/modsync/extra --port 25590 --bind 0.0.0.0 --base-url http://play.example.com:25590/modsync --minecraft-version 1.21.1 --loader-version 21.1.248
Restart=always
User=minecraft

[Install]
WantedBy=multi-user.target
```

内存占用很小（只按需读文件），`-Xmx256M` 足够。

### 1.3 方式 C：静态托管（完全不起进程）

```bash
java -jar modsync-server.jar --mods ./mods --extra ./modsync/extra \
  --base-url https://cdn.example.com/modsync \
  --minecraft-version 1.21.1 --loader-version 21.1.248 \
  --once --write-manifest out/manifest.json
# 把 out/manifest.json 和它引用到的 out/... 一起上传（–-once 不产出文件副本时，
# 用 --write-manifest 配合本地文件服务器/对象存储同步整个 mods 目录亦可）
```

客户端只需要 HTTP GET，任何静态托管都能用；代价是没有按玩家鉴权。

### 1.4 自动过滤纯服务端模组

服务端常有一些**客户端完全不需要**的模组（性能优化、区块预生成、管理/反作弊工具）。把它们推给玩家只会浪费带宽，有时还会让客户端崩溃。发布器默认开启过滤，按可靠性依次判定：

| 判定来源 | 依据 | 说明 |
| --- | --- | --- |
| Fabric 元数据 | `fabric.mod.json` 的 `environment: "server"` | 显式声明，最可靠 |
| NeoForge / Forge 注解 | `@Mod(dist = { Dist.DEDICATED_SERVER })` | 直接解析 jar 里 class 文件的 `RuntimeVisibleAnnotations`，**不是**字符串匹配——所以「代码里引用了 `Dist.DEDICATED_SERVER` 的双端模组」不会被误判 |
| Modrinth 元数据 | 项目 `server_side=required` 且 `client_side=unsupported` | 只在开启 `modrinthLookup` 时作为兜底 |
| 无法判定 | 无以上信息 | **照常发布**（宁可多发，不可漏发） |

行为细节：
- 被跳过的文件会在日志里明确写出原因：`[modsync-server] skipping server-only mod (clients do not need it): xxx.jar [from mod metadata]`；
- 判定结果是「双端」的一律发布：不写 `dist`（默认就是两端）、显式写 `{CLIENT, DEDICATED_SERVER}` 都算双端；
- 误判时的逃生门：`forcePublish`（模组配置）/ `--force-publish <glob>`（独立进程）；- 想完全关闭：`filterServerOnlyMods: false` / `--no-filter-server-only`；
- 客户端侧自愈：某个模组以前同步过、后来被过滤掉，客户端下次启动会把它从 `mods-server/` 与 `mods/` 移除（进 `modsync/trash/`），不会长期残留。

## 2. 客户端部署

### 2.1 Prism Launcher / MultiMC（推荐）

实例 → **Edit → Settings → Custom Commands** → 勾选 → **Pre-launch command**：

```
"$INST_JAVA" -jar "/绝对路径/modsync-agent.jar" --manifest-url http://play.example.com:25590/modsync/manifest.json --game-dir "$INST_MC_DIR"
```

- Prism 会在启动游戏前执行；退出码非 0 会中断启动。
- `$INST_JAVA` / `$INST_MC_DIR` 由 Prism 替换，路径含空格也没问题。
- 只想平时不弹窗：加 `--no-gui`（默认会显示同步进度窗口，可随时点「跳过同步」）。

### 2.2 PCL2（Plain Craft Launcher 2）

版本设置 → 高级 → **JVM 参数**，追加：

```
-javaagent:/绝对路径/modsync-agent.jar=--manifest-url=http://play.example.com:25590/modsync/manifest.json --no-gui
```

`-javaagent` 的 `premain` 在 Minecraft 主类之前执行，模组在加载前就已就位。
若启动器的工作目录不是 `.minecraft`，再加一个系统属性：`-Dmodsync.gameDir=/绝对路径/.minecraft`。

### 2.3 手动 / 其他启动器

```bash
java -jar modsync-agent.jar --game-dir /path/to/.minecraft \
  --manifest-url http://play.example.com:25590/modsync/manifest.json
```

### 2.4 客户端配置

首次运行会在 `<gameDir>/modsync/config.json` 生成模板：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `manifestUrl` | 空 | 必填（也可用 `--manifest-url` / `-Dmodsync.manifestUrl=`） |
| `serverDir` / `clientDir` / `targetDir` | `mods-server` / `mods-client` / `mods` | 目录名；`targetDir` 是构建产物，玩家不要手动维护 |
| `gui` | true | 进度窗口；headless 自动降级 |
| `strict` | false | true = 同步失败时中止启动（默认只告警后继续） |
| `autoMigrateUnknownMods` | true | 误丢进 `mods/` 的 jar 迁移到 `mods-client/`（不删除） |
| `loaderMismatch` | `warn` | 加载器版本不一致：`warn` 提醒 / `abort` 中止启动 / `ignore` 忽略 |
| `minecraftMismatch` | `skip` | MC 版本不一致：`skip` 跳过同步 / `warn` 只提醒 / `abort` 中止 |
| `warnHoldSeconds` | 8 | 不匹配提示窗口停留秒数，0 = 不弹窗 |
| `clientMinecraftVersion` / `clientLoaderVersion` | 空 | 覆盖自动探测（探测不到时很有用） |
| `parallelDownloads` | 4 | 并行下载数 |
| `timeoutSeconds` | 30 | 网络超时 |

对应命令行开关：`--abort-on-mismatch`、`--no-version-check`、`--warn-hold N`、`--client-mc-version`、`--client-loader-version`、`--strict`、`--force`、`--dry-run`。

## 3. 服务器升级 NeoForge 时的行为

清单里带上了服务器的 `minecraft` 与 `loader` 版本（模组自动上报；独立进程用 `--minecraft-version` / `--loader-version` 指定）。客户端 agent 会在**启动前**比对：

- 读取顺序：`clientMinecraftVersion`/`clientLoaderVersion` 配置 → Prism/MultiMC 的 `mmc-pack.json` → `versions/<name>/<name>.json`（官方启动器 / PCL）。
- **加载器版本不同**（默认 `warn`）：控制台打出大边框提示 + 弹窗停留 8 秒，明确写出「Prism：实例 → Edit → Version → 把 neoforge 改成 21.1.248」，然后**继续同步并启动**（同一 21.1.x 内通常可兼容）。要强制对齐就设 `abort`。
- **Minecraft 版本不同**（默认 `skip`）：跨版本模组会导致崩溃，所以**跳过本次同步**并提示玩家把实例换成服务器的 MC 版本，但仍然放行启动（不会把玩家彻底挡在门外）。
- 服务器没声明版本（老清单/静态托管没写）→ 不做任何检查，行为与旧版一致。

## 4. 上线检查清单

- [ ] 服务器 `mods/` 里没有"服务端私有"模组会被同步出去（纯服务端模组按 1.4 自动跳过；其余用 `excludes` 或挪出目录）
- [ ] 检查一次启动日志里的 `skipping server-only mod` 列表，确认没有误伤（有就用 `forcePublish` 放行）
- [ ] `modsync-neoforge-*.jar` 在 excludes 里（默认已包含）
- [ ] 防火墙放行 25590/TCP；`curl http://<IP>:25590/modsync/health` 通
- [ ] `--minecraft-version` / `--loader-version`（或模组自动上报）正确，客户端能看到提示
- [ ] `baseUrl` 用玩家能访问到的地址（域名/公网 IP），必要时挂 HTTPS 反代
- [ ] 玩家侧 agent 的 `manifestUrl` 指向同一个地址
- [ ] 灰度：先用 `--dry-run` 在一台客户端看计划，再正式启用
- [ ] 备份：首次启用前备份一次 `mods/`（agent 只会重排，不会删源文件，但小心驶得万年船）

## 5. 日常运维

| 操作 | 做法 |
| --- | --- |
| 加/删/升级模组 | 改服务器 `mods/`（或 `modsync/extra/`）→ **重启服务器**（模组模式下清单在服务器启动时生成一次，不热重载） |
| 玩家端何时生效 | 下次启动客户端时自动增量同步 |
| 看谁在下载 | 模组/独立进程的 stdout，每个请求一行 `[modsync-server] 200 GET /modsync/files/... 3ms` |
| 回滚某次变更 | 服务器换回旧 jar 重启即可；客户端旧文件在 `modsync/trash/`（保留最近 10 批）里也能找回 |
| 某个模组不想同步 | 加进 `excludes`（服务器端），或从发布目录移走 |
| 换服务器地址 | 客户端改 `manifestUrl`；`serverId` 不变则不重下 |

## 6. 安全与网络

- 同步内容是**可执行 jar**：只对你信任的服务器启用；不要在陌生服务器上开自动同步（与 AutoModpack 同一信任模型）。
- 建议用 HTTPS 反向代理（Nginx/Caddy）指向 `:25590`，并在客户端用 https 的 `manifestUrl`。
- 目录穿越已防护：下载接口只按 64 位 sha256 查表，非法哈希返回 400，未知哈希 404。
- 下载一律校验 sha256；替换/删除先进回收站；玩家自装文件不删。
- 想做「白名单玩家才能下载」：模组本身不带鉴权，请用反代 + IP/帐号限制，或把清单/文件放到静态托管的私有路径。

## 7. 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| 客户端缺某个模组 | 服务器没加载它（看服务器启动日志有没有 FML 的报错/拒绝），把它放进 `modsync/extra/` 或写进 `modDirs`；纯服务端模组被过滤时日志会有 `skipping server-only mod` |
| 想换端口 | 改 `config/modsync-server.json` 的 `port` 后重启服务器，并同步放行新端口（客户端 `manifestUrl` 也要改） |
| 模组日志 `BindException` | 25590 被占用（如另一个 ModSync 进程）。改 `port` 或停掉占用者；模组会给出提示且不影响服务器运行 |
| 每次启动都重下全部文件 | 服务端文件在变（mtime 无关，看 sha256）；或客户端 `state.json` 被删；或 `serverId` 变了（换目录/删状态文件）→ 视为换服全量重同步 |
| 玩家进服被拒（模组不匹配界面） | 客户端多/少了"服务端要求"的模组：把该模组放到 `modsync/extra/`（客户端专用，需 `displayTest=IGNORE_SERVER_VERSION`）或从发布集合里用 `excludes` 排除 |
| 提示 NeoForge 版本不一致 | 让玩家按提示在启动器里升级；改完版本号后再启动即可（agent 会自动重判） |
| `trash/` 越来越大 | 现在只在"内容真的变了/被移除"时进回收站，且只保留最近 10 批；老版本升级后残留的可以手动删 |
| Modrinth 直链回填失败 | 服务器到 `api.modrinth.com` 不通（**本机实测：网络代理会让 Java 旧 HTTP 栈 5 秒超时**，独立进程已把连接超时放宽到 15 秒并只告警一次）。失败不影响同步，客户端改走服务器下载 |
| 玩家端 `无法访问清单` | `manifestUrl` 不可达（防火墙/端口/baseUrl 用了内网地址）；非 strict 模式下 agent 会沿用上次结果并照常启动 |
| 搭测试用 NeoForge 服务端时安装器卡住 | 官方安装器的连接/读取超时被**硬编码为 5 秒**（`DownloadUtils` 里两个 `sipush 5000`），在网络慢或经代理的环境必然 `Read timed out`。本次实测的做法：把 `install_profile.json` 的 `mirrorList` 清空、把两处 `sipush 5000` 改成 `sipush 30000` 后重打包安装器，再用 curl 补齐个别被掐断的依赖。`build-mod.sh` 不需要 Gradle，正是为了绕开这条链 |

## 8. 已知限制

- 只同步 `mods/*.jar`。资源包、光影、`config/` 尚未支持（协议 v2 计划）。
- 服务端清单在启动时生成一次，不热重载；改模组需要重启服务器。
- 客户端账本记录单个 `serverId`：多服务器自动切换（每服独立账本）未实现，切服会全量重同步一次。
- 没有内置鉴权/配额，也没有 AutoModpack 那样的"下载密钥"机制。
