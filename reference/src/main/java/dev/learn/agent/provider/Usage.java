package dev.learn.agent.provider;

public record Usage(int promptTokens, int completionTokens, int cacheHitTokens) {

    public static final Usage EMPTY = new Usage(0, 0, 0);

    public Usage plus(Usage other) {
        return new Usage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cacheHitTokens + other.cacheHitTokens);
    }
}
