package dev.jpi.permission;

/**
 * 审批人：真正去问"人"的那个东西。
 *
 * <p>策略和交互方式分开：命令行里是 y/n，CI 里可以是读环境变量，
 * 测试里是"永远批准"。所以权限系统可测。
 */
@FunctionalInterface
public interface Approver {

    /**
     * @param toolName 工具名
     * @param action   这次要做什么的人话描述
     * @param risk     风险等级
     */
    Approval ask(String toolName, String action, Risk risk);
}
