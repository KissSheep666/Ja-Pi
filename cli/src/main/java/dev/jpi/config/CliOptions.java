package dev.jpi.config;

import dev.jpi.provider.Providers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行参数。支持 {@code --name=value} 和 {@code --name value} 两种写法。
 */
public record CliOptions(
        String provider,
        String model,
        boolean thinking,
        boolean autoApprove,
        int budget,
        int keep,
        List<String> mcpCommands,
        boolean dumpRaw,
        boolean color,
        Path cwd,
        boolean help,
        boolean version) {

    public static CliOptions parse(String[] args) {
        String provider = envOrDefault("JPI_PROVIDER", Providers.DEEPSEEK);
        String model = envOrDefault("JPI_MODEL", Settings.DEFAULT_MODEL);
        boolean thinking = isTruthy(System.getenv("JPI_THINK"));
        boolean autoApprove = false;
        int budget = intEnv("JPI_BUDGET", Settings.DEFAULT_BUDGET);
        int keep = intEnv("JPI_KEEP", Settings.DEFAULT_KEEP);
        List<String> mcpCommands = new ArrayList<>();
        boolean dumpRaw = false;
        boolean color = System.getenv("NO_COLOR") == null;
        Path cwd = null;
        boolean help = false;
        boolean version = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                continue;
            }

            if (arg.equals("-h") || arg.equals("--help")) {
                help = true;
            } else if (arg.equals("--version") || arg.equals("-V")) {
                version = true;
            } else if (arg.equals("--think")) {
                thinking = true;
            } else if (arg.equals("--no-think")) {
                thinking = false;
            } else if (arg.equals("-y") || arg.equals("--yes")) {
                autoApprove = true;
            } else if (arg.equals("--raw")) {
                dumpRaw = true;
            } else if (arg.equals("--no-color")) {
                color = false;
            } else if (arg.startsWith("--provider=")) {
                provider = value(arg);
            } else if (arg.equals("--provider")) {
                provider = requireNext(args, ++i, "--provider");
            } else if (arg.startsWith("--model=")) {
                model = value(arg);
            } else if (arg.equals("--model")) {
                model = requireNext(args, ++i, "--model");
            } else if (arg.startsWith("--budget=")) {
                budget = parseInt(value(arg), "--budget");
            } else if (arg.equals("--budget")) {
                budget = parseInt(requireNext(args, ++i, "--budget"), "--budget");
            } else if (arg.startsWith("--keep=")) {
                keep = parseInt(value(arg), "--keep");
            } else if (arg.equals("--keep")) {
                keep = parseInt(requireNext(args, ++i, "--keep"), "--keep");
            } else if (arg.startsWith("--mcp=")) {
                mcpCommands.add(value(arg));
            } else if (arg.equals("--mcp")) {
                mcpCommands.add(requireNext(args, ++i, "--mcp"));
            } else if (arg.startsWith("--cwd=")) {
                cwd = Path.of(value(arg));
            } else if (arg.equals("--cwd")) {
                cwd = Path.of(requireNext(args, ++i, "--cwd"));
            } else {
                throw new IllegalArgumentException(
                        "未知参数：" + arg + "\n运行 jpi --help 查看用法。");
            }
        }

        provider = Providers.normalize(provider);
        if (!Providers.isKnown(provider)) {
            throw new IllegalArgumentException(
                    "未知的 provider：" + provider + "（可选：" + String.join(" / ", Providers.ALL) + "）");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("--model 不能为空");
        }
        if (budget < 1_000) {
            throw new IllegalArgumentException("--budget 不能小于 1000");
        }
        if (keep < 2 || keep > 200) {
            throw new IllegalArgumentException("--keep 必须在 2 到 200 之间");
        }
        if (cwd != null) {
            cwd = cwd.toAbsolutePath().normalize();
            if (!Files.isDirectory(cwd)) {
                throw new IllegalArgumentException("--cwd 指向的目录不存在：" + cwd);
            }
        }

        return new CliOptions(provider, model.trim(), thinking, autoApprove, budget, keep,
                List.copyOf(mcpCommands), dumpRaw, color, cwd, help, version);
    }

    public static String helpText() {
        return """
                jpi —— 用 Java 21 手写的终端编码 agent

                用法：
                  jpi [选项]

                选项：
                  --provider=<name>    模型协议：deepseek（默认）或 anthropic
                  --model=<id>         模型 ID，默认 deepseek-flash
                  --think              开启思考模式
                  --budget=<tokens>    上下文预算，默认 24000
                  --keep=<n>           压缩时保留最近 n 条消息，默认 8
                  --mcp=<command>      挂载 MCP 服务器；可重复。传 demo 挂载内置示例
                  --cwd=<path>         工作目录，默认当前目录
                  -y, --yes            自动批准所有工具调用（谨慎使用）
                  --raw                把原始 SSE 打到 stderr
                  --no-color           关闭颜色
                  -h, --help           显示本帮助
                  -V, --version        显示版本

                环境变量：
                  DEEPSEEK_API_KEY     DeepSeek key（deepseek 协议必需）
                  ANTHROPIC_API_KEY    其他 Anthropic 端点使用的 key
                  JPI_PROVIDER         默认 provider
                  JPI_MODEL            默认模型
                  JPI_THINK=1          默认开启思考模式
                  JPI_BUDGET           默认上下文预算
                  JPI_KEEP             默认保留消息数
                  JPI_DEEPSEEK_BASE_URL / DEEPSEEK_BASE_URL
                  JPI_ANTHROPIC_BASE_URL / ANTHROPIC_BASE_URL
                  NO_COLOR             设置后关闭颜色

                示例：
                  export DEEPSEEK_API_KEY="sk-..."
                  jpi
                  jpi --provider=anthropic --think
                  jpi --mcp=demo
                  jpi --budget=8000 --keep=4
                """;
    }

    private static String value(String arg) {
        int eq = arg.indexOf('=');
        return arg.substring(eq + 1).trim();
    }

    private static String requireNext(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 需要一个值");
        }
        return args[index];
    }

    private static int parseInt(String value, String flag) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " 需要一个整数，收到：" + value);
        }
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }
}
