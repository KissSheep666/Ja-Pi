package dev.learn.agent.permission;

/**
 * 对一次审批请求的回答。
 */
public sealed interface Approval {

    /**
     * 批准。
     *
     * @param remember 是否本会话内不再询问同类操作（仅对非 HIGH 风险生效）
     */
    record Allowed(boolean remember) implements Approval {}

    /**
     * 拒绝。
     *
     * @param reason 理由。<b>会被原样告诉模型</b>，所以写清楚对模型有意义。
     */
    record Denied(String reason) implements Approval {}

    static Approval once() {
        return new Allowed(false);
    }

    static Approval always() {
        return new Allowed(true);
    }

    static Approval deny(String reason) {
        return new Denied(reason);
    }
}
