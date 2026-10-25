package dev.jpi.provider;

import dev.jpi.provider.anthropic.AnthropicProvider;
import dev.jpi.provider.deepseek.DeepSeekProvider;

import java.util.List;

/**
 * Provider 工厂：把"环境变量 → 可用 Provider"的脏活集中在这里。
 *
 * <p>两种协议的默认端点都是 DeepSeek：
 * <ul>
 *   <li>deepseek：{@code https://api.deepseek.com/chat/completions}</li>
 *   <li>anthropic：{@code https://api.deepseek.com/anthropic/v1/messages}</li>
 * </ul>
 * 同一个账号、同一个模型、两种协议 —— 这是验证抽象是否成立的最干净方式。
 *
 * <p>API key 只从环境变量读取，不落盘、不进代码。
 */
public final class Providers {

    public static final String DEEPSEEK = "deepseek";
    public static final String ANTHROPIC = "anthropic";
    public static final List<String> ALL = List.of(DEEPSEEK, ANTHROPIC);

    private static final String DEFAULT_DEEPSEEK_BASE = "https://api.deepseek.com";
    private static final String DEFAULT_ANTHROPIC_BASE = "https://api.deepseek.com/anthropic";

    private Providers() {
    }

    public static String normalize(String name) {
        return name == null ? DEEPSEEK : name.trim().toLowerCase();
    }

    public static boolean isKnown(String name) {
        return ALL.contains(normalize(name));
    }

    public static Provider create(String name, boolean dumpRaw) {
        String normalized = normalize(name);
        return switch (normalized) {
            case DEEPSEEK -> createDeepSeek(dumpRaw);
            case ANTHROPIC -> createAnthropic(dumpRaw);
            default -> throw new IllegalArgumentException(
                    "未知的 provider: " + name + "（可选：" + String.join(" / ", ALL) + "）");
        };
    }

    private static Provider createDeepSeek(boolean dumpRaw) {
        String apiKey = env("DEEPSEEK_API_KEY");
        if (isBlank(apiKey)) {
            throw new IllegalStateException(
                    "未设置 DEEPSEEK_API_KEY。请先在终端里设置环境变量：\n"
                            + "  Windows PowerShell:  $env:DEEPSEEK_API_KEY=\"sk-...\"\n"
                            + "  Git Bash / macOS:    export DEEPSEEK_API_KEY=\"sk-...\"\n"
                            + "API key 只走环境变量，不要写进任何文件。");
        }
        String baseUrl = firstNonBlank(
                env("JPI_DEEPSEEK_BASE_URL"),
                env("DEEPSEEK_BASE_URL"),
                DEFAULT_DEEPSEEK_BASE);
        return new DeepSeekProvider(apiKey, baseUrl, dumpRaw);
    }

    private static Provider createAnthropic(boolean dumpRaw) {
        String baseUrl = firstNonBlank(
                env("JPI_ANTHROPIC_BASE_URL"),
                env("ANTHROPIC_BASE_URL"),
                DEFAULT_ANTHROPIC_BASE);

        // 默认端点是 DeepSeek 的 Anthropic 兼容层 → 用 DEEPSEEK_API_KEY。
        // 显式指到别处（例如 api.anthropic.com）→ 优先用 ANTHROPIC_API_KEY。
        boolean deepSeekEndpoint = baseUrl.contains("api.deepseek.com");
        String apiKey = deepSeekEndpoint
                ? firstNonBlank(env("DEEPSEEK_API_KEY"), env("ANTHROPIC_API_KEY"))
                : firstNonBlank(env("ANTHROPIC_API_KEY"), env("DEEPSEEK_API_KEY"));

        if (isBlank(apiKey)) {
            throw new IllegalStateException(
                    "未找到可用的 API key。DeepSeek 兼容端点需要 DEEPSEEK_API_KEY；"
                            + "其他 Anthropic 端点需要 ANTHROPIC_API_KEY。");
        }
        return new AnthropicProvider(apiKey, baseUrl, 8192, dumpRaw);
    }

    /** 只用于显示，不暴露 key。 */
    public static String endpointHint(String name) {
        String normalized = normalize(name);
        if (ANTHROPIC.equals(normalized)) {
            return firstNonBlank(
                    env("JPI_ANTHROPIC_BASE_URL"),
                    env("ANTHROPIC_BASE_URL"),
                    DEFAULT_ANTHROPIC_BASE);
        }
        return firstNonBlank(
                env("JPI_DEEPSEEK_BASE_URL"),
                env("DEEPSEEK_BASE_URL"),
                DEFAULT_DEEPSEEK_BASE);
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
