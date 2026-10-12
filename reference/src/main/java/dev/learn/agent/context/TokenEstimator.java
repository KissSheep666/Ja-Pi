package dev.learn.agent.context;

import dev.learn.agent.provider.Message;
import dev.learn.agent.provider.ToolCall;
import dev.learn.agent.provider.ToolSpec;

import java.util.List;

/**
 * 粗略估算 token 数。
 *
 * <p><b>先说清楚：这东西不准。</b>真正的 token 数只有模型服务端知道，
 * 每次都从响应的 usage 里拿才是权威。但我们需要一个"在发请求<b>之前</b>"的判断依据 ——
 * 因为"该不该压缩"这个决定必须在请求发出前做出来。
 *
 * <p>所以这是一个<b>保守估计器</b>而不是精确计数器。它只需要满足一个条件：
 * <b>宁可估高，不要估低</b>。估高了顶多过早压缩（浪费一次摘要调用），
 * 估低了会撞上下文上限（请求直接失败）。
 *
 * <p>换算比例来自 DeepSeek 官方文档：
 * <pre>
 *   1 个中文字符 ≈ 0.6 token
 *   1 个英文字符 ≈ 0.3 token
 * </pre>
 *
 * <p>💡 这个类可以替换成官方的离线 tokenizer（DeepSeek 有提供下载），
 * 但要注意：<b>用 tokenizer 会让每次判断变慢</b>，而且它仍然只是估算。
 * 对"该不该压缩"这种粗粒度的决策来说，字符数估算完全够用。
 */
public final class TokenEstimator {

    /** 中文字符（及 CJK 相关字符）的每字符 token 数。 */
    private static final double CJK_TOKENS_PER_CHAR = 0.6;

    /** 其它字符（ASCII 为主）的每字符 token 数。 */
    private static final double OTHER_TOKENS_PER_CHAR = 0.3;

    /** CJK 字符的起始码点。取一个够用的近似边界。 */
    private static final int CJK_START = 0x2E80;

    /** 每条消息的结构开销（role 字段、分隔符等）。 */
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
     * 估算整个对话历史的 token 数。
     *
     * <p>⚠️ 注意把 reasoningContent 也算进去 —— 开启思考模式后它会被回传，
     * 同样是计费的输入。漏算它会导致估算严重偏低（思考内容常常比正文长好几倍）。
     */
    public static int estimate(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += PER_MESSAGE_OVERHEAD;
            total += estimate(m.content());
            total += estimate(m.reasoningContent());
            if (m.toolCalls() != null) {
                for (ToolCall call : m.toolCalls()) {
                    // 工具调用的名字和参数也要回传，也算输入
                    total += estimate(call.name()) + estimate(call.arguments()) + 12;
                }
            }
        }
        return total;
    }

    /**
     * 估算工具定义的 token 数。
     *
     * <p>⚠️ 这一项经常被忘掉，但它是<b>每轮都要重新发一遍的固定开销</b>。
     * 我们这个项目的六个工具，光定义就一千多个 token ——
     * 也就是说每次请求还没说话，就先付了这份钱。
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
