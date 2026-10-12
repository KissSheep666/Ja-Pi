package dev.learn.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.provider.ToolSpec;

/**
 * 一个工具。
 *
 * <p>实现类只需要回答四个问题：我叫什么、我是干什么的、我的参数长什么样、
 * 拿到参数后干什么。前面三个决定了模型会不会正确地用你 —— 比第四个重要得多。
 *
 * <p>M3 加了三个 default 方法，让工具能参与权限系统。<b>全都是 default，
 * 所以 M2 写的三个只读工具一行都不用改。</b>
 */
public interface Tool {

    /** 工具名。模型就是靠这个名字调用你的，务必用动词开头、语义明确。 */
    String name();

    /**
     * 工具描述。<b>这是整个工具系统里最需要用心写的部分。</b>
     * 模型只看到这段文字来决定用不用你、什么时候用你。
     */
    String description();

    /** 参数的 JSON Schema。 */
    JsonNode parametersSchema();

    /**
     * 执行工具。
     *
     * @param args 模型给的参数，已经解析成 JSON 树。⚠️ 不要假设它一定合法，要自己校验。
     * @param ctx  执行上下文（工作目录、权限管理器）
     * @return 要回灌给模型的文本结果。<b>这是模型唯一能看到的东西</b>。
     */
    String execute(JsonNode args, ToolContext ctx) throws Exception;

    default ToolSpec spec() {
        return new ToolSpec(name(), description(), parametersSchema());
    }

    // ─────────────────── M3 新增：参与权限系统 ───────────────────

    /**
     * 这个工具是否需要用户审批。
     *
     * <p>只读、无副作用的工具覆写成 false —— 每一次读文件都弹窗会让人疯掉，
     * 而且会训练用户无脑按"允许"。<b>审批疲劳是安全系统最大的敌人。</b>
     */
    default boolean requiresApproval() {
        return true;
    }

    /**
     * 用"人话"描述这次调用准备做什么，展示给用户做审批。
     *
     * <p>⚠️ 这段文字直接决定用户能不能看懂自己将要同意什么。
     * 写 `write_file {"path":"a.java","content":"..."}` 是失职；
     * 要写"覆盖 src/Main.java（现有 1200 字节 → 新 3400 字节）"。
     */
    default String describeAction(JsonNode args, ToolContext ctx) {
        return name() + " " + args;
    }

    /**
     * 会话级授权的粒度。
     *
     * <p>相同 scope 的后续调用不再询问。默认是整个工具，
     * 但这个默认值<b>太粗</b>——"总是允许 write_file"等于允许写任何文件。
     * 有副作用的工具应该覆写它，把粒度收窄到具体对象上。
     */
    default String permissionScope(JsonNode args, ToolContext ctx) {
        return name();
    }
}
