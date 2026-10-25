package dev.jpi.permission;

/** 对一次审批请求的回答。 */
public sealed interface Approval {

    /**
     * @param remember 本会话内是否不再询问同类操作（仅对非 HIGH 风险生效）
     */
    record Allowed(boolean remember) implements Approval {
    }

    /**
     * @param reason 会被原样告诉模型，所以写清楚对模型有意义
     */
    record Denied(String reason) implements Approval {
    }

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
