package dev.learn.agent.agent;

import dev.learn.agent.context.Compactor;
import dev.learn.agent.context.ContextBudget;
import dev.learn.agent.context.TokenEstimator;
import dev.learn.agent.provider.ChatRequest;
import dev.learn.agent.provider.Message;
import dev.learn.agent.provider.Provider;
import dev.learn.agent.provider.StopReason;
import dev.learn.agent.provider.StreamEvent;
import dev.learn.agent.provider.ToolCall;
import dev.learn.agent.provider.Usage;
import dev.learn.agent.tool.ToolContext;
import dev.learn.agent.tool.ToolRegistry;
import dev.learn.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 核心 agent 循环。
 *
 * <p>整个 agent 的心跳就是这几行：
 * <pre>
 *   while (true) {
 *       如果历史太长 → 压缩        ← M4 新增
 *       调模型
 *       把回复写进历史
 *       if (模型不调工具) 结束
 *       执行工具，把结果写进历史
 *   }
 * </pre>
 */
public final class Agent {

    private static final int MAX_ROUNDS = 25;

    private final Provider provider;
    private final ToolRegistry tools;
    private final ToolContext context;
    private final String model;
    private final boolean thinking;

    // ── M4 新增：上下文管理 ──
    private final ContextBudget budget;
    private final Compactor compactor;

    private final List<Message> conversation = new ArrayList<>();
    private Usage totalUsage = Usage.EMPTY;

    /** 上一次请求<b>实测</b>的输入 token 数。这是唯一权威的数字。 */
    private int lastPromptTokens = 0;

    public Agent(Provider provider,
                 ToolRegistry tools,
                 ToolContext context,
                 String model,
                 boolean thinking,
                 String systemPrompt,
                 ContextBudget budget,
                 Compactor compactor) {
        this.provider = provider;
        this.tools = tools;
        this.context = context;
        this.model = model;
        this.thinking = thinking;
        this.budget = budget;
        this.compactor = compactor;
        this.conversation.add(Message.system(systemPrompt));
    }

    /** 处理用户的一次输入，直到模型给出最终答复（或触发熔断）。阻塞方法。 */
    public void run(String userInput, AgentListener listener) {
        conversation.add(Message.user(userInput));

        for (int round = 1; round <= MAX_ROUNDS; round++) {

            // ── 0. 上下文管理：发请求之前先看看是不是该压缩了 ──
            maybeCompact(listener);

            // ── 1. 调模型，边收边攒 ──
            AssistantTurn turn = new AssistantTurn();
            provider.stream(
                    new ChatRequest(model, List.copyOf(conversation), tools.specs(), thinking),
                    event -> {
                        turn.accept(event);
                        relay(event, listener);
                    });

            if (turn.failed()) {
                return;
            }

            // ── 2. 把这一轮的回复写进历史 ──
            // ⚠️ reasoningText() 必须一起写进去，否则带 tools 的请求会 400（铁律 6）
            conversation.add(Message.assistant(
                    turn.contentText(),
                    turn.reasoningText(),
                    turn.toolCalls()));

            totalUsage = totalUsage.plus(turn.usage());
            // 记下这次的实测输入长度，下次判断压缩时用它校准
            if (turn.usage().promptTokens() > 0) {
                lastPromptTokens = turn.usage().promptTokens();
            }
            listener.onRoundEnd(turn.stopReason(), turn.usage());

            // ── 3. 模型不调工具 → 说完了，收工 ──
            if (turn.stopReason() != StopReason.TOOL_USE) {
                return;
            }

            List<ToolCall> calls = turn.toolCalls();
            if (calls.isEmpty()) {
                listener.onFailure("finish_reason 是 tool_calls，但没有收到任何工具调用");
                return;
            }

            // ── 4. 执行工具 ──
            List<ToolResult> results = tools.executeAll(calls, context);

            // ── 5. 把工具结果写进历史 ──
            for (ToolResult result : results) {
                listener.onToolResult(result);
                conversation.add(Message.tool(result.toolCallId(), result.content()));
            }
        }

        listener.onLimitReached(MAX_ROUNDS);
    }

    /**
     * 检查上下文用量，必要时压缩。
     *
     * <p>⚠️ 注意判断用的是<b>估算值</b>，因为决定必须在请求发出<b>之前</b>做出来。
     * 但我们同时保留了 {@code lastPromptTokens}（上一次的实测值），
     * 用来在日志里对照 —— 你会看到估算和实测能差多少。
     * <b>亲眼看到这个误差，比记住"估算不准"这句话有用得多。</b>
     */
    private void maybeCompact(AgentListener listener) {
        if (compactor == null || budget == null) {
            return;
        }

        int estimated = TokenEstimator.estimate(conversation)
                + TokenEstimator.estimateTools(tools.specs());

        listener.onContextUsage(estimated, budget.tokenBudget(), lastPromptTokens);

        if (!budget.shouldCompact(estimated)) {
            return;
        }

        Compactor.Result result = compactor.compact(
                conversation, lastPromptTokens, listener::onCompactionLog);

        if (result.messagesRemoved() > 0) {
            conversation.clear();
            conversation.addAll(result.messages());
            listener.onCompacted(result);
        }
    }

    /** 把流事件转成 listener 回调。 */
    private static void relay(StreamEvent event, AgentListener listener) {
        switch (event) {
            case StreamEvent.TextDelta t -> listener.onText(t.text());
            case StreamEvent.ReasoningDelta r -> listener.onReasoning(r.text());
            case StreamEvent.ToolUseStart s -> listener.onToolCallStart(s.name());
            case StreamEvent.ToolUseArgsDelta a -> listener.onToolCallArgs(a.partialJson());
            case StreamEvent.Failure f -> listener.onFailure(f.message());
            case StreamEvent.Done d -> {
                // 用量和停止原因由 AssistantTurn 消化，这里不重复上报
            }
        }
    }

    public List<Message> conversation() {
        return List.copyOf(conversation);
    }

    public Usage totalUsage() {
        return totalUsage;
    }

    /** 上一次请求实测的输入 token。用来对照估算误差。 */
    public int lastPromptTokens() {
        return lastPromptTokens;
    }
}
