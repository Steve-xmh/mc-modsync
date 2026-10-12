# ModSync 部署手册

面向「一台 NeoForge 服务端 + 一群玩家」的部署步骤与排查表。

## 0. 组件与端口

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| `modsync-neoforge-<ver>.jar` | 服务端 `mods/` | 推荐形态。服务端自己发布，单端口，无额外进程，无需登记目录 |
| `modsync-server.jar` | 服务端任意目录 | 独立进程形态；用于非 NeoForge 或不便改 `mods/` 的场景 |
| `modsync-agent.jar` | 玩家实例内 | 启动前同步；`-javaagent` 与可执行 jar 两用 |

端口：25590/TCP（默认，可在配置里改）。

## 1. 服务端

### 1.1 方式 A：NeoForge 模组

```bash
./build.sh
./build-mod.sh                        # 产出 build/modsync-neoforge-<ver>.jar
./build-mod.sh --neo-libs <dir>       # 或显式指定 NeoForge 依赖来源
```

把 jar 放进服务端 `mods/`，重启。首次启动生成 `config/modsync-server.json`：

```json
{
  "enabled": true,
  "publishLoadedMods": true,           // 发布 FML 实际加载的 jar
  "modDirs": [],                       // 额外发布目录；只有服务器不加载的 jar 才需要填
  "extraDirs": ["modsync/extra"],       // 客户端专用模组的投放目录（自动创建）
  "excludes": ["modsync-neoforge-*.jar", "modsync-server*.jar"],
  "port": 25590,
  "bind": "0.0.0.0",
  "baseUrl": "",                       // 留空按请求 Host 生成；有域名/反代时写死
  "packName": "",                      // 留空取主机名
  "serverId": "",                      // 留空生成并持久化到 config/modsync-server-state.json
  "modrinthLookup": false,             // true：按 sha1 查 Modrinth，命中后客户端走 CDN 直链
  "filterServerOnlyMods": true,
  "forcePublish": []                   // 即使被判为纯服务端也发布的文件名 glob
}
```

**发布范围**

| 服务器上的情况 | 结果 |
| --- | --- |
| FML 加载的模组（不限目录） | 发布 |
| FML 以 `FMLModType: LIBRARY` 加载的库（KotlinForForge 等，位于 `mods/`） | 发布；读 `MANIFEST.MF` 识别 |
| jar-in-jar 内嵌件 | 不发布；它是父 jar 内部路径，磁盘上没有这个文件 |
| 加载器自身（`minecraft` / `neoforge`）、`libraries/` 下的产物 | 不发布 |
| ModSync 自己 | 不发布（改文件名也一样） |
| 客户端专用、服务器不加载的模组（Sodium / 光影 / 小地图） | 不发布；放进 `modsync/extra/` 或 `modDirs` 才发布 |
| 判定为纯服务端的模组 | 不发布；`forcePublish` 可覆盖 |
| `mods/` 里有但服务器未加载的 jar | 不发布 |

自检：

```bash
curl http://<服务器IP>:25590/modsync/health
curl http://<服务器IP>:25590/modsync/manifest.json | head -40
```

要点：

- 发布器不发布自身 jar：模组形态在启动时反射取自身文件名加进排除（日志 `not publishing our own mod jar: ...`），独立进程形态按运行位置排除（`not publishing our own jar: ...`）；默认 glob 只是兜底。
- 客户端专用模组放 `modsync/extra/`。这类模组要能进服，需声明 `displayTest="IGNORE_SERVER_VERSION"` 或 `@Mod(dist = Dist.CLIENT)`。
- `"publishLoadedMods": false` 退回扫描目录模式，此时 `modDirs` 为空会退回 `["mods"]`。
- 清单里的路径固定为 `mods/<文件名>`，与实际存放位置无关；放 `modsync/extra/` 的 jar 在客户端看起来和 `mods/` 里的一样。
- 开 `modrinthLookup` 后命中 Modrinth 的文件让客户端走 CDN，省服务器上行带宽。

### 1.2 方式 B：独立进程

```bash
java -jar modsync-server.jar \
  --mods ./mods --extra ./modsync/extra \
  --mod-file /srv/shared/special.jar \   # 精确发布单个文件，可重复
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

内存占用很小（按需读文件），`-Xmx256M` 足够。

### 1.3 方式 C：静态托管

```bash
java -jar modsync-server.jar --mods ./mods --extra ./modsync/extra \
  --base-url https://cdn.example.com/modsync \
  --minecraft-version 1.21.1 --loader-version 21.1.248 \
  --once --write-manifest out/manifest.json
