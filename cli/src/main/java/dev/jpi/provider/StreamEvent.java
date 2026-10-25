package dev.jpi.provider;

/**
 * 各家协议归一化之后的"通用语言"。
 *
 * <p>Provider 左边只认这几种事件；右边是 OpenAI / Anthropic 的方言。
 */
public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {
    }

    record ReasoningDelta(String text) implements StreamEvent {
    }

    record ToolUseStart(String id, String name) implements StreamEvent {
    }

    record ToolUseArgsDelta(String id, String partialJson) implements StreamEvent {
    }

    record Done(StopReason reason, Usage usage) implements StreamEvent {
    }

    record Failure(String message) implements StreamEvent {
    }
}
