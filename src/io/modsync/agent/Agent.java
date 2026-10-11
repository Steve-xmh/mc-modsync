package io.modsync.agent;

import io.modsync.common.Args;

import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point with two faces:
 *
 * <ul>
 *   <li>{@code -javaagent:modsync-agent.jar=...} on the game JVM (PCL, or any launcher whose JVM
 *       arguments can be edited): {@link #premain} runs the sync before Minecraft's main class.</li>
 *   <li>{@code java -jar modsync-agent.jar --sync} (Prism/MultiMC pre-launch command): {@link #main}
 *       runs the same sync in a throwaway JVM.</li>
 * </ul>
 */
public final class Agent {

    private Agent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        int code = run(Args.parse(agentArgs), true);
        if (code != 0) {
            System.err.println("[modsync] 预启动同步未成功完成 (code=" + code + ")");
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static void main(String[] argv) {
        Args args = Args.parse(argv);
        if (args.flag("help") || args.flag("h")) {
            usage(System.out);
            System.exit(0);
        }
        System.exit(run(args, false));
    }

    /**
     * @return process exit code: 0 = ok (or a soft failure in non-strict mode), 1 = strict failure,
     *         2 = configuration error
     */
    private static int run(Args args, boolean premain) {
        PrintStream log = System.out;
        try {
            Path gameDir = resolveGameDir(args);
            if (args.flag("print-game-dir")) {
                log.println(gameDir);
                return 0;
            }
            AgentConfig cfg = AgentConfig.load(gameDir, args, log);
            if (cfg.manifestUrl == null || cfg.manifestUrl.isBlank()) {
                log.println("[modsync] 未配置清单地址。请设置 modsync/config.json 的 manifestUrl，"
                        + "或用 --manifest-url <url> / -Dmodsync.manifestUrl=<url> 指定。");
                usage(log);
                return 2;
            }
            log.println("[modsync] 游戏目录: " + gameDir);
            log.println("[modsync] 清单地址: " + cfg.manifestUrl);

            Progress progress = Progress.create(cfg.gui, log, "ModSync — 正在同步服务器模组");
            try {
                Syncer syncer = new Syncer(cfg, gameDir, progress, log);
                syncer.run();
            } finally {
                progress.close();
            }
            return 0;
        } catch (Syncer.CancelledException cancelled) {
            log.println("[modsync] 已取消同步，继续使用当前 mods 目录");
            return 0;
        } catch (Syncer.MismatchAbortException abort) {
            log.println("[modsync] 版本不匹配且配置为 abort，已中止游戏启动：" + abort.getMessage());
            if (premain) {
                throw new RuntimeException("[modsync] 已中止游戏启动（版本不匹配）", abort);
            }
            return 3;
        } catch (Throwable t) {
            log.println("[modsync] 同步失败: " + t);
            if (args.flag("verbose")) {
                t.printStackTrace(log);
            }
            if (args.flag("strict")) {
                if (premain) {
                    throw new RuntimeException("[modsync] 严格模式：中止游戏启动", t);
                }
                return 1;
            }
            log.println("[modsync] 非严格模式：跳过本次同步，继续启动游戏");
            return 0;
        }
    }

    private static Path resolveGameDir(Args args) {
        String configured = args.get("game-dir");
        if (configured == null) {
            configured = System.getProperty("modsync.gameDir");
        }
        if (configured == null) {
            configured = System.getProperty("modsync.gamedir");
        }
        Path dir = (configured == null || configured.isBlank())
                ? Paths.get("").toAbsolutePath().normalize()
                : Paths.get(configured).toAbsolutePath().normalize();
        if (configured == null || configured.isBlank()) {
            // Launcher started from the parent directory: use .minecraft when that is where the config lives.
            if (!Files.isRegularFile(AgentConfig.configFile(dir))
                    && Files.isRegularFile(AgentConfig.configFile(dir.resolve(".minecraft")))) {
                return dir.resolve(".minecraft");
            }
        }
        return dir;
    }

    private static void usage(PrintStream out) {
        out.println("""
                ModSync client agent — 在游戏启动前同步服务器模组，并重组成 mods 目录

                用法:
                  java -jar modsync-agent.jar [选项]                 # Prism/MultiMC 预启动命令
                  -javaagent:/绝对路径/modsync-agent.jar[=选项]       # PCL 等直接改 JVM 参数

                选项:
                  --manifest-url <url>   服务器清单地址（必填，或用 -Dmodsync.manifestUrl=）
                  --game-dir <dir>       .minecraft 目录，默认当前工作目录（或用 -Dmodsync.gameDir=）
                  --config <file>        指定配置文件，默认 <gameDir>/modsync/config.json
                  --server-dir <name>    服务器模组目录名，默认 mods-server
                  --client-dir <name>    玩家自装模组目录名，默认 mods-client
                  --no-gui               只用控制台进度（或 -Dmodsync.gui=false）
                  --force                强制清空并重组 mods
                  --dry-run              只打印将要执行的操作，不改动任何文件
                  --strict               同步失败时中止游戏启动（默认仅告警后继续）
                  --parallel <n>         并行下载数，默认 4
                  --timeout <seconds>    网络超时，默认 30
                  --client-mc-version <v>     覆盖本地 Minecraft 版本检测（如 1.21.1）
                  --client-loader-version <v> 覆盖本地加载器版本检测（如 21.1.248）
                  --abort-on-mismatch    版本不匹配时直接中止启动（默认只提醒）
                  --no-version-check     关闭服务器/本地版本比对
                  --warn-hold <seconds>  不匹配提示窗口停留秒数，默认 8，0 关闭窗口
                  --verbose              打印异常堆栈
                  --print-game-dir       输出解析到的游戏目录后退出
                  --help                 显示本帮助

                目录模型:
                  mods-server/  由本 agent 与服务器清单保持一致
                  mods-client/  玩家自己安装、手动维护的模组
                  mods/         构建产物, 由两者重组生成, 请勿手动维护
                """);
    }
}
