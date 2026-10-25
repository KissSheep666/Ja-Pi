package dev.jpi.agent;

import dev.jpi.config.Settings;
import dev.jpi.context.Compactor;
import dev.jpi.context.ContextBudget;
import dev.jpi.context.TokenEstimator;
import dev.jpi.provider.ChatRequest;
import dev.jpi.provider.Message;
import dev.jpi.provider.Provider;
import dev.jpi.provider.StopReason;
import dev.jpi.provider.StreamEvent;
import dev.jpi.provider.ToolCall;
import dev.jpi.provider.Usage;
import dev.jpi.tool.ToolContext;
import dev.jpi.tool.ToolRegistry;
import dev.jpi.tool.ToolResult;
import dev.jpi.util.Cancellation;

import java.util.ArrayList;
import java.util.List;

/**
 * 核心 agent 循环：
 * <pre>
 *   while (true) {
 *       如果历史太长 → 压缩
 *       调模型
 *       把回复写进历史
 *       if (模型不调工具) 结束
 *       执行工具，把结果写进历史
 *   }
 * </pre>
 *
 * <p>取消语义（重要）：只要 assistant 消息（含 tool_calls）已经写进历史，
 * 就一定把对应的工具结果也写回去，再退出。否则下一次请求会出现悬空 tool_call。
 */
public final class Agent {

    private static final int MAX_ROUNDS = 25;

    private final Provider provider;
    private final ToolRegistry tools;
    private final ToolContext context;
    private final Settings settings;
    private final String systemPrompt;
    private final Compactor compactor;

    private final List<Message> conversation = new ArrayList<>();
    private Usage totalUsage = Usage.EMPTY;

    /** 上一次请求实测的输入 token。唯一权威的数字，用来校准估算。 */
    private int lastPromptTokens = 0;

    public Agent(Provider provider,
                 ToolRegistry tools,
                 ToolContext context,
                 Settings settings,
                 String systemPrompt,
                 Compactor compactor) {
        this.provider = provider;
        this.tools = tools;
        this.context = context;
        this.settings = settings;
        this.systemPrompt = systemPrompt;
        this.compactor = compactor;
        this.conversation.add(Message.system(systemPrompt));
    }

    /** 处理用户的一次输入，直到模型给出最终答复（或触发熔断 / 取消）。阻塞方法。 */
    public void run(String userInput, AgentListener listener, Cancellation cancellation) {
        conversation.add(Message.user(userInput));

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            if (cancellation.isCancelled()) {
                listener.onCancelled();
                return;
            }

            maybeCompact(listener, cancellation);

            listener.onRoundStart();
            AssistantTurn turn = new AssistantTurn();
            provider.stream(
                    new ChatRequest(settings.model(), List.copyOf(conversation),
                            tools.specs(), settings.thinking()),
                    cancellation,
                    event -> {
                        turn.accept(event);
                        relay(event, listener);
                    });

            // 流式阶段被取消：这一轮 assistant 消息还没进历史，直接返回是安全的
            if (cancellation.isCancelled()) {
                listener.onCancelled();
                return;
            }

            if (turn.failed()) {
                listener.onFailure(turn.failureMessage());
                return;
            }

            // reasoningText() 必须一起写进去，否则带 tools 的请求会 400
            conversation.add(Message.assistant(
                    turn.contentText(),
                    turn.reasoningText(),
                    turn.toolCalls()));

            totalUsage = totalUsage.plus(turn.usage());
            if (turn.usage().promptTokens() > 0) {
                lastPromptTokens = turn.usage().promptTokens();
            }
            listener.onRoundEnd(turn.stopReason(), turn.usage());

            // 不调工具 → 说完了
            if (turn.stopReason() != StopReason.TOOL_USE) {
                return;
            }

            List<ToolCall> calls = turn.toolCalls();
            if (calls.isEmpty()) {
                listener.onFailure("finish_reason 是 tool_calls，但没有收到任何工具调用");
                return;
            }

            List<ToolResult> results;
            context.setCancellation(cancellation);
            try {
                results = tools.executeAll(calls, context);
            } catch (Exception e) {
                // 兜底：即使工具子系统整体崩了，也要为每个 tool_call 生成一条结果，
                // 否则下一次请求会出现悬空引用，API 直接报错。
                results = calls.stream()
                        .map(call -> ToolResult.error(call.id(),
                                "工具执行系统异常：" + e.getMessage()))
                        .toList();
            } finally {
                context.setCancellation(null);
            }
            results = ensureOneResultPerCall(calls, results);

            // 无论是否取消，工具结果都必须回灌，保持 tool_calls / tool 结果配对
            for (int i = 0; i < results.size(); i++) {
                ToolResult result = results.get(i);
                listener.onToolResult(result, tools.describe(calls.get(i)));
                conversation.add(Message.tool(result.toolCallId(), result.content()));
            }

            if (cancellation.isCancelled()) {
                listener.onCancelled();
                return;
            }
        }

