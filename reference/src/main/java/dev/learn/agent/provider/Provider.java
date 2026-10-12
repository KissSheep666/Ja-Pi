package dev.learn.agent.provider;

import java.util.function.Consumer;

/**
 * 模型提供方的统一接口。
 *
 * <p>M2 一个字都没改——加工具不需要动它，因为工具是 ChatRequest 的一部分，
 * 而工具结果只是普通的消息。这就是抽象的回报。
 */
public interface Provider {

    void stream(ChatRequest request, Consumer<StreamEvent> onEvent);
}
