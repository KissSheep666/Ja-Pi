package dev.jpi.permission;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限决策中心。
 *
 * <p>决策顺序（顺序本身就是设计）：
 * <ol>
 *   <li>工具声明不需要审批 → 直接放行</li>
 *   <li>高风险 → 跳过会话缓存，必须重新问</li>
 *   <li>同粒度已授权过 → 放行</li>
 *   <li>否则 → 问人</li>
 * </ol>
 *
 * <p>⚠️ 第 2 条不能省："本会话总是允许 run_bash" 绝不能变成
 * "本会话允许 rm -rf /"。
 */
public final class PermissionManager {

    private final Approver approver;
    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();

    public PermissionManager(Approver approver) {
        this.approver = approver;
    }

    /** 什么都不问、全部放行。测试和不关心权限的场景用。 */
    public static PermissionManager allowAll() {
        return new PermissionManager((tool, action, risk) -> Approval.always());
    }

    public Approval check(Tool tool, JsonNode args, ToolContext ctx) {
        if (!tool.requiresApproval()) {
            return Approval.once();
        }

        DangerDetector.Assessment assessment = DangerDetector.assess(tool.name(), args, ctx);
        Risk risk = assessment.risk();
        String scope = tool.permissionScope(args, ctx);

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
        if (approval == null) {
            return Approval.deny("审批没有返回结果");
        }
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

    public Set<String> grantedScopes() {
        return Set.copyOf(sessionAllowed);
    }

    /** 忘记所有会话级授权。 */
    public void resetSession() {
        sessionAllowed.clear();
    }
}
