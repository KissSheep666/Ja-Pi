package dev.learn.agent.permission;

/**
 * 审批人：真正去问"人"的那个东西。
 *
 * <p>把它抽成接口，是为了让 <b>策略</b> 和 <b>交互方式</b> 分开：
 * <ul>
 *   <li>命令行里它是"在终端问一句 y/n"；</li>
 *   <li>在 CI 里它可以是"读环境变量，非白名单一律拒绝"；</li>
 *   <li>在测试里它是"永远返回批准"，让测试不需要人工干预。</li>
 * </ul>
 * 这就是为什么 M3 的权限系统可测试 —— 因为没有人被硬编码进去。
 */
@FunctionalInterface
public interface Approver {

    /**
     * @param toolName 工具名
     * @param action   这次要做什么的人话描述
     * @param risk     风险等级
     * @return 批准还是拒绝
     */
    Approval ask(String toolName, String action, Risk risk);
}