```

把 `out/manifest.json` 与清单引用的文件一起上传；客户端只发 HTTP GET，任何静态托管都能用。代价是没有按玩家鉴权。

### 1.4 纯服务端模组过滤

判定依据，按可靠性排序：

| 来源 | 依据 |
| --- | --- |
| Fabric 元数据 | `fabric.mod.json` 的 `environment: "server"` |
| NeoForge 注解 | `@Mod(dist = { Dist.DEDICATED_SERVER })`，解析 class 文件的 `RuntimeVisibleAnnotations`，不做字符串匹配 |
| Modrinth 元数据 | 项目 `server_side=required` 且 `client_side=unsupported`，仅 `modrinthLookup` 开启时使用 |
| 无法判定 | 照常发布 |

- 被跳过的文件在日志里给出原因：`[modsync-server] skipping server-only mod (clients do not need it): xxx.jar [from mod metadata]`。
- 判为双端的一律发布：不写 `dist`、显式写 `{CLIENT, DEDICATED_SERVER}` 都算双端。
- 误判时用 `forcePublish` / `--force-publish <glob>` 放行；要完全关闭用 `filterServerOnlyMods: false` / `--no-filter-server-only`。
- 客户端自愈：以前同步过、后来被过滤掉的模组，下次启动会从 `mods-server/` 与 `mods/` 移除并进 `modsync/trash/`。

## 2. 客户端

### 2.1 Prism Launcher / MultiMC

实例 → **Edit → Settings → Custom Commands** → 勾选 → **Pre-launch command**：

```
"$INST_JAVA" -jar "/绝对路径/modsync-agent.jar" --manifest-url http://play.example.com:25590/modsync/manifest.json --game-dir "$INST_MC_DIR"
```

- 启动游戏前执行，退出码非 0 会中断启动。
- `$INST_JAVA`、`$INST_MC_DIR` 由 Prism 替换，路径含空格也可以。
- 进度窗口由 agent 显示（无显示环境退回控制台）。

### 2.2 PCL2 / PCL2 CE

版本设置 → 高级 → **启动前执行命令**（设置键 `VersionAdvanceRun`）：

```
"<启动器用的那个 java.exe>" -jar "<绝对路径>\modsync-agent.jar" --game-dir "<实例目录>"
```

同时写入等待开关（界面中隐藏，不写则 PCL 不等命令结束就启动游戏）：

| 范围 | 配置文件 | 键 |
| --- | --- | --- |
| 单实例 | `<实例目录>\PCL\config.v1.yml` | `VersionAdvanceRun`、`VersionAdvanceRunWait: true` |
| 全局 | PCL 安装目录的 `config.v1.yml` | `LaunchAdvanceRun`、`LaunchAdvanceRunWait: true` |

PCL 执行命令的工作目录是 `.minecraft` 根目录；版本隔离时 `mods/` 在实例目录里，`--game-dir` 必须显式指定。

没有启动前命令的启动器用 `-javaagent`：版本设置 → 高级 → JVM 参数追加

```
-javaagent:/绝对路径/modsync-agent.jar=--manifest-url=http://play.example.com:25590/modsync/manifest.json
```

agent 参数写在 `=` 之后、用逗号分隔。若启动器工作目录不是 `.minecraft`，再加 `-Dmodsync.gameDir=/绝对路径/.minecraft`。

### 2.3 手动 / 其他启动器

```bash
java -jar modsync-agent.jar --game-dir /path/to/.minecraft \
  --manifest-url http://play.example.com:25590/modsync/manifest.json
```

### 2.4 客户端配置

首次运行在 `<gameDir>/modsync/config.json` 生成模板。

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `manifestUrl` | 空 | 清单地址，必填。也可用 `--manifest-url`、`-Dmodsync.manifestUrl=`、环境变量 `MODSYNC_MANIFEST_URL` |
| `serverDir` | `mods-server` | 服务端模组镜像目录 |
| `targetDir` | `mods` | 玩家生效目录 |
| `strict` | `false` | `true` = 同步失败即中止启动 |
| `forceSync` | `false` | `true` = 忽略清单时间戳检查（服务器回滚清单时用） |
| `loaderMismatch` | `warn` | 加载器版本不一致：`warn` / `abort` / `ignore` |
| `minecraftMismatch` | `skip` | MC 版本不一致：`skip`（跳过同步）/ `warn` / `abort` |
| `warnHoldSeconds` | `8` | 版本不匹配提示窗停留秒数，`0` = 不弹窗 |
| `clientMinecraftVersion` / `clientLoaderVersion` | 空 | 覆盖自动探测（探测不到时用） |
| `parallelDownloads` | `4` | 并行下载数 |
| `timeoutSeconds` | `30` | 网络超时 |

其他参数：`--timeout`、`--parallel`、`--server-dir`、`--dry-run`、`--verbose`、`--warn-hold N`、`--abort-on-mismatch`、`--no-version-check`、`--print-game-dir`、`--help`。

## 3. 版本比对

清单带服务端的 `minecraft` 与 `loader` 版本；agent 在启动前比对：

- 本地版本读取顺序：`clientMinecraftVersion`/`clientLoaderVersion` → Prism/MultiMC 的 `mmc-pack.json` → `versions/<name>/<name>.json`。
- 加载器版本不同（默认 `warn`）：控制台横幅 + 提示窗停留 8 秒，写明在启动器里的修改路径，之后继续同步并启动。
- Minecraft 版本不同（默认 `skip`）：跳过本次同步并提示换成服务端版本，仍放行启动。
- 清单未声明版本：不做检查。

## 4. 上线检查清单

- [ ] 服务端 `mods/` 里没有不该同步出去的私有模组（纯服务端模组自动跳过，其余用 `excludes`）
- [ ] 检查一次启动日志里的 `skipping server-only mod` 列表，确认无误伤（有就用 `forcePublish`）
- [ ] `modsync-neoforge-*.jar` 在 `excludes` 里（默认已含）
- [ ] 防火墙放行 25590/TCP，`curl http://<IP>:25590/modsync/health` 通
- [ ] `baseUrl` 用玩家可访问的地址，必要时挂 HTTPS 反代
- [ ] 每个玩家的 agent `manifestUrl` 指向同一地址
- [ ] 灰度：先用一台客户端跑 `--dry-run` 看计划，再正式启用
- [ ] 首次启用前备份一次 `mods/`

