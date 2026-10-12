package dev.learn.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.Tool;
import dev.learn.agent.tool.ToolContext;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限管理器：决定一次工具调用能不能执行。
 *
 * <p>它只做一件事：把"这一次要不要问人"这个判断集中到一个地方。
 * 工具本身完全不知道权限的存在 —— 这是刻意的，
 * 因为权限是<b>横切关注点</b>：它和"读文件"这个功能没有逻辑关系，
 * 但几乎每个有副作用的工具都需要它。
 */
public final class PermissionManager {

    private final Approver approver;

    /**
     * 本会话内已经"总是允许"的粒度键。
     *
     * <p>为什么用 ConcurrentHashMap 的 keySet 而不是 HashSet？
     * 因为工具是并发执行的（虚拟线程），多个线程可能同时检查/写入。
     */
    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();

    public PermissionManager(Approver approver) {
        this.approver = approver;
    }

    /** 一个什么都不问、全部放行的实现。测试和不关心权限的场景用。 */
    public static PermissionManager allowAll() {
        return new PermissionManager((tool, action, risk) -> Approval.always());
    }

    /**
     * 检查一次调用。
     *
     * <p>决策顺序（很重要，顺序本身就是设计）：
     * <ol>
     *   <li>工具声明不需要审批 → 直接放行（只读工具走这条路，不打扰用户）</li>
     *   <li>高风险 → <b>跳过会话缓存，必须重新问</b></li>
     *   <li>同粒度已授权过 → 放行</li>
     *   <li>否则 → 问人</li>
     * </ol>
     */
    public Approval check(Tool tool, JsonNode args, ToolContext ctx) {
        if (!tool.requiresApproval()) {
            return Approval.once();
        }

        DangerDetector.Assessment assessment = DangerDetector.assess(tool.name(), args, ctx);
        Risk risk = assessment.risk();
        String scope = tool.permissionScope(args, ctx);

        // ⚠️ 高风险永远重新确认。这一条是整套设计里最不能省的一行：
        //    "本会话总是允许 run_bash" 绝不能变成"本会话允许 rm -rf /"。
        if (risk == Risk.HIGH) {
            return ask(tool.name(), tool.describeAction(args, ctx), risk, assessment.reason(), scope, false);
        }

        if (sessionAllowed.contains(scope)) {
            return Approval.once();
        }

        return ask(tool.name(), tool.describeAction(args, ctx), risk, assessment.reason(), scope, true);
    }

    private Approval ask(String toolName, String action, Risk risk,
                         String riskReason, String scope, boolean rememberable) {
        Approval approval = approver.ask(toolName, formatAction(action, riskReason), risk);
        if (approval instanceof Approval.Allowed allowed
                && allowed.remember()
                && rememberable) {
            sessionAllowed.add(scope);
        }
        return approval;
    }

    private static String formatAction(String action, String riskReason) {
        if (riskReason == null) {
            return action;
        }
        return action + "\n    ⚠ 命中危险规则：" + riskReason;
    }

    /** 忘记所有会话级授权。 */
    public void forgetAll() {
        sessionAllowed.clear();
    }
}
