package dev.learn.agent.context;

import dev.learn.agent.provider.ChatRequest;
import dev.learn.agent.provider.Message;
import dev.learn.agent.provider.Provider;
import dev.learn.agent.provider.StreamEvent;
import dev.learn.agent.provider.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 压缩器：把旧的历史总结成一段摘要。
 *
 * <p><b>类比</b>：像开会。开了一个小时之后，你不需要把前面六十分钟的每一句原话都留着 ——
 * 你需要的是"我们决定用方案 B，因为 A 的性能不达标，张三负责下周三前出原型"。
 * 议程、寒暄、来回争论的过程都可以丢，<b>结论和待办不能丢</b>。
 *
 * <p>压缩做的事情就是把"逐字记录"变成"会议纪要"。
 *
 * <p>⚠️ 这里有个<b>根本性的取舍</b>，你在这一节会反复遇到它：
 * <ul>
 *   <li>压得狠 → 省 token，但可能丢掉后面需要的信息，模型开始"失忆"；</li>
 *   <li>压得轻 → 信息全，但省不下多少，等于白压。</li>
 * </ul>
 * <b>没有正确答案。</b>只能根据任务类型调：改代码的任务要保住文件内容细节，
 * 聊需求的任务可以压得狠一些。
 */
public final class Compactor {

    /**
     * 单条消息在摘要输入里的最大字符数。
     *
     * <p>⚠️ 为什么要截断？因为压缩的<b>输入本身也是要计费的</b>。
     * 如果我们把 5 万 token 的历史原样丢给模型让它总结，
     * 这一次调用的成本可能比不压缩还高。
     *
     * <p>代价是：被截断的细节，模型总结时看不到了。
     * 又是那个取舍 —— 这次是"摘要质量"换"摘要成本"。
     */
    private static final int MAX_CHARS_PER_MESSAGE = 4000;

    private final Provider provider;
    private final String model;
    private final int keepRecentMessages;

