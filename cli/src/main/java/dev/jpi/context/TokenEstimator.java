package dev.jpi.context;

import dev.jpi.provider.Message;
import dev.jpi.provider.ToolCall;
import dev.jpi.provider.ToolSpec;

import java.util.List;

/**
 * 粗略估算 token 数。
 *
 * <p><b>它不准。</b>权威数字只有响应的 usage 里有。但"该不该压缩"这个决定必须在
 * 请求发出之前做出来，所以需要一个保守估算器。原则只有一条：<b>宁可估高</b>。
 *
 * <p>换算比例来自 DeepSeek 文档：中文字符 ≈ 0.6 token，其他字符 ≈ 0.3 token。
 */
public final class TokenEstimator {

    private static final double CJK_TOKENS_PER_CHAR = 0.6;
    private static final double OTHER_TOKENS_PER_CHAR = 0.3;
    private static final int CJK_START = 0x2E80;
    private static final int PER_MESSAGE_OVERHEAD = 8;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= CJK_START) {
                cjk++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil(cjk * CJK_TOKENS_PER_CHAR + other * OTHER_TOKENS_PER_CHAR);
    }

    /**
     * 估算整个对话历史。
     *
     * <p>reasoningContent 也要算：开启思考模式后它会被回传，同样计费。
     */
    public static int estimate(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += PER_MESSAGE_OVERHEAD;
            total += estimate(message.content());
            total += estimate(message.reasoningContent());
            if (message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    total += estimate(call.name()) + estimate(call.arguments()) + 12;
                }
            }
        }
        return total;
    }

    /**
     * 估算工具定义。
     *
     * <p>这一项是每轮都要重发的固定开销，很容易被忘掉。
     */
    public static int estimateTools(List<ToolSpec> tools) {
        int total = 0;
        for (ToolSpec spec : tools) {
            total += estimate(spec.name()) + estimate(spec.description());
            total += estimate(spec.parametersSchema().toString());
            total += 30;
        }
        return total;
    }
}
