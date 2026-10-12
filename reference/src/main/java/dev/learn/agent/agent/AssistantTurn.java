package dev.learn.agent.agent;

import dev.learn.agent.provider.StopReason;
import dev.learn.agent.provider.StreamEvent;
import dev.learn.agent.provider.ToolCall;
import dev.learn.agent.provider.Usage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一轮里收到的所有 StreamEvent，攒成一条完整的 assistant 回复。
 *
 * <p><b>这是 M2 的核心，也是 M1 埋下的铁律 3 真正落地的地方。</b>
 *
 * <p>为什么不直接边收边用？因为流式事件是"碎片"，而写回历史、回灌结果都需要"整块"：
 * <ul>
 *   <li>正文是一段段来的 → 拼起来才是完整回复</li>
 *   <li>工具参数是一片片来的 → 拼起来才是合法 JSON</li>
 * </ul>
 *
 * <p>这就像一个翻译官同声传译：他一边听一边把句子记下来，
 * 等对方说完了，才能把完整的一句话交给你。中途你拿到的都是半句。
 */
public final class AssistantTurn {

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();

    /**
     * ⚠️ 用 LinkedHashMap 保序。
     * 工具调用的顺序是模型决定的，回灌结果时最好保持一致，
     * 而且显示出来也更符合直觉。
     *
     * <p>key 是工具调用的 id——Provider 已经帮我们把流里的 index 翻译成 id 了，
     * 所以这里不需要再关心 index。
     */
    private final Map<String, PartialCall> partials = new LinkedHashMap<>();

    private StopReason stopReason = StopReason.OTHER;
    private Usage usage = Usage.EMPTY;
    private String failure;

    /** 一个正在拼装的工具调用。 */
    private static final class PartialCall {
        final String id;
        final String name;
        final StringBuilder arguments = new StringBuilder();

        PartialCall(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 喂给它一个事件。它在内部默默累积。 */
    public void accept(StreamEvent event) {
        switch (event) {
            case StreamEvent.TextDelta t -> content.append(t.text());

            case StreamEvent.ReasoningDelta r -> reasoning.append(r.text());

            // 第一次见到某个工具：登记它的 id 和名字
            case StreamEvent.ToolUseStart s ->
                    partials.putIfAbsent(s.id(), new PartialCall(s.id(), s.name()));

            // 参数碎片：按 id 找到对应的调用，把碎片接上去
            // ⚠️ 这就是"分拣线"那一步（铁律 3）。绝不能在这里做字符串匹配判断。
            case StreamEvent.ToolUseArgsDelta a -> {
                PartialCall call = partials.get(a.id());
                if (call != null) {
                    call.arguments.append(a.partialJson());
                }
            }

            case StreamEvent.Done d -> {
                stopReason = d.reason();
                usage = d.usage();
            }

            case StreamEvent.Failure f -> failure = f.message();
        }
    }

    /** 拼装完成的工具调用列表。arguments 是完整 JSON 字符串（还没解析）。 */
    public List<ToolCall> toolCalls() {
        return partials.values().stream()
                .map(p -> new ToolCall(p.id, p.name, p.arguments.toString()))
                .toList();
    }

    public String contentText() {
        return content.toString();
    }

    /** ⚠️ 返回空串而不是 null。带 tools 的请求必须回传这个字段（铁律 6）。 */
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
