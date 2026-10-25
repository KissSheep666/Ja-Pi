package dev.jpi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jpi.permission.Approval;
import dev.jpi.provider.ToolCall;
import dev.jpi.provider.ToolSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 工具注册表：给模型的清单、并发执行、权限检查都在这里。
 */
public final class ToolRegistry {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Tool> byName = Collections.synchronizedMap(new LinkedHashMap<>());

    public synchronized ToolRegistry register(Tool tool) {
        byName.put(tool.name(), tool);
        return this;
    }

    public synchronized boolean has(String name) {
        return byName.containsKey(name);
    }

    public synchronized Tool get(String name) {
        return byName.get(name);
    }

    public synchronized List<Tool> all() {
        return List.copyOf(byName.values());
    }

    /**
     * 给模型看的工具清单。
     *
     * <p>按名字排序，保证每次请求生成的 JSON 字节完全一致 ——
     * 这是上下文缓存能命中的前提之一。
     */
    public synchronized List<ToolSpec> specs() {
        return byName.values().stream()
                .map(Tool::spec)
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .toList();
    }

    /**
     * 并发执行一批工具调用。结果按输入顺序返回；
     * 某个工具抛异常也会得到一个 error 结果，绝不拖垮整轮。
     */
    public List<ToolResult> executeAll(List<ToolCall> calls, ToolContext context) {
        if (calls.isEmpty()) {
            return List.of();
        }
        if (calls.size() == 1) {
            return List.of(executeOne(calls.get(0), context));
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResult>> futures = new ArrayList<>(calls.size());
            for (ToolCall call : calls) {
                futures.add(pool.submit(() -> executeOne(call, context)));
            }

            List<ToolResult> results = new ArrayList<>(calls.size());
            for (int i = 0; i < futures.size(); i++) {
                ToolCall call = calls.get(i);
                try {
                    results.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolResult.error(call.id(), "工具执行被中断"));
                } catch (Exception e) {
                    results.add(ToolResult.error(call.id(), "工具执行异常：" + e.getMessage()));
                }
            }
            return results;
        }
    }

    /** UI 用的一行摘要。 */
    public String describe(ToolCall call) {
        Tool tool = get(call.name());
        if (tool == null) {
            return call.name();
        }
        try {
            JsonNode args = JSON.readTree(call.arguments());
            return tool.preview(args);
        } catch (Exception e) {
            return call.name();
        }
    }

    private ToolResult executeOne(ToolCall call, ToolContext context) {
        long start = System.nanoTime();

        Tool tool = get(call.name());
        if (tool == null) {
            return ToolResult.error(call.id(),
                    "没有名为 " + call.name() + " 的工具。可用工具：" + toolNames())
                    .withDuration(elapsedMs(start));
        }

        JsonNode args;
        try {
            args = JSON.readTree(call.arguments());
        } catch (Exception e) {
            return ToolResult.error(call.id(), "工具参数不是合法 JSON：" + call.arguments())
                    .withDuration(elapsedMs(start));
        }

        if (context.cancellation() != null && context.cancellation().isCancelled()) {
            return ToolResult.error(call.id(), "用户取消了操作。")
                    .withDuration(elapsedMs(start));
        }

        Approval approval = context.permissions().check(tool, args, context);
        if (approval instanceof Approval.Denied denied) {
            // 拒绝不是流程终止，而是一次对话：模型可以解释、换方式或放弃
            return ToolResult.error(call.id(),
                    "用户拒绝了这个操作。理由：" + denied.reason()
                            + "\n请不要原样重试。如果这个操作确实必要，先向用户说明为什么；"
                            + "或者换一种用户能接受的方式。")
                    .withDuration(elapsedMs(start));
        }

        try {
            String content = tool.execute(args, context);
            return ToolResult.timed(call.id(), content, false, elapsedMs(start));
        } catch (Exception e) {
            return ToolResult.timed(call.id(),
                    "工具 " + call.name() + " 执行失败：" + e.getMessage()
                            + "\n你传入的参数是：" + call.arguments(),
                    true, elapsedMs(start));
        }
    }

    private synchronized String toolNames() {
        return String.join(", ", byName.keySet());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
