package dev.jpi.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次请求的取消令牌。
 *
 * <p>取消不是"立刻杀死线程"，而是两件事：
 * <ol>
 *   <li>通知循环别再往下走（每轮、每个工具执行前检查）；</li>
 *   <li>关掉正在读的网络流 / 子进程，让阻塞中的读立刻返回。</li>
 * </ol>
 *
 * <p>Agent 会保证：只要 assistant 消息（含 tool_calls）已经写进历史，
 * 就一定把对应的工具结果也写回去。否则下一次请求会出现
 * "有 tool_calls 却没有 tool 结果"的悬空引用，API 直接报错。
 */
public final class Cancellation {

    public static final String MESSAGE = "已取消";

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Set<Closeable> resources = ConcurrentHashMap.newKeySet();

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 取消，并关闭所有已注册的资源。
     * 幂等：重复调用只生效一次。
     */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Closeable resource : resources) {
                closeQuietly(resource);
            }
            resources.clear();
        }
    }

    /** 注册一个"取消时要关掉"的资源（网络流、子进程的流等）。 */
    public void register(Closeable resource) {
        if (resource == null) {
            return;
        }
        if (isCancelled()) {
            closeQuietly(resource);
            return;
        }
        resources.add(resource);
        // 注册和取消可能同时发生，补一次检查
        if (isCancelled() && resources.remove(resource)) {
            closeQuietly(resource);
        }
    }

    public void unregister(Closeable resource) {
        if (resource != null) {
            resources.remove(resource);
        }
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 取消路径上，关闭失败没有补救动作
        }
    }

    /** 不打印堆栈：取消是正常控制流，不是异常。 */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super(MESSAGE, null, false, false);
        }
    }
}