## 5. 日常运维

| 操作 | 做法 |
| --- | --- |
| 加 / 删 / 升级模组 | 改服务端 `mods/` 或 `modsync/extra/`，重启服务端 |
| 玩家端生效时间 | 下次启动客户端时同步 |
| 查看下载请求 | 服务端 stdout 每行一条 `[modsync-server] 200 GET /modsync/files/... 3ms` |
| 回滚某次变更 | 服务端换回旧 jar 重启；客户端旧文件在 `modsync/trash/` |
| 某模组不想同步 | 加进 `excludes`，或从发布目录移走 |
| 换服务器地址 | 客户端改 `manifestUrl`；`serverId` 不变则不重下 |

## 6. 安全与网络

- 同步内容是可执行 jar：只对可信服务器启用。
- 建议用 HTTPS 反向代理（Nginx/Caddy）指向 25590，客户端用 https 的 `manifestUrl`。
- 下载接口按 64 位 sha256 查表，非法哈希 400，未知哈希 404，路径不可穿越。
- 下载一律校验 sha256；替换与删除先进回收站；玩家自装文件不删。
- 需要白名单时用反代做 IP / 账号限制；本工具不带鉴权与配额。

## 7. 排查

| 现象 | 原因 / 处理 |
| --- | --- |
| 客户端缺某个模组 | 服务器没加载它（看服务端启动日志），把它放进 `modsync/extra/` 或写进 `modDirs`；被过滤时日志有 `skipping server-only mod` |
| 客户端报 `needs language provider xxx` 后崩溃 | 该库以 `FMLModType: LIBRARY` 加载（KotlinForForge 等），会随清单发布；若它不在服务端 `mods/` 或关掉了 `publishLoadedMods`，就放进 `modsync/extra/`。这类崩溃常表现为另一个模组读配置抛异常（FML 进入 broken mod state 后配置未加载），先看崩溃报告前几行的 `needs language provider` |
| 换端口 | 改 `config/modsync-server.json` 的 `port` 后重启，并放行新端口，客户端 `manifestUrl` 同步改 |
| 日志出现 `BindException` | 25590 被占用；改 `port` 或停掉占用进程。不影响 Minecraft 服务端运行 |
| 每次启动都重下全部文件 | 服务端文件内容变了；或客户端 `state.json` 被删；或 `serverId` 变了（换目录 / 删状态文件） |
| 玩家进服被拒（模组不匹配） | 客户端多或少「服务端要求」的模组：把该模组放进 `modsync/extra/`（需 `displayTest=IGNORE_SERVER_VERSION`），或用 `excludes` 从发布集合排除 |
| 提示 NeoForge 版本不一致 | 让玩家按提示在启动器里升级，改完再启动 |
| `trash/` 越来越大 | 只在内容真的变化或被移除时入回收站，保留最近 10 批；旧的可手动删 |
| Modrinth 直链回填失败 | 服务器到 `api.modrinth.com` 不通。只告警一次，不影响同步，客户端改走服务器下载 |
| 客户端报清单不可达 | `manifestUrl` 不可达（防火墙 / 端口 / `baseUrl` 用了内网地址）；非 strict 模式下沿用上次结果继续启动 |
| NeoForge 安装器卡住 | 官方安装器的连接超时硬编码为 5 秒（`DownloadUtils` 里两处 `sipush 5000`），网络慢或经代理时必然 `Read timed out`。`build-mod.sh` 不需要 Gradle，就是为了绕开这条链 |

## 8. 限制

- 只同步 `mods/*.jar`；资源包、光影、`config/` 未支持。
- 服务端清单在启动时生成一次，不热重载。
- 客户端只记录一个 `serverId`；换服会全量重同步。
- 没有内置鉴权 / 配额。
