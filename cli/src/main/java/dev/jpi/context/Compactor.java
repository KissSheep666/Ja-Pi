package dev.jpi.context;

import dev.jpi.config.Settings;
import dev.jpi.provider.ChatRequest;
import dev.jpi.provider.Message;
import dev.jpi.provider.Provider;
import dev.jpi.provider.StreamEvent;
import dev.jpi.provider.ToolCall;
import dev.jpi.util.Cancellation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 把旧历史总结成一段摘要。
 *
 * <p>类比：逐字记录 → 会议纪要。结论、决定、待办不能丢；
 * 寒暄、重复尝试、冗长的工具输出可以丢。
 *
 * <p>策略：
 * <ol>
 *   <li>保留开头的 system 提示词；</li>
 *   <li>保留最近 keepRecentMessages 条；</li>
 *   <li>中间部分交给模型总结；</li>
 *   <li>用一条 system 消息把摘要插回中间。</li>
 * </ol>
 *
 * <p>⚠️ 切割点必须落在 user 消息上：assistant 的 tool_calls 和后面的 tool 结果
 * 是一对，从中间切开会产生悬空引用，API 直接报错。
 */
public final class Compactor {

    private static final int MAX_CHARS_PER_MESSAGE = 4000;

    private final Provider provider;
    private final Settings settings;

    /**
     * @param messagesRemoved 减少了多少条消息
     * @param actualBefore    压缩前实测的 prompt_tokens（可能为 0）
     */
    public record Result(List<Message> messages,
                         int tokensBefore,
                         int tokensAfter,
                         int messagesRemoved,
                         int actualBefore) {

        public int savedRatioPercent() {
            if (tokensBefore == 0) {
                return 0;
            }
            return (int) Math.round(100.0 * (tokensBefore - tokensAfter) / tokensBefore);
        }
    }

    public Compactor(Provider provider, Settings settings) {
        this.provider = provider;
        this.settings = settings;
    }

    public Result compact(List<Message> conversation,
                          int actualPromptTokens,
                          Cancellation cancellation,
                          Consumer<String> logger) {
        int tokensBefore = TokenEstimator.estimate(conversation);

        int start = 0;
        if (!conversation.isEmpty() && "system".equals(conversation.get(0).role())) {
            start = 1;
        }

        int keep = settings.keepRecentMessages();
        if (conversation.size() <= keep + start + 1) {
            return unchanged(conversation, tokensBefore, actualPromptTokens);
        }

        int cut = conversation.size() - keep;
        while (cut > start && !"user".equals(conversation.get(cut).role())) {
            cut--;
        }
        if (cut <= start) {
            return unchanged(conversation, tokensBefore, actualPromptTokens);
        }

        List<Message> toSummarize = conversation.subList(start, cut);
        logger.accept(String.format("%n[压缩] 正在把 %d 条历史消息总结成一段……%n", toSummarize.size()));

        String summary;
        try {
            summary = summarize(toSummarize, cancellation);
        } catch (Exception e) {
            if (cancellation.isCancelled()) {
                return unchanged(conversation, tokensBefore, actualPromptTokens);
            }
            // 压缩失败绝不能毁掉历史：保持原状继续
            logger.accept("[压缩] 失败，保持原样继续：" + e.getMessage() + "\n");
            return unchanged(conversation, tokensBefore, actualPromptTokens);
        }

        if (summary.isBlank()) {
            logger.accept("[压缩] 摘要为空，保持原样继续\n");
            return unchanged(conversation, tokensBefore, actualPromptTokens);
        }

        List<Message> compacted = new ArrayList<>();
        compacted.addAll(conversation.subList(0, start));
        compacted.add(Message.system(
                "[以下是更早对话的摘要。原始消息已被省略以节省上下文，"
                        + "但其中的事实、决定和结论都已保留在这里]\n\n" + summary));
        compacted.addAll(conversation.subList(cut, conversation.size()));

        int tokensAfter = TokenEstimator.estimate(compacted);
        if (tokensAfter >= tokensBefore) {
            // 摘要比原文还长（短对话常见）。压缩的目的是省 token，不是省消息条数。
            logger.accept(String.format(
                    "[压缩] 摘要没有更短（%d → %d token），保持原样%n", tokensBefore, tokensAfter));
            return unchanged(conversation, tokensBefore, actualPromptTokens);
        }
        logger.accept(String.format(
                "[压缩] %d 条 → 1 条摘要；消息部分估算 %d → %d token（省 %d%%）%n%n",
                toSummarize.size(), tokensBefore, tokensAfter,
                tokensBefore == 0 ? 0 : Math.round(100.0 * (tokensBefore - tokensAfter) / tokensBefore)));

        return new Result(compacted, tokensBefore, tokensAfter,
                toSummarize.size(), actualPromptTokens);
    }

    private static Result unchanged(List<Message> conversation, int tokens, int actual) {
        return new Result(List.copyOf(conversation), tokens, tokens, 0, actual);
    }

    /**
     * 摘要请求的三个刻意选择：
     * <ol>
     *   <li>不带工具 —— 不需要，也不会触发 reasoning_content 回传规则；</li>
     *   <li>关思考模式 —— 摘要不需要深度推理；</li>
     *   <li>把历史拍平成纯文本 —— 避免转发 tool_calls / tool 配对关系时踩协议坑。</li>
     * </ol>
     */
    private String summarize(List<Message> history, Cancellation cancellation) {
        StringBuilder transcript = new StringBuilder();
        for (Message message : history) {
            transcript.append('[').append(message.role()).append("] ");
            if (message.content() != null && !message.content().isEmpty()) {
                transcript.append(clip(message.content()));
            }
            if (message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    transcript.append(" <调用工具 ").append(call.name())
                            .append('(').append(clip(call.arguments())).append(")>");
                }
            }
            transcript.append('\n');
        }

        String instruction = """
                下面是一段 AI 编码助手与用户的对话历史。请把它压缩成一段简明的中文摘要。

                必须保留：
                - 用户的原始目标，以及当前正在做的任务
                - 已经做过的关键决定，以及为什么这么做
                - 已经查明的重要事实：文件名、类名、方法名、行号、报错信息、结论
                - 当前进展到哪一步了
                - 还没做完、待验证、待确认的事

                可以丢弃：寒暄、重复的尝试、冗长的工具输出细节、中间的推理过程。

                要求：条目式，紧凑，直接输出摘要，不要任何开场白或结束语。

                === 对话历史开始 ===
                """ + transcript + "\n=== 对话历史结束 ===";

        StringBuilder out = new StringBuilder();
        String[] failure = {null};

        provider.stream(
                new ChatRequest(settings.model(), List.of(Message.user(instruction)), List.of(), false),
                cancellation,
                event -> {
                    switch (event) {
                        case StreamEvent.TextDelta text -> out.append(text.text());
                        case StreamEvent.Failure failed -> failure[0] = failed.message();
                        case StreamEvent.ReasoningDelta ignored -> {
                        }
                        case StreamEvent.ToolUseStart ignored -> {
                        }
                        case StreamEvent.ToolUseArgsDelta ignored -> {
                        }
                        case StreamEvent.Done ignored -> {
                        }
                    }
                });

        if (cancellation.isCancelled()) {
            throw new Cancellation.CancelledException();
        }
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0]);
        }
        return out.toString().strip();
    }

    private static String clip(String s) {
        if (s.length() <= MAX_CHARS_PER_MESSAGE) {
            return s;
        }
        return s.substring(0, MAX_CHARS_PER_MESSAGE)
                + "\n…（此处省略 " + (s.length() - MAX_CHARS_PER_MESSAGE) + " 字符）";
    }
}