    /**
     * 一次压缩的结果。
     *
     * @param messages        压缩后的新历史
     * @param tokensBefore    压缩前估算 token
     * @param tokensAfter     压缩后估算 token
     * @param messagesRemoved 减少了多少条消息
     * @param actualBefore    压缩前<b>实测</b>的 prompt_tokens（上一次请求的真实值，可能为 0）
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

    public Compactor(Provider provider, String model, int keepRecentMessages) {
        this.provider = provider;
        this.model = model;
        this.keepRecentMessages = keepRecentMessages;
    }

    /**
     * 压缩对话历史。
     *
     * <p>策略：
     * <ol>
     *   <li><b>保留</b>第 0 条系统提示词；</li>
     *   <li><b>保留</b>最近 keepRecentMessages 条；</li>
     *   <li>中间的部分交给模型总结成一段；</li>
     *   <li>用一条 system 消息把摘要插回中间。</li>
     * </ol>
     *
     * <p>⚠️ <b>切割点必须落在 user 消息上。</b>
     * 因为助手消息里的 tool_calls 和后面的 tool 结果消息是一对，
     * 从中间切开会出现"有工具结果但找不到对应的调用"，
     * API 会直接报错。从 user 边界切，整个工具调用序列就是完整的一坨。
     */
    public Result compact(List<Message> conversation, int actualPromptTokens,
                          Consumer<String> logger) {

        int tokensBefore = TokenEstimator.estimate(conversation);

        // 至少要有 system + 几条 + 保留的几条，否则没什么可压的
        if (conversation.size() <= keepRecentMessages + 2) {
            return new Result(conversation, tokensBefore, tokensBefore, 0, actualPromptTokens);
        }

        Message systemPrompt = conversation.get(0);

        // 从后往前数 keepRecentMessages 条，再往前退到最近的 user 边界
        int cut = conversation.size() - keepRecentMessages;
        while (cut > 1 && !"user".equals(conversation.get(cut).role())) {
            cut--;
        }
        if (cut <= 1) {
            return new Result(conversation, tokensBefore, tokensBefore, 0, actualPromptTokens);
        }

        List<Message> toSummarize = conversation.subList(1, cut);
        logger.accept(String.format("%n[压缩] 正在把 %d 条历史消息总结成一段……%n", toSummarize.size()));

        String summary;
        try {
            summary = summarize(toSummarize);
        } catch (Exception e) {
            // ⚠️ 压缩失败绝不能毁掉历史。
            //    这在设计上叫 fail-safe：出问题时保持原状，而不是用残缺的东西继续。
            logger.accept("[压缩] 失败，保持原样继续：" + e.getMessage() + "\n");
            return new Result(conversation, tokensBefore, tokensBefore, 0, actualPromptTokens);
        }

        if (summary.isBlank()) {
            logger.accept("[压缩] 摘要为空，保持原样继续\n");
            return new Result(conversation, tokensBefore, tokensBefore, 0, actualPromptTokens);
        }

        List<Message> compacted = new ArrayList<>();
        compacted.add(systemPrompt);
        compacted.add(Message.system(
                "[以下是更早对话的摘要。原始消息已被省略以节省上下文，"
                        + "但其中的事实、决定和结论都已保留在这里]\n\n" + summary));
        compacted.addAll(conversation.subList(cut, conversation.size()));

        int tokensAfter = TokenEstimator.estimate(compacted);
        // ⚠️ 这里报的是"仅消息部分"，不含工具定义。所以它比 Agent 报的
        //    "上下文用量"小 —— 那个数字里还包含了每轮都要重发的工具 schema。
        logger.accept(String.format(
                "[压缩] %d 条 → 1 条摘要；消息部分估算 %d → %d token（省 %d%%）%n%n",
                toSummarize.size(), tokensBefore, tokensAfter,
                tokensBefore == 0 ? 0 : Math.round(100.0 * (tokensBefore - tokensAfter) / tokensBefore)));

        return new Result(compacted, tokensBefore, tokensAfter,
                toSummarize.size(), actualPromptTokens);
    }

    /**
     * 调用模型生成摘要。
     *
     * <p>⚠️ 注意这里构造的请求有三个刻意的选择，每一个都有原因：
     * <ol>
     *   <li><b>不带工具</b> —— 摘要任务不需要工具，而且不带 tools 就不会触发铁律 6
     *       （reasoning_content 必须回传），省掉一堆麻烦。</li>
     *   <li><b>关思考模式</b> —— 摘要不需要深度推理，开思考只是白花钱和时间。</li>
     *   <li><b>把历史拍平成纯文本</b> —— 不原样转发消息列表。因为历史里可能有
     *       tool_calls / tool 结果的配对关系，转发过去容易踩协议坑。
     *       拍平成文本是最省事也最稳的做法。</li>
     * </ol>
     */
    private String summarize(List<Message> history) {
        StringBuilder transcript = new StringBuilder();
        for (Message m : history) {
            transcript.append('[').append(m.role()).append("] ");
            if (m.content() != null && !m.content().isEmpty()) {
                transcript.append(clip(m.content()));
            }
            if (m.toolCalls() != null) {
                for (ToolCall call : m.toolCalls()) {
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

        // 用一个数组当可变容器：lambda 里不能给局部变量赋值
        StringBuilder out = new StringBuilder();
        String[] failure = {null};

        provider.stream(
                new ChatRequest(model, List.of(Message.user(instruction)), List.of(), false),
                event -> {
                    switch (event) {
                        case StreamEvent.TextDelta t -> out.append(t.text());
                        case StreamEvent.Failure f -> failure[0] = f.message();
                        case StreamEvent.Done d -> {
                        }
                        case StreamEvent.ToolUseStart s -> {
                        }
                        case StreamEvent.ToolUseArgsDelta a -> {
                        }
                        case StreamEvent.ReasoningDelta r -> {
                        }
                    }
                });

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
