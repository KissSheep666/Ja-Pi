package dev.jpi.agent;

import dev.jpi.provider.StopReason;
import dev.jpi.provider.StreamEvent;
import dev.jpi.provider.ToolCall;
import dev.jpi.provider.Usage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一轮里收到的所有 {@link StreamEvent} 攒成一条完整的 assistant 回复。
 *
 * <p>流式事件是碎片，写回历史和回灌结果都需要整块：
 * 正文要拼起来，工具参数（分片 JSON）更要按 id 累积后整体 parse。
 *
 * <p>一个正在拼装的工具调用。
 */
public final class AssistantTurn {

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();

    /** 保序：模型给的调用顺序，回灌时保持一致。key 是 Provider 翻译好的调用 id。 */
    private final Map<String, PartialCall> partials = new LinkedHashMap<>();

    private StopReason stopReason = StopReason.OTHER;
    private Usage usage = Usage.EMPTY;
    private String failure;

    private static final class PartialCall {
        final String id;
        final String name;
        final StringBuilder arguments = new StringBuilder();

        PartialCall(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public void accept(StreamEvent event) {
        switch (event) {
            case StreamEvent.TextDelta text -> content.append(text.text());

            case StreamEvent.ReasoningDelta reasoningDelta -> reasoning.append(reasoningDelta.text());

            // 第一片：登记 id 和名字
            case StreamEvent.ToolUseStart start ->
                    partials.putIfAbsent(start.id(), new PartialCall(start.id(), start.name()));

            // 后续片：只有 id 和参数碎片，拼到对应调用上
            case StreamEvent.ToolUseArgsDelta delta -> {
                PartialCall call = partials.get(delta.id());
                if (call != null) {
                    call.arguments.append(delta.partialJson());
                }
            }

            case StreamEvent.Done done -> {
                stopReason = done.reason();
                usage = done.usage();
            }

            case StreamEvent.Failure failed -> failure = failed.message();
        }
    }

    /** 拼装完成的工具调用；arguments 是完整 JSON 字符串（还没解析）。 */
    public List<ToolCall> toolCalls() {
        return partials.values().stream()
                .map(partial -> new ToolCall(partial.id, partial.name, partial.arguments.toString()))
                .toList();
    }

    public String contentText() {
        return content.toString();
    }

    /** 返回空串而不是 null：带 tools 的请求必须回传这个字段。 */
    public String reasoningText() {
        return reasoning.toString();
    }

    public StopReason stopReason() {
        return stopReason;
    }

    public Usage usage() {
        return usage;
    }

    public boolean failed() {
        return failure != null;
    }

    public String failureMessage() {
        return failure;
    }
}
