package dev.learn.agent.provider;

public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {}

    record ReasoningDelta(String text) implements StreamEvent {}

    record ToolUseStart(String id, String name) implements StreamEvent {}

    record ToolUseArgsDelta(String id, String partialJson) implements StreamEvent {}

    record Done(StopReason reason, Usage usage) implements StreamEvent {}

    record Failure(String message) implements StreamEvent {}
}
