package dev.jpi.provider;

import dev.jpi.util.Cancellation;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 一个可以在运行时切换实现的 Provider 门面。
 *
 * <p>Agent 和 Compactor 都持有它。{@code /provider anthropic} 只需要换掉里面的
 * delegate，不需要重建 Agent，对话历史也不会丢。
 */
public final class SwitchingProvider implements Provider {

    private volatile String name;
    private volatile Provider delegate;

    public SwitchingProvider(String name, Provider delegate) {
        this.name = Objects.requireNonNull(name);
        this.delegate = Objects.requireNonNull(delegate);
    }

    public void switchTo(String name, Provider delegate) {
        this.delegate = Objects.requireNonNull(delegate);
        this.name = Objects.requireNonNull(name);
    }

    public String name() {
        return name;
    }

    public Provider delegate() {
        return delegate;
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamEvent> onEvent) {
        delegate.stream(request, onEvent);
    }

    @Override
    public void stream(ChatRequest request, Cancellation cancellation, Consumer<StreamEvent> onEvent) {
        delegate.stream(request, cancellation, onEvent);
    }
}
