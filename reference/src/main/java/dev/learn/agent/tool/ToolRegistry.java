package dev.learn.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.learn.agent.permission.Approval;
import dev.learn.agent.provider.ToolCall;
import dev.learn.agent.provider.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ToolRegistry {

    /** 复用同一个 ObjectMapper。它线程安全，每次 new 一个是浪费。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Tool> byName = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        byName.put(tool.name(), tool);
        return this;
    }

    public Tool get(String name) {
        return byName.get(name);
    }

    /**
     * 给模型看的工具清单。
     * ⚠️ 按名字排序，保证每次请求生成的 JSON 字节完全一致 ——
     * 这是 M4 的上下文缓存能命中的前提之一（前缀变了缓存就全废）。
     */
    public List<ToolSpec> specs() {
        return byName.values().stream()
                .map(Tool::spec)
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    /**
     * 并发执行一批工具调用。
     *
     * <p>结果按输入顺序返回；即使某个工具抛异常也会得到一个 error 结果，
     * <b>绝不让一个失败的工具拖垮整轮</b>。
     *
     * <p>⚠️ M3 起，每个调用在执行前都要过一遍权限检查。因为工具是并发的，
     * 用户可能会同时看到好几个审批提示 —— 这是真实产品里也存在的体验问题
     * （Claude Code 的处理方式是串行化审批提示）。学习项目里先接受它。
     */
    public List<ToolResult> executeAll(List<ToolCall> calls, ToolContext ctx) {
        if (calls.size() == 1) {
            return List.of(executeOne(calls.get(0), ctx));
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResult>> futures = new ArrayList<>(calls.size());
            for (ToolCall call : calls) {
                futures.add(pool.submit(() -> executeOne(call, ctx)));
            }

            List<ToolResult> results = new ArrayList<>(calls.size());
            for (Future<ToolResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add(ToolResult.error("?", "工具执行异常: " + e.getMessage()));
                }
            }
            return results;
        }
    }

    private ToolResult executeOne(ToolCall call, ToolContext ctx) {
        Tool tool = byName.get(call.name());
        if (tool == null) {
            return ToolResult.error(call.id(),
                    "没有名为 " + call.name() + " 的工具。可用工具：" + byName.keySet());
        }

        JsonNode args;
        try {
            args = JSON.readTree(call.arguments());
        } catch (Exception e) {
            return ToolResult.error(call.id(),
                    "工具参数不是合法 JSON：" + call.arguments());
        }

        // ── ★ M3 新增：执行之前先过权限 ──
        Approval approval = ctx.permissions().check(tool, args, ctx);
        if (approval instanceof Approval.Denied denied) {
            // 💡 关键设计：拒绝不是"流程终止"，而是"一次对话"。
            //    模型收到这条消息后，可以解释理由、换个方式、或者就此放弃。
            return ToolResult.error(call.id(),
                    "用户拒绝了这个操作。理由：" + denied.reason()
                            + "\n请不要原样重试。如果这个操作确实必要，先向用户说明为什么；"
                            + "或者换一种用户能接受的方式。");
        }

        try {
            return ToolResult.ok(call.id(), tool.execute(args, ctx));
        } catch (Exception e) {
            // ⚠️ 把原始参数也带上。模型看到自己传了什么，才知道怎么改。
            return ToolResult.error(call.id(),
                    "工具 " + call.name() + " 执行失败：" + e.getMessage()
                            + "\n你传入的参数是：" + call.arguments());
        }
    }
}
