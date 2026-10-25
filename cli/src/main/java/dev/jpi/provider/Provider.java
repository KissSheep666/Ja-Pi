package dev.jpi.provider;

import dev.jpi.util.Cancellation;

import java.util.function.Consumer;

/**
 * 模型提供方的统一接口。
 *
 * <p>换厂商只需要换右边：Agent 只认 {@link StreamEvent}。
 */
public interface Provider {

    void stream(ChatRequest request, Consumer<StreamEvent> onEvent);

    /**
     * 可取消的流式请求。
     *
     * <p>默认实现忽略取消令牌；真正的 Provider 会把它注册到网络流上，
     * 用户按 Ctrl+C 时能立刻关掉连接。
     */
    default void stream(ChatRequest request, Cancellation cancellation, Consumer<StreamEvent> onEvent) {
        stream(request, onEvent);
    }
}