        listener.onLimitReached(MAX_ROUNDS);
    }

    /**
     * 检查上下文用量，必要时压缩。
     *
     * <p>判断用的是估算值 —— 因为决定必须在请求发出之前做出来。
     * 上一次实测的 lastPromptTokens 只用于在 UI 里对照误差。
     */
    private void maybeCompact(AgentListener listener, Cancellation cancellation) {
        if (compactor == null) {
            return;
        }

        ContextBudget budget = settings.budget();
        int estimated = TokenEstimator.estimate(conversation)
                + TokenEstimator.estimateTools(tools.specs());

        listener.onContextUsage(estimated, budget.tokenBudget(), lastPromptTokens);

        if (!budget.shouldCompact(estimated)) {
            return;
        }

        Compactor.Result result = compactor.compact(
                conversation, lastPromptTokens, cancellation, listener::onCompactionLog);

        if (result.messagesRemoved() > 0) {
            conversation.clear();
            conversation.addAll(result.messages());
            listener.onCompacted(result);
        }
    }

    /** 手动压缩。返回结果供 UI 判断"有没有压缩成功"。 */
    public Compactor.Result forceCompact(AgentListener listener, Cancellation cancellation) {
        if (compactor == null) {
            return new Compactor.Result(List.copyOf(conversation),
                    TokenEstimator.estimate(conversation), TokenEstimator.estimate(conversation), 0,
                    lastPromptTokens);
        }
        Compactor.Result result = compactor.compact(
                conversation, lastPromptTokens, cancellation, listener::onCompactionLog);
        if (result.messagesRemoved() > 0) {
            conversation.clear();
            conversation.addAll(result.messages());
            listener.onCompacted(result);
        }
        return result;
    }

    /** 保证每个 tool_call 恰好对应一条 tool 结果；顺序与 calls 一致。 */
    private static List<ToolResult> ensureOneResultPerCall(List<ToolCall> calls, List<ToolResult> results) {
        if (results.size() == calls.size()) {
            boolean aligned = true;
            for (int i = 0; i < calls.size(); i++) {
                if (!calls.get(i).id().equals(results.get(i).toolCallId())) {
                    aligned = false;
                    break;
                }
            }
            if (aligned) {
                return results;
            }
        }

        List<ToolResult> fixed = new ArrayList<>(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            ToolResult result = i < results.size() ? results.get(i) : null;
            if (result != null && calls.get(i).id().equals(result.toolCallId())) {
                fixed.add(result);
            } else {
                fixed.add(ToolResult.error(calls.get(i).id(), "工具没有返回结果"));
            }
        }
        return fixed;
    }

    private static void relay(StreamEvent event, AgentListener listener) {
        switch (event) {
            case StreamEvent.TextDelta text -> listener.onText(text.text());
            case StreamEvent.ReasoningDelta reasoning -> listener.onReasoning(reasoning.text());
            case StreamEvent.ToolUseStart start -> listener.onToolCallStart(start.id(), start.name());
            case StreamEvent.ToolUseArgsDelta delta -> listener.onToolCallArgs(delta.id(), delta.partialJson());
            case StreamEvent.Failure failure -> listener.onFailure(failure.message());
            case StreamEvent.Done done -> {
                // 用量和停止原因由 AssistantTurn 消化
            }
        }
    }

    public void clearConversation() {
        conversation.clear();
        conversation.add(Message.system(systemPrompt));
        totalUsage = Usage.EMPTY;
        lastPromptTokens = 0;
    }

    public List<Message> conversation() {
        return List.copyOf(conversation);
    }

    public Usage totalUsage() {
        return totalUsage;
    }

    public int lastPromptTokens() {
        return lastPromptTokens;
    }

    public Posture posture() {
        return new Posture(conversation.size(),
                TokenEstimator.estimate(conversation) + TokenEstimator.estimateTools(tools.specs()));
    }

    public record Posture(int messages, int estimatedTokens) {
    }
}
