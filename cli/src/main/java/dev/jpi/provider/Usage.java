package dev.jpi.provider;

/**
 * token 用量。
 *
 * <p>{@code promptTokens} 统一约定为"总输入"（OpenAI 语义）。
 * Anthropic 的 {@code input_tokens} 只算未命中缓存的部分，
 * 所以 AnthropicProvider 会把它和 cache_read / cache_creation 加起来再上报。
 */
public record Usage(int promptTokens, int completionTokens, int cacheHitTokens) {

    public static final Usage EMPTY = new Usage(0, 0, 0);

    public Usage plus(Usage other) {
        return new Usage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cacheHitTokens + other.cacheHitTokens);
    }

    public int cacheMissTokens() {
        return Math.max(0, promptTokens - cacheHitTokens);
    }
}
