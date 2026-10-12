# 05 · M5 — 验证抽象：第二个 Provider 与 MCP

> 前面四节都在**建设**。这一节回头**检验**。
>
> 我们要验证一个从未被验证过的假设：
> **`Provider` 抽象真的成立吗？还是只是"看起来能扩展"？**
>
> 检验方法：写第二个实现，然后看**哪里真的漏了**。漏掉的地方才是这一节最值钱的产出。

---

## 0. 本节目标与验收标准

### 新增的文件

```
src\main\java\dev\learn\agent\
├── Main.java                                ✏️ --provider= / --mcp= 参数
├── provider\anthropic\                      ✨ 新的
│   └── AnthropicProvider.java               ★ 第二个 Provider 实现
└── mcp\                                     ✨ 新的
    ├── McpClient.java                       ★ stdio JSON-RPC 客户端
    ├── McpToolAdapter.java                  ★ 把 MCP 工具伪装成普通工具
    └── demo\
        └── DemoMcpServer.java               一个自制的测试用 MCP 服务器
```

### 关键约束

**除了 `Main`，一行已有代码都不能改。**

`Provider`、`StreamEvent`、`Agent`、`Tool`、`ToolContext`、`ToolRegistry`、
`PermissionManager`、`Compactor`、`TokenEstimator` —— **全部零改动**。

如果 M5 逼你去改它们，说明前面的抽象在某个地方切错了位置。

### 验收标准

```bash
# 同一套 agent 代码，两套协议
mvn -q compile exec:java -Dexec.args=""                       # OpenAI 格式（默认）
mvn -q compile exec:java -Dexec.args="--provider=anthropic"    # Anthropic 格式

# 挂载 MCP 服务器
mvn -q compile exec:java -Dexec.args="--mcp=demo"
```

✅ 两个 Provider 下，agent 行为完全一致
✅ **两个 Provider 下，token 统计数字也一致**
✅ 挂上 MCP 后，外部工具出现在工具列表里，模型能正常调用
✅ 挂 MCP 不需要改 `Agent`、不需要改权限系统、不需要改压缩器

---

## 1. 概念：抽象什么时候算"成立"？

一个常见的错觉是：

> "我把接口抽出来了，以后加新实现只要实现这个接口就行。" —— 于是抽象"成立"了。

**这是错的。** 抽象是否成立，取决于**接口的位置切在哪里**：

| 接口切在哪儿 | 结果 |
|---|---|
| 切在**协议差异**的下方 | ✅ 新实现只需要翻译协议，上层不动 |
| 切在**业务概念**的上方 | ❌ 每个新实现都要重新实现业务逻辑 |

**怎么判断你切对了？** 只有一个办法：**真的写第二个实现，然后数一数你被迫改了几个文件。**

改的越少，抽象越对。改到 0 个，说明位置切得刚刚好。

### 我们这次实验的设计

**关键在于控制变量。** 如果我们拿"DeepSeek vs OpenAI"做对照，
那么任何行为差异都可能来自**模型能力**，而不只是协议。

所以我们用 **DeepSeek 自己的 Anthropic 兼容端点**：

```
OpenAI 格式：    https://api.deepseek.com/chat/completions
Anthropic 格式： https://api.deepseek.com/anthropic/v1/messages
```

**同一个账号、同一个模型（`deepseek-flash`）、同一个 API key。**

于是任何差异都**只可能**来自协议本身。这是最干净的对照实验。

---

## 2. 三处必须抹平的差异，和一处抹不平的

在写代码之前，先列清楚两个协议差在哪。这张表是 M5 的全部内容。

### 差异 1 · 工具结果的消息形态（方向相反）

**OpenAI 格式**（我们的 Agent 现在就是这么发的）：

```json
{"role":"assistant","content":null,"tool_calls":[{...},{...}]}
{"role":"tool","tool_call_id":"call_00_...","content":"杭州：多云"}
{"role":"tool","tool_call_id":"call_01_...","content":"北京：晴"}
```

**Anthropic 格式**：

```json
{"role":"assistant","content":[
    {"type":"tool_use","id":"call_00_...","name":"get_weather","input":{...}}
]}
{"role":"user","content":[
    {"type":"tool_result","tool_use_id":"call_00_...","content":"杭州：多云"},
    {"type":"tool_result","tool_use_id":"call_01_...","content":"北京：晴"}
]}
```

**一个是"每个结果一条消息"，一个是"全部塞进一条消息"。** 正好相反。

> 💡 这正是 M1 埋下、M3 反复强调的**铁律 1 的反面**。
> `Agent` 只知道"这批工具跑完了，把结果写进历史"——它用哪种形态写，
> 完全由 Provider 决定。**这就是抽象在这一处的价值。**

### 差异 2 · 工具参数的序列化（对象 vs 字符串）

**OpenAI**：`"arguments"` 是**字符串** —— `"{\"location\":\"Hangzhou\"}"`
**Anthropic**：`"input"` 是**对象** —— `{"location":"Hangzhou"}`

同一个 `ToolCall.arguments`（我们一路传过来的那个 JSON 字符串），
在两个 Provider 里要做完全不同的事：

```java
// OpenAI：原样透传字符串
call.putObject("function").put("arguments", c.arguments());

// Anthropic：必须解析成对象
use.set("input", json.readTree(c.arguments()));
```

> 💡 还记得 M2 那个设计决定吗——`ToolCall.arguments` 存**字符串**而不是 `JsonNode`。
> 当时的理由是"原样透传最安全"。到了 M5 你会发现：
> **存字符串让 OpenAI 侧零成本，让 Anthropic 侧多一次解析。反过来存对象则两者对调。**
> 没有绝对更好的选择——**只有"知道代价在哪"的选择。**

### 差异 3 · SSE 的结构（连行都不一样）

**OpenAI 格式**：
```
data: {"choices":[{"delta":{"content":"Hi"}}]}
data: [DONE]
```

**Anthropic 格式**：
```
event: message_start
data: {"type":"message_start","message":{...}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

event: ping
data: {"type":"ping"}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{...}}

event: message_stop
data: {"type":"message_stop"}
```

三个直观区别：

| | OpenAI 格式 | Anthropic 格式 |
|---|---|---|
| 行结构 | 只有 `data:` | 有 `event:` **和** `data:` |
| 结束标记 | `data: [DONE]` 哨兵 | **没有哨兵**，连接直接关闭 |
| 心跳 | 无 | `event: ping` |

以及一个不那么直观的：

| | OpenAI 格式 | Anthropic 格式 |
|---|---|---|
| 工具参数 | `delta.tool_calls[].function.arguments` 分片 | `content_block_delta` + `input_json_delta.partial_json` 分片 |
| 记哪个 key | `index`（provider 内部映射到 id） | `index`（同样映射到 id） |

**好消息**：分片累积这件事，两个协议是**一样的**（都要按 index 拼 JSON）。
所以 `AssistantTurn` 完全不用改——它拿到的还是 `StreamEvent`，一视同仁。

### 差异 4 · 抹不平的那处 ⚠️

**Anthropic 的消息格式里，只有 `user` 和 `assistant` 两种角色。**

`system` 是**顶层参数**，不是消息。而且**没有"对话中途的 system 消息"这种东西**。

但我们的 M4 压缩器，恰恰要往对话中间插一条 `system` 摘要。

**怎么办？** 只能降级成一条 `user` 消息：

```java
case "system" -> {
    if (inLeadingSystemRun) {
        // 开头的系统提示词，已经被 collectSystem 拿走放进顶层了
    } else {
        // ⚠️ 中途插入的 system（M4 压缩摘要）——只能降级成 user
        out.add(userText(m.content()));
    }
}
```

**语义被轻微扭曲了**：模型可能会把摘要当成"用户说的话"而不是"系统背景"。

**这是抽象的裂缝，而且它不可能被修好**——因为两个协议在这里真的不一样。

> 💡 **记住这个发现：抽象的价值在于"能抹平的地方全部抹平"，
> 而不是"假装所有地方都能抹平"。**
> 好的抽象会**把差异集中到一个已知的点上**，让你知道裂缝在哪，
> 而不是把裂缝藏起来等你踩。
>
> 我们在 `AnthropicProvider` 的类注释里专门写了一段来标注它：
> ```java
> * <h3>还有一处它抹不平（这是本节最值钱的发现）</h3>
> * Anthropic 格式<b>没有"对话中途的 system 消息"</b>。
> ```

---

## 3. `AnthropicProvider.java` ★

```java
package dev.learn.agent.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learn.agent.provider.ChatRequest;
import dev.learn.agent.provider.Message;
import dev.learn.agent.provider.Provider;
import dev.learn.agent.provider.StopReason;
import dev.learn.agent.provider.StreamEvent;
import dev.learn.agent.provider.ToolCall;
import dev.learn.agent.provider.ToolSpec;
import dev.learn.agent.provider.Usage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 第二个 Provider 实现：Anthropic Messages API 格式。
 *
 * <p>它连的是 DeepSeek 的 Anthropic 兼容端点
 * （{@code https://api.deepseek.com/anthropic}）。<b>同一个账号、同一个模型，
 * 完全不同的协议。</b>这是验证抽象最干净的方式——因为任何行为差异都只能来自协议，
 * 不可能来自模型能力。
 *
 * <h3>它抹平了三处差异</h3>
 * <ol>
 *   <li><b>工具结果的消息形态</b>：Anthropic 要求所有工具结果塞进<b>同一条</b> user 消息；
 *       OpenAI 要求每个结果<b>独立</b>成一条。上层完全不用知道。</li>
 *   <li><b>工具参数的序列化</b>：Anthropic 的 {@code input} 是 JSON <b>对象</b>；
 *       OpenAI 的 {@code arguments} 是 JSON <b>字符串</b>。</li>
 *   <li><b>SSE 的行结构</b>：Anthropic 有 {@code event:} 行、有 {@code ping} 事件、
 *       没有 {@code [DONE]} 哨兵。</li>
 * </ol>
 *
 * <h3>还有一处它抹不平（这是本节最值钱的发现）</h3>
 * Anthropic 格式<b>没有"对话中途的 system 消息"</b>。
 * 而我们的 M4 压缩器恰恰要在中间插一条 system 摘要。
 * Provider 只能把它降级成一条 user 消息——语义变了，但这是它能做的最好选择。
 * <b>抽象的裂缝，就出现在两个协议真正不同的地方。</b>
 */
public class AnthropicProvider implements Provider {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DATA_PREFIX = "data:";

    private final String apiKey;
    private final String baseUrl;
    private final int maxTokens;
    private final boolean dumpRaw;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public AnthropicProvider(String apiKey, boolean dumpRaw) {
        this(apiKey, DEFAULT_BASE_URL, 8192, dumpRaw);
    }

    public AnthropicProvider(String apiKey, String baseUrl, int maxTokens, boolean dumpRaw) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.maxTokens = maxTokens;
        this.dumpRaw = dumpRaw;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamEvent> onEvent) {
        try {
            HttpResponse<InputStream> response = http.send(
                    buildHttpRequest(request),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                onEvent.accept(new StreamEvent.Failure(
                        "HTTP " + response.statusCode() + " — " + body));
                return;
            }

            readStream(response.body(), onEvent);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onEvent.accept(new StreamEvent.Failure("请求被中断"));
        } catch (Exception e) {
            onEvent.accept(new StreamEvent.Failure(
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  请求构建
    // ═══════════════════════════════════════════════════════════

    private HttpRequest buildHttpRequest(ChatRequest request) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                // ⚠️ Anthropic 用 x-api-key，不是 Authorization: Bearer
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(request), StandardCharsets.UTF_8))
                .build();
    }

    private String buildBody(ChatRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", request.model());
        // ⚠️ Anthropic 格式里 max_tokens 是必填的。OpenAI 格式里是可选。
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.putObject("thinking").put("type", request.thinking() ? "enabled" : "disabled");

        // ⚠️ system 是顶层参数，不是消息！（差异 4 的根源）
        String system = collectSystem(request.messages());
        if (!system.isEmpty()) {
            body.put("system", system);
        }

        ArrayNode messages = body.putArray("messages");
        appendMessages(request.messages(), messages);

        if (!request.tools().isEmpty()) {
            body.set("tools", toolsToJson(request.tools()));
        }
        return body.toString();
    }

    /**
     * 把<b>开头的连续 system 消息</b>拼成顶层的 system 字段。
     *
     * <p>⚠️ 注意这里有个坑：不能收集"所有" system 消息。
     * M4 的压缩会在对话中途插一条 system 摘要消息，如果把它也塞进顶层 system，
     * 那么（a）它会和降级成的 user 消息重复发一遍，
     * （b）更糟的是 —— <b>顶层的 system 变了，整个缓存前缀就失效了</b>。
     * 实测：这个 bug 让压缩后的输入 token 多了约 190。
     */
    private static String collectSystem(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (!"system".equals(m.role())) {
                break;      // 只看开头那一段连续的 system
            }
            if (m.content() != null && !m.content().isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(m.content());
            }
        }
        return sb.toString();
    }

    private void appendMessages(List<Message> messages, ArrayNode out) {
        boolean inLeadingSystemRun = true;
        int i = 0;
        while (i < messages.size()) {
            Message m = messages.get(i);

            switch (m.role()) {
                case "system" -> {
                    if (inLeadingSystemRun) {
                        // 开头的系统提示词，已经被 collectSystem 拿走放进顶层了
                    } else {
                        // ⚠️ 中途插入的 system（M4 压缩摘要）——
                        //    Anthropic 没有"对话中途的 system 消息"，只能降级成 user。
                        out.add(userText(m.content() == null ? "" : m.content()));
                    }
                    i++;
                }

                case "user" -> {
                    inLeadingSystemRun = false;
                    out.add(userText(m.content() == null ? "" : m.content()));
                    i++;
                }

                case "assistant" -> {
                    inLeadingSystemRun = false;
                    out.add(assistantMessage(m));
                    i++;
                }

                case "tool" -> {
                    inLeadingSystemRun = false;
                    // ★ 差异 1：把所有连续的 tool 结果合并进一条 user 消息
                    ArrayNode blocks = json.createArrayNode();
                    while (i < messages.size() && "tool".equals(messages.get(i).role())) {
                        Message toolMsg = messages.get(i);
                        ObjectNode block = blocks.addObject();
                        block.put("type", "tool_result");
                        block.put("tool_use_id", toolMsg.toolCallId());
                        block.put("content", toolMsg.content() == null ? "" : toolMsg.content());
                        i++;
                    }
                    ObjectNode userMsg = json.createObjectNode();
                    userMsg.put("role", "user");
                    userMsg.set("content", blocks);
                    out.add(userMsg);
                }

                default -> i++;
            }
        }
    }

    private ObjectNode userText(String text) {
        ObjectNode node = json.createObjectNode();
        node.put("role", "user");
        ArrayNode blocks = node.putArray("content");
        blocks.addObject().put("type", "text").put("text", text);
        return node;
    }

    private ObjectNode assistantMessage(Message m) {
        ObjectNode node = json.createObjectNode();
        node.put("role", "assistant");
        ArrayNode blocks = node.putArray("content");

        if (m.content() != null && !m.content().isEmpty()) {
            blocks.addObject().put("type", "text").put("text", m.content());
        }

        if (m.toolCalls() != null) {
            for (ToolCall call : m.toolCalls()) {
                ObjectNode use = blocks.addObject();
                use.put("type", "tool_use");
                use.put("id", call.id());
                use.put("name", call.name());
                // ★ 差异 2：input 必须是 JSON 对象，不是字符串。
                try {
                    use.set("input", json.readTree(call.arguments()));
                } catch (Exception e) {
                    use.set("input", json.createObjectNode());
                }
            }
        }

        // Anthropic 不允许空 content 数组
        if (blocks.isEmpty()) {
            blocks.addObject().put("type", "text").put("text", "");
        }
        return node;
    }

    /** ToolSpec → Anthropic 格式（注意是 input_schema，且没有 function 包装层）。 */
    private ArrayNode toolsToJson(List<ToolSpec> specs) {
        ArrayNode tools = json.createArrayNode();
        for (ToolSpec spec : specs) {
            ObjectNode tool = tools.addObject();
            tool.put("name", spec.name());
            tool.put("description", spec.description());
            tool.set("input_schema", spec.parametersSchema());
        }
        return tools;
    }

    // ═══════════════════════════════════════════════════════════
    //  流解析（差异 3）
    // ═══════════════════════════════════════════════════════════

    private void readStream(InputStream body, Consumer<StreamEvent> onEvent) throws Exception {
        Map<Integer, String> blockIds = new HashMap<>();
        Accumulator acc = new Accumulator();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (dumpRaw) {
                    System.out.println("[RAW] " + line);
                }
                if (line.isBlank() || line.startsWith(":")) {
                    continue;
                }
                // ⚠️ Anthropic 的 SSE 有 `event: xxx` 行。我们不需要它 ——
                //    因为 JSON 负载里本来就有 `type` 字段。跳过即可。
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }

                String payload = line.substring(DATA_PREFIX.length()).trim();
                JsonNode frame;
                try {
                    frame = json.readTree(payload);
                } catch (Exception e) {
                    continue;   // 解析不了的帧（比如心跳）直接跳过
                }
                handleEvent(frame, blockIds, acc, onEvent);
            }
        }
    }

    /** 一次流里累积的状态。Anthropic 把用量拆在 message_start 和 message_delta 两处。 */
    private static final class Accumulator {
        int inputTokens;
        int outputTokens;
        int cacheReadTokens;
        int cacheCreationTokens;

        /**
         * ⚠️ 这里有一处必须"翻译"的语义差异，否则你的成本统计会错得离谱。
         *
         * <p>两个协议对"输入 token"的定义不一样：
         * <pre>
         *   OpenAI/DeepSeek:  prompt_tokens = 全部输入（命中 + 未命中）
         *   Anthropic:        input_tokens  = 只是【未命中缓存】的那部分
         *                     总输入 = input_tokens + cache_read + cache_creation
         * </pre>
         *
         * <p>实测：同一份对话，OpenAI 格式报 prompt_tokens=1607，
         * Anthropic 格式报 input_tokens=199 + cache_read=1408 = 1607。
         * <b>数字一样，但字段的含义完全不同。</b>
         *
         * <p>我们的 Usage.promptTokens 约定为"总输入"（OpenAI 语义），
         * 所以这里必须把三部分加起来。<b>这就是 Provider 该做的事——
         * 把方言翻译成统一的语义，而不只是字段改名。</b>
         */
        Usage usage() {
            return new Usage(
                    inputTokens + cacheReadTokens + cacheCreationTokens,
                    outputTokens,
                    cacheReadTokens);
        }
    }

    private void handleEvent(JsonNode frame,
                             Map<Integer, String> blockIds,
                             Accumulator acc,
                             Consumer<StreamEvent> onEvent) {

        String type = frame.path("type").asText("");

        switch (type) {
            case "message_start" -> {
                JsonNode usage = frame.path("message").path("usage");
                if (usage.isObject()) {
                    acc.inputTokens = usage.path("input_tokens").asInt(acc.inputTokens);
                    acc.cacheReadTokens =
                            usage.path("cache_read_input_tokens").asInt(acc.cacheReadTokens);
                    acc.cacheCreationTokens =
                            usage.path("cache_creation_input_tokens").asInt(acc.cacheCreationTokens);
                }
                // message_start 里的 stop_reason 一定是 null，不在这里发 Done
            }

            case "content_block_start" -> {
                JsonNode block = frame.path("content_block");
                if ("tool_use".equals(block.path("type").asText())) {
                    int index = frame.path("index").asInt();
                    String id = block.path("id").asText();
                    String name = block.path("name").asText();
                    blockIds.put(index, id);
                    onEvent.accept(new StreamEvent.ToolUseStart(id, name));
                }
            }

            case "content_block_delta" -> {
                JsonNode delta = frame.path("delta");
                String deltaType = delta.path("type").asText("");
                switch (deltaType) {
                    case "text_delta" -> {
                        String text = delta.path("text").asText("");
                        if (!text.isEmpty()) {
                            onEvent.accept(new StreamEvent.TextDelta(text));
                        }
                    }
                    case "thinking_delta" -> {
                        String text = delta.path("thinking").asText("");
                        if (!text.isEmpty()) {
                            onEvent.accept(new StreamEvent.ReasoningDelta(text));
                        }
                    }
                    case "input_json_delta" -> {
                        int index = frame.path("index").asInt();
                        String id = blockIds.get(index);
                        String partial = delta.path("partial_json").asText("");
                        if (id != null && !partial.isEmpty()) {
                            onEvent.accept(new StreamEvent.ToolUseArgsDelta(id, partial));
                        }
                    }
                    default -> {
                        // signature_delta 等，忽略
                    }
                }
            }

            case "message_delta" -> {
                JsonNode usage = frame.path("usage");
                if (usage.isObject()) {
                    acc.outputTokens = usage.path("output_tokens").asInt(acc.outputTokens);
                    acc.inputTokens = usage.path("input_tokens").asInt(acc.inputTokens);
                    acc.cacheReadTokens =
                            usage.path("cache_read_input_tokens").asInt(acc.cacheReadTokens);
                    acc.cacheCreationTokens =
                            usage.path("cache_creation_input_tokens").asInt(acc.cacheCreationTokens);
                }
                JsonNode stopReason = frame.path("delta").path("stop_reason");
                if (stopReason.isTextual()) {
                    onEvent.accept(new StreamEvent.Done(
                            mapStopReason(stopReason.asText()), acc.usage()));
                }
            }

            case "error" -> onEvent.accept(new StreamEvent.Failure(
                    frame.path("error").path("message").asText(frame.toString())));

            default -> {
                // ping / content_block_stop / message_stop 都不用处理。
                // ⚠️ 注意这里<b>没有 [DONE] 哨兵</b> —— 连接直接关闭就是结束。
            }
        }
    }

    private static StopReason mapStopReason(String reason) {
        return switch (reason) {
            case "end_turn", "stop_sequence" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }
}
```

### 回头看：M1 的那个 `StopReason` 枚举

```java
case "end_turn", "stop_sequence" -> StopReason.END_TURN;
case "tool_use"                  -> StopReason.TOOL_USE;
case "max_tokens"                -> StopReason.MAX_TOKENS;
```

**M1 的时候你可能觉得 `StopReason` 有点多余** —— OpenRouter 那边直接用字符串不就行了？

现在答案清楚了：**这个映射就是抽象的落点**。两个协议用两套词表描述同一件事
（`stop` / `tool_calls` / `length` vs `end_turn` / `tool_use` / `max_tokens`），
上层只认识一个枚举。**如果没有它，`Agent` 里就要写两套判断。**

---

## 4. 跑起来：同一问题，两个协议

```bash
cd /d/codeSpace/agent
mvn -q compile

# OpenAI 格式
mvn -q compile exec:java -Dexec.args="--yes"

# Anthropic 格式（同一套代码，只改了启动参数）
mvn -q compile exec:java -Dexec.args="--yes --provider=anthropic"
```

问同一个需要工具的问题：

```
How many .java files are in this project, and what does Calculator.add do?
```

### 实测结果

**OpenAI 格式：**
```
[轮次结束 TOOL_USE  in=1607 out=92 cache=1408]
[轮次结束 TOOL_USE  in=1778 out=44 cache=1536]
[轮次结束 END_TURN  in=1973 out=91 cache=1792]
```

**Anthropic 格式：**
```
[轮次结束 TOOL_USE  in=1607 out=91 cache=1408]
[轮次结束 TOOL_USE  in=1777 out=44 cache=1536]
[轮次结束 END_TURN  in=1972 out=92 cache=1792]
```

**两边的数字几乎完全一致**（差 1 个 token，来自工具定义编码的细微差别）。

而且两边**都完成了**：
- glob + grep 并行调用（差异 1 的合并逻辑生效）
- read_file 第二轮循环
- 给出正确答案

**这就是抽象成立的证据**——不只是"能跑"，而是"跑出来的数字含义相同"。

---

## 5. 重大发现：`input_tokens` 的语义不一样 ⚠️

在修好之前，Anthropic 格式报的是 `in=199`，而 OpenAI 格式报 `in=1547`。

**差 7 倍**，但两边做的是同一件事。这说明**字段含义不同，不是真的省了 token**。

### 真相

```json
// OpenAI 格式的 usage
{
  "prompt_tokens": 1607,              ← 全部输入（命中 + 未命中）
  "prompt_cache_hit_tokens": 1408,
  "prompt_cache_miss_tokens": 199
}

// Anthropic 格式的 usage
{
  "input_tokens": 199,                ← 只是【未命中】的那部分！
  "cache_read_input_tokens": 1408,
  "cache_creation_input_tokens": 0
}
```

**`input_tokens` 不是总输入，而是"新读进去的"部分。**
总输入 = `input_tokens + cache_read + cache_creation` = 199 + 1408 + 0 = **1607**。

### 我们怎么处理的

```java
Usage usage() {
    return new Usage(
            inputTokens + cacheReadTokens + cacheCreationTokens,   // ← 加起来
            outputTokens,
            cacheReadTokens);
}
```

**统一成 OpenAI 语义（"promptTokens = 总输入"）**，因为这是字段名 `promptTokens` 的自然含义。

### 为什么这个发现重要

> 如果只是"字段改名"（把 `input_tokens` 直接塞进 `promptTokens`），
> 你的 M4 压缩判断会**永远不触发**——因为它以为输入只有 199 token。
>
> **而且它不会报错。** 它只会静默地不工作。

**这是抽象最容易出错的地方：字段名对上了，语义没对上。**

> ⚠️ 记住这条：**做 Provider 抽象的时候，最危险的不是"这个字段在哪"，
> 而是"这个字段到底是什么意思"。**
> 有没有缓存命中？计不计入总量？包不包括系统提示？
> 这些问题的答案在两家 API 里经常不一样，而文档通常不会主动告诉你。

---

## 6. 第二个发现：压缩后缓存的表现不同

M4 里我们看到压缩会让缓存失效。在 Anthropic 格式下又测了一遍：

| | OpenAI 格式 | Anthropic 格式 |
|---|---|---|
| 压缩前 | cache = 4864 | cache = 4864 |
| **压缩后第 1 次请求** | cache = **1536** | cache = **0** |
| 压缩后第 2 次请求 | cache = 1664 | cache = 1664 |

### 怎么解读

**共同点**：两个协议在压缩后都会**大量失去缓存**，但**都会在下一次请求重建**
（1664）。**缓存失效是一次性的，不是永久的。**

**差异**：OpenAI 格式保住了 1536，Anthropic 格式是 0。

**为什么？** 我的推测是：OpenAI 格式下，我们的系统提示词是 `messages[0]`，
压缩后位置和内容都没变，所以 `[工具定义 + 系统提示词]` 这一段前缀仍然能命中。
而 Anthropic 格式下，`system` 被提到了顶层、`messages[0]` 变成了那条摘要
（在 Anthropic 格式里被降级成了 user 消息），所以从 messages 的第一个字节就不一样了。

⚠️ **我要诚实地说：这是推测，我没有从文档里找到确证。**
DeepSeek 的 Anthropic 兼容端点具体怎么切缓存单元，官方没有说明。

**但这不影响结论**——**结论是"你要测"。**

> 💡 **这一节的真正教训不是"哪个协议缓存更好"，
> 而是"同一段业务逻辑，在两个协议下的成本表现可能不同，而你只能通过测量知道"。**

### 这次差 1536 token 值多少钱？

1536 token 按未命中价（$0.15/1M）是 $0.00023，按命中价（$0.003/1M）是 $0.0000046。
**差 50 倍，但绝对值是两万分之一美元。**

所以对学习项目来说，**这个差异完全可以忽略**。
但它让你看到了一件事：**成本的杠杆在"缓存命中率"上，而不在"少说几句话"上。**

---

## 7. 一个 bug 和它教的东西

写 `AnthropicProvider` 的时候我犯了一个错，值得单独讲。

### 错误的代码

```java
// ❌ 收集了【所有】 system 消息
private static String collectSystem(List<Message> messages) {
    StringBuilder sb = new StringBuilder();
    for (Message m : messages) {
        if ("system".equals(m.role()) && m.content() != null) {   // ← 没有 break
            sb.append(m.content());
        }
    }
    return sb.toString();
}
```

同时 `appendMessages` 又把中途的 system 降级成了 user 消息。

### 后果

1. **摘要被发了两遍**：一次在顶层 `system` 里，一次作为 user 消息。
   输入 token 多了约 190（`in=2035` vs 修好后的 `in=1842`）。
2. **更糟的是**：顶层 `system` 变了 → 整个缓存前缀失效。

### 教训

> **一个"看起来只是多收集了一点"的循环条件，同时影响了正确性和成本。**
>
> `for (...) { if (...) {...} }` 和 `for (...) { if (!...) break; if (...) {...} }`
> 差一个 `break`，语义从"收集全部"变成"收集开头那一段"。
> **在错误的地方，这个差别是"功能挂掉"；在正确的地方，它是"缓存全废"——
> 后者更隐蔽，因为它不报错。**

这和第 5 节的 `input_tokens` 是同一个主题：**看不见的错误最贵。**

---

## 8. 第二部分：MCP

### 8.1 MCP 是什么

**剥掉所有包装，MCP 就是 JSON-RPC 2.0。**

```
你 → 子进程 stdin:   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}\n
你 ← 子进程 stdout:  {"jsonrpc":"2.0","id":1,"result":{...}}\n
```

**一行一个 JSON，换行分隔。** 没有 HTTP、没有端口、没有鉴权、没有复杂的状态机。

它解决的问题是：

> **你想让 agent 用上一万个别人写好的工具，但你不想把那一万个工具的实现
> 全部写进你的代码里。**

**类比**：MCP 就是"工具的 USB 接口"。

```
没有 MCP：
  你想接 GitHub → 写 GitHub 的集成
  你想接数据库 → 写数据库的集成
  你想接 Slack  → 写 Slack 的集成
  ...每个都要写一遍，每个都在你的进程里跑

有 MCP：
  大家都实现同一个"插头标准"（MCP）
  你的 agent 只实现一次"插座"（McpClient）
  然后想插谁插谁
```

### 8.2 三个必须守的规矩

1. **`stdout` 是协议专用的。** 服务器往 stdout 打任何日志都会破坏协议。日志必须走 stderr。
2. **必须先 `initialize` 握手。** 之后才能调别的。
3. **`id` 要自己管。** 请求和响应靠 id 配对。

### 8.3 我们只实现三个方法

MCP 规范有很多方法，但我们这个 agent 只需要三个：

| 方法 | 干什么 |
|---|---|
| `initialize` | 握手，交换能力 |
| `tools/list` | 问服务器"你有什么工具" |
| `tools/call` | 调用一个工具 |

---

## 9. `McpClient.java` ★

```java
package dev.learn.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个最小的 MCP 客户端（stdio 传输）。
 *
 * <h3>MCP 协议其实就是 JSON-RPC 2.0</h3>
 * 剥掉所有包装，MCP 就是：
 * <pre>
 *   你 → 子进程 stdin:  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}\n
 *   你 ← 子进程 stdout: {"jsonrpc":"2.0","id":1,"result":{...}}\n
 * </pre>
 * <b>一行一个 JSON，换行分隔。</b>就这么简单。
 */
public final class McpClient implements AutoCloseable {

    /** 我们声明的协议版本。服务器如果支持会回同一个，否则回它自己的。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final Process process;
    private final BufferedWriter toServer;
    private final BufferedReader fromServer;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * ⚠️ 所有请求串行化。
     *
     * <p>因为我们的工具是<b>并发执行</b>的（M2 用的虚拟线程），
     * 如果两个 MCP 工具同时调用，两个线程会同时往 stdin 写、同时从 stdout 读，
     * 响应就串了。加锁是最省事的正确做法。
     *
     * <p>真实产品会做 id → Future 的映射表来支持并发。对学习项目来说，
     * 串行化的 MCP 调用完全够用。
     */
    private final Object lock = new Object();

    /** 服务器在握手时自报的名字，用来给工具名加前缀。 */
    private String serverName = "unknown";

    private McpClient(Process process) {
        this.process = process;
        this.toServer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromServer = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    public static McpClient start(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        // ⚠️ stderr 必须单独处理。如果让它混进 stdout，协议就废了。
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);

        McpClient client = new McpClient(builder.start());
        client.initialize();
        return client;
    }

    /** 握手。成功后服务器才认识我们。 */
    public JsonNode initialize() throws IOException {
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", json.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "learn-agent");
        clientInfo.put("version", "0.1.0");

        JsonNode result = request("initialize", params);

        String reported = result.path("serverInfo").path("name").asText("");
        if (!reported.isBlank()) {
            this.serverName = reported;
        }

        // ⚠️ 握手后必须发这个"通知"。它没有 id，服务器不会回。
        notify("notifications/initialized", json.createObjectNode());
        return result;
    }

    /** 服务器自报的名字。工具名前缀用它。 */
    public String serverName() {
        return serverName;
    }

    /** 列出服务器提供的所有工具。 */
    public List<JsonNode> listTools() throws IOException {
        JsonNode result = request("tools/list", json.createObjectNode());
        List<JsonNode> tools = new ArrayList<>();
        result.path("tools").forEach(tools::add);
        return tools;
    }

    /**
     * 调用一个工具，返回它产出的文本。
     *
     * <p>MCP 的工具结果是一组 content 块（可以是 text / image / resource）。
     * 我们只取文本 —— 编码工作不需要图片。
     */
    public String callTool(String name, JsonNode arguments) throws IOException {
        ObjectNode params = json.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? json.createObjectNode() : arguments);

        JsonNode result = request("tools/call", params);

        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            }
        }
        if (sb.isEmpty()) {
            return "（MCP 工具没有返回文本内容）";
        }
        // ⚠️ 工具自己报告的失败也在 result 里，靠 isError 标记
        if (result.path("isError").asBoolean(false)) {
            return "MCP 工具报告错误：" + sb;
        }
        return sb.toString();
    }

    // ───────────────────────────────────────────────────────────

    private JsonNode request(String method, JsonNode params) throws IOException {
        synchronized (lock) {
            int id = nextId.getAndIncrement();
            ObjectNode msg = json.createObjectNode();
            msg.put("jsonrpc", "2.0");
            msg.put("id", id);
            msg.put("method", method);
            msg.set("params", params);
            writeLine(msg);

            // 一直读到 id 对上的那条。中间可能有服务器发来的通知（没有 id），跳过。
            String line;
            while ((line = fromServer.readLine()) != null) {
                JsonNode frame;
                try {
                    frame = json.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                if (frame.path("id").asInt(-1) != id) {
                    continue;   // 不是给我们的
                }
                if (frame.has("error")) {
                    throw new IOException("MCP 错误：" + frame.path("error").path("message").asText());
                }
                return frame.path("result");
            }
            throw new IOException("MCP 服务器关闭了连接（method=" + method + "）");
        }
    }

    private void notify(String method, JsonNode params) throws IOException {
        synchronized (lock) {
            ObjectNode msg = json.createObjectNode();
            msg.put("jsonrpc", "2.0");
            msg.put("method", method);
            msg.set("params", params);
            writeLine(msg);
        }
    }

    private void writeLine(JsonNode msg) throws IOException {
        toServer.write(msg.toString());
        toServer.write('\n');
        toServer.flush();
    }

    @Override
    public void close() {
        try {
            toServer.close();
        } catch (IOException ignored) {
            // 关闭失败没什么可做的
        }
        process.destroy();
    }
}
```

---

## 10. `McpToolAdapter.java` ★ 本节最关键的一个类

**它只有几十行。但它让一整个外部工具生态变成了 `ToolRegistry` 里的普通一员。**

```java
package dev.learn.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.Tool;
import dev.learn.agent.tool.ToolContext;

/**
 * 把一个 MCP 工具包装成我们自己的 {@link Tool}。
 *
 * <h3>这就是 MCP 能被接进来的全部原因</h3>
 * 这个类只有几十行，但它让<b>一整个外部工具生态</b>变成了我们
 * {@code ToolRegistry} 里的普通一员。{@code Agent}、{@code PermissionManager}、
 * {@code Compactor} 一个字都不用改，甚至连"这是 MCP 工具"都不知道。
 *
 * <p><b>这就是 M5 要验证的那件事：一个好的抽象能让新东西"插进来"而不是"改进去"。</b>
 *
 * <h3>名字为什么加前缀</h3>
 * MCP 工具的名字由服务器决定，我们控制不了。如果某个服务器提供一个叫
 * {@code read_file} 的工具，就会和我们的本地工具<b>静默冲突</b>——
 * 后注册的把先注册的覆盖掉，而你不会收到任何警告。
 *
 * <p>所以统一加前缀 {@code mcp__<服务器名>__<工具名>}。
 */
public final class McpToolAdapter implements Tool {

    private final McpClient client;
    private final String serverName;
    private final String remoteName;
    private final String exposedName;
    private final String description;
    private final JsonNode inputSchema;

    public McpToolAdapter(McpClient client, String serverName, JsonNode toolDefinition) {
        this.client = client;
        this.serverName = serverName;
        this.remoteName = toolDefinition.path("name").asText();
        this.exposedName = "mcp__" + serverName + "__" + remoteName;
        this.description = toolDefinition.path("description").asText("(MCP 工具，没有提供描述)");
        this.inputSchema = toolDefinition.path("inputSchema").isObject()
                ? toolDefinition.path("inputSchema")
                : null;
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String description() {
        // 把来源标出来，对模型理解工具的用途有帮助
        return "（来自 MCP 服务器 " + serverName + "）\n" + description;
    }

    @Override
    public JsonNode parametersSchema() {
        if (inputSchema != null) {
            return inputSchema;
        }
        // ⚠️ MCP 规范里 inputSchema 是必填的，但总有服务器不守规矩。
        //    给一个空 schema 至少能让它被列出来，而不是整个注册流程崩掉。
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("type", "object");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        try {
            return client.callTool(remoteName, args);
        } catch (Exception e) {
            // 💡 和其他工具一样：失败是信息，不是终止。
            return "调用 MCP 工具 " + remoteName + " 失败：" + e.getMessage();
        }
    }

    // ── 权限：MCP 工具是"外来的"，我们不知道它背后做什么 ──

    @Override
    public String describeAction(JsonNode args, ToolContext ctx) {
        return "调用外部 MCP 工具 " + serverName + " / " + remoteName
                + "\n    参数：" + args;
    }

    /**
     * ⚠️ 粒度是<b>具体工具</b>而不是整个服务器。
     * 因为一个 MCP 服务器上可能既有只读工具也有破坏性工具，
     * 按服务器授权等于把整个服务器的权限一起给出去。
     */
    @Override
    public String permissionScope(JsonNode args, ToolContext ctx) {
        return exposedName;
    }
}
```

### 请停下来欣赏一下这个类

**它没有任何"agent"逻辑。** 它不调模型、不管循环、不碰上下文、不做权限判断。

它只做一件事：**把"MCP 的 tools/call"翻译成 Tool 接口的 `execute`。**

而因为这个翻译存在：

| M2 写的 | 需要改吗？ |
|---|---|
| `Agent` 的循环 | ❌ 不用 |
| `ToolRegistry` 的并发执行 | ❌ 不用 |
| `PermissionManager` 的审批 | ❌ 不用 |
| `DangerDetector` 的风险识别 | ❌ 不用 |
| `Compactor` 的上下文压缩 | ❌ 不用 |
| `Main` 的 REPL | ❌ 不用 |

**MCP 工具自动获得了所有这些能力**——因为它们是"普通工具"。

> 💡 **这就是 M2 那句"工具定义了能做什么，权限定义了允许做什么"的完整含义。**
> 只要你接进 `Tool` 接口，你就免费获得了整个 agent 的基础设施。

---

## 11. `DemoMcpServer.java` —— 一个能跑的 MCP 服务器

为了能测，我们自己写一个。**不到 100 行**——因为 MCP 真的就这么简单。

```java
package dev.learn.agent.mcp.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 一个最小的 MCP 服务器，用来测试我们的客户端。
 *
 * <p><b>它不是给生产用的</b>，是给你"看清 MCP 协议到底在交换什么"用的。
 * 真正的 MCP server 用 Python/Node/Java 都有官方 SDK，你不需要手写这个。
 * 手写一遍的目的是<b>去掉黑箱感</b>。
 */
public final class DemoMcpServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        System.err.println("[demo-mcp] 启动，等待握手……");

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode msg;
            try {
                msg = JSON.readTree(line);
            } catch (Exception e) {
                System.err.println("[demo-mcp] 收到无法解析的内容：" + line);
                continue;
            }

            String method = msg.path("method").asText("");
            boolean isNotification = !msg.has("id");

            switch (method) {
                case "initialize" -> respond(out, msg, initializeResult());
                case "notifications/initialized" ->
                        System.err.println("[demo-mcp] 客户端握手完成");
                case "tools/list" -> respond(out, msg, toolsListResult());
                case "tools/call" -> respond(out, msg, callTool(msg.path("params")));
                default -> {
                    if (!isNotification) {
                        respondError(out, msg, -32601, "不支持的方法：" + method);
                    }
                }
            }
        }
        System.err.println("[demo-mcp] stdin 关闭，退出");
    }

    private static JsonNode initializeResult() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "demo-mcp");
        serverInfo.put("version", "0.1.0");
        return result;
    }

    private static JsonNode toolsListResult() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        ObjectNode time = tools.addObject();
        time.put("name", "current_time");
        time.put("description", "返回服务器当前的日期和时间。用户问'现在几点''今天几号'时使用。");
        ObjectNode timeSchema = time.putObject("inputSchema");
        timeSchema.put("type", "object");
        timeSchema.putObject("properties");

        ObjectNode wc = tools.addObject();
        wc.put("name", "word_count");
        wc.put("description", "统计一段文本的字符数和行数。");
        ObjectNode wcSchema = wc.putObject("inputSchema");
        wcSchema.put("type", "object");
        wcSchema.putObject("properties")
                .putObject("text").put("type", "string").put("description", "要统计的文本");
        wcSchema.putArray("required").add("text");

        return result;
    }

    private static JsonNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");

        String text;
        boolean isError = false;

        switch (name) {
            case "current_time" -> text = ZonedDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));

            case "word_count" -> {
                String input = arguments.path("text").asText("");
                if (input.isEmpty()) {
                    text = "错误：text 参数不能为空";
                    isError = true;
                } else {
                    text = "字符数：" + input.length()
                            + "，行数：" + input.lines().count()
                            + "，按空格分词：" + input.strip().split("\\s+").length + " 个";
                }
            }

            default -> {
                text = "没有名为 " + name + " 的工具";
                isError = true;
            }
        }

        ObjectNode result = JSON.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", isError);
        return result;
    }

    // ───────────────────────────────────────────────────────────

    private static void respond(BufferedWriter out, JsonNode request, JsonNode result)
            throws Exception {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.path("id"));
        response.set("result", result);
        write(out, response);
    }

    private static void respondError(BufferedWriter out, JsonNode request, int code, String message)
            throws Exception {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.path("id"));
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        write(out, response);
    }

    private static void write(BufferedWriter out, JsonNode msg) throws Exception {
        out.write(msg.toString());
        out.write('\n');
        out.flush();
    }
}
```

---

## 12. 在 `Main` 里挂载 MCP

```java
        // ── M5：可选挂载 MCP 服务器。挂上之后它的工具就变成普通工具 ──
        if (mcpCommand != null && !mcpCommand.isBlank()) {
            // 便捷写法：--mcp=demo 会用当前 classpath 启动自带的演示服务器
            if ("demo".equals(mcpCommand)) {
                String javaBin = ProcessHandle.current().info().command().orElse("java");
                mcpCommand = javaBin + " -cp " + System.getProperty("java.class.path")
                        + " dev.learn.agent.mcp.demo.DemoMcpServer";
            }
            try {
                McpClient client = McpClient.start(List.of(mcpCommand.split("\\s+")));
                mcpClients.add(client);
                int attached = 0;
                for (JsonNode definition : client.listTools()) {
                    tools.register(new McpToolAdapter(client, client.serverName(), definition));
                    attached++;
                }
                System.out.printf("MCP[%s] 已挂载 %d 个工具%n", client.serverName(), attached);
            } catch (Exception e) {
                System.err.println("MCP 服务器启动失败：" + e.getMessage());
            }
        }
```

> 💡 **注意这段代码在 `Main` 里，不在 `Agent` 里。**
> 这是刻意的：**挂载 MCP 是一个"启动配置"动作，不是 agent 逻辑。**
> 真实产品里它等价于读配置文件（`.mcp.json` 之类），
> 然后"对每个配置项启动一个服务器，把工具注册进去"。
>
> **如果这段代码被迫写进 `Agent`，说明抽象又切错了位置。**

---

## 13. 跑起来

```bash
cd /d/codeSpace/agent
mvn -q compile exec:java -Dexec.args="--mcp=demo"
```

问一个只有 MCP 工具能回答的问题：

```
你> What time is it on the server right now?

[demo-mcp] 启动，等待握手……
[demo-mcp] 客户端握手完成
MCP[demo-mcp] 已挂载 2 个工具
可用工具：[edit_file, glob, grep, mcp__demo-mcp__current_time,
           mcp__demo-mcp__word_count, read_file, run_bash, write_file]

⚙ mcp__demo-mcp__current_time {}
[轮次结束 TOOL_USE  in=1743 out=28 cache=768]

┌─ 需要授权 ─────────────────────────────
│ 工具：mcp__demo-mcp__current_time
│ 调用外部 MCP 工具 demo-mcp / current_time
│     参数：{}
│ 风险：LOW
└────────────────────────────────────────
  允许吗？ [y]允许  [a]本会话总是允许  [n]拒绝 > y
  ← 2026-10-12 08:47:35 CST
服务器当前时间是 **2026-10-12 08:47:35 CST**。
[轮次结束 END_TURN  in=1796 out=19 cache=1536]
```

### 请确认这几件事

1. **`mcp__demo-mcp__current_time` 出现在工具列表里** —— 和本地工具排在一起，
   没有任何区别。
2. **它被权限系统拦住了** —— 我们一行权限代码都没为 MCP 写。
3. **它的结果进了对话历史、走了压缩器** —— 也是零改动。
4. **退出时服务器进程被清理了** —— 见 `McpClient.close()`。

> ⚠️ 唯一需要你注意的：`--mcp=demo` 是**便捷写法**，它用当前 classpath 启动
> 自带的演示服务器。要接真实的 MCP 服务器（比如
> `npx @modelcontextprotocol/server-filesystem /tmp`），
> 直接传完整命令即可：`--mcp=npx -y @modelcontextprotocol/server-filesystem /tmp`。
> （我们的参数解析是按空格切分的，所以路径里不要有空格。）

---

## 14. 如果你想接真实的 MCP 服务器

我们的客户端只实现了三个方法，但**协议是完整的**。接真实服务器时可能需要的补充：

| 情况 | 需要做什么 |
|---|---|
| 服务器返回 `image` 类型的 content | 我们的 `callTool` 只取 `text` 块，需要扩展 |
| 服务器提供 resource（文件、数据库行） | 需要实现 `resources/list`、`resources/read` |
| 服务器提供 prompt 模板 | 需要实现 `prompts/list`、`prompts/get` |
| 服务器主动发通知（工具列表变了） | 需要在读循环里处理带 `method` 的帧 |
| 需要并发调用 | 把 `lock` 换成 `id → CompletableFuture` 的映射表 |
| 用 HTTP/SSE 传输而不是 stdio | 需要实现 Streamable HTTP 传输 |

**但注意：这些都是"补功能"，不是"改架构"。** 加这些东西不会让你去动 `Agent` 或 `Tool`。

---

## 15. 自检清单

- [ ] `mvn -q compile` 无错误
- [ ] `--provider=anthropic` 下 agent 行为与默认完全一致
- [ ] **两个 Provider 下 `in=` 数字量级相同**（差几十以内，不是差 7 倍）
- [ ] `--provider=anthropic` 下工具调用、并行工具、多轮循环全部正常
- [ ] `--provider=anthropic --budget=4000 --keep=4` 下压缩正常工作
- [ ] `--mcp=demo` 后工具列表里出现 `mcp__demo-mcp__*`
- [ ] MCP 工具调用会走权限审批
- [ ] 退出时 MCP 子进程被清理（`ps` 看一下）
- [ ] **`git diff` 里除了 `Main` 和新增文件，没有别的改动**

最后一条是这一节的**核心验收标准**。

### 可以自己动手改的练习

1. **把 Anthropic 那处"系统摘要降级成 user"改成"塞进顶层 system"**，
   然后跑一次压缩，对比 `cache=` 的变化。
   你会看到 1536 vs 0 的差别——**这就是"改前缀"的代价。**
2. **让两个 Provider 同时跑**：`Main` 里交替使用两个 provider，看行为是否一致。
   这是真实产品做 A/B 测试的做法。
3. **给 `McpClient` 加并发支持**（把 `lock` 换成 `Map<Integer, CompletableFuture<JsonNode>>`），
   然后用一个会慢响应的假服务器测试。**体会一下"串行化"和"并发"的复杂度差距。**
4. **给 MCP 工具加 `DangerDetector` 规则**。
   想想：一个外部工具的 `describeAction` 该显示什么才算够？
   如果它叫 `delete_everything`，你光看名字敢授权吗？

---

## 16. 踩坑速查

| 症状 | 原因 | 修法 |
|---|---|---|
| Anthropic 格式报 401 | 用了 `Authorization: Bearer` | Anthropic 用 `x-api-key` 头 |
| Anthropic 格式报 `max_tokens is required` | 忘了这个字段 | Anthropic 里它是必填 |
| 工具结果报"找不到对应调用" | tool_result 没配对的 tool_use_id | 检查 `appendMessages` 的 tool 合并逻辑 |
| 工具参数报格式错误 | `input` 传了字符串 | Anthropic 的 `input` 必须是对象 |
| `in=` 数字小得离谱 | `input_tokens` 没加缓存部分 | 加起来：`input + cache_read + cache_creation` |
| 压缩后缓存全废 | 改了顶层 `system` / 消息列表开头 | 中途插入的东西不要进顶层 system |
| MCP 客户端读不到响应 | 服务器往 stdout 打日志了 | 日志必须走 stderr |
| MCP 工具名和本地冲突 | 没加前缀 | 用 `mcp__<server>__<tool>` |
| MCP 子进程没退出 | 没调 `close()` | `Main` 退出前关闭所有 client |
| 并发调 MCP 时响应串了 | 没有串行化 | 请求方法加锁，或做 id→Future 映射 |

---

## 17. 本节你实际学到了什么

| 概念 | 你做了什么 |
|---|---|
| **抽象的验证方法** | 写第二个实现，数一数被迫改了几个文件 |
| **控制变量** | 用同一账号同一模型的两种协议做对照实验 |
| **协议差异的抹平** | 工具结果形态、参数序列化、SSE 结构 |
| **抽象的裂缝** | 发现并标注了"中途 system 消息"这个抹不平的点 |
| **语义 vs 字段名** | 发现 `input_tokens` 和 `prompt_tokens` 含义不同 |
| **沉默的错误** | 一个 `break` 的差别同时影响正确性和成本，但不报错 |
| **MCP 的本质** | 剥掉包装就是换行分隔的 JSON-RPC |
| **适配器模式** | 几十行的 Adapter 接入一整个生态 |
| **命名冲突** | `mcp__<server>__<tool>` 前缀是必要的防御 |
| **测量而非假设** | 缓存表现、token 语义、成本差异，全部靠实测 |

**最重要的一条**：你现在知道**怎么判断一个抽象好坏了**。

不是看它的接口写得漂不漂亮，而是**加一个实现，看你要改多少东西**。

改 0 个文件 = 抽象成立。改 5 个文件 = 抽象切错了。
改得越多，说明你越早地把"本来就该被隔离的东西"泄露出去了。

---

## 18. 结语：这个项目还能往哪走

回头看，从 M1 到 M5 你亲手建了什么：

```
M1  SSE 解析 + 事件归一化           ── 让模型"能说话"
M2  工具循环 + 参数累积 + 并发       ── 让它"能看"
M3  写工具 + 权限系统               ── 让它"能动手"，而且有人把关
M4  token 预算 + 压缩 + 缓存        ── 让它能"跑很久"
M5  第二个 Provider + MCP           ── 让前面四层"能被替换和扩展"
```

**五个里程碑，每一个都在回答同一个问题的不同侧面：**

> **怎么把"模型的智能"变成一个"可靠的工程系统"？**

而答案从来不是"让模型更聪明"，而是：

- **把不确定性收敛到边界上**（Provider 遮住协议差异）
- **把失败变成信息而不是终止**（工具错误、权限拒绝）
- **把"没标准答案"变成"可调的参数"**（压缩策略、风险分级）
- **把"看起来能扩展"变成"测过了能扩展"**（M5）

### 如果还想继续深入

按投入产出排序：

| 方向 | 做什么 | 能学到什么 |
|---|---|---|
| **子 agent** | 让 agent 能启动一个独立的 `Agent` 实例处理子任务，只返回结论 | 上下文隔离、任务分解 |
| **工具动态加载** | 工具多到几十个时，只给模型工具名和一行摘要，需要时再展开完整 schema | 上下文预算的另一种解法 |
| **持久化** | 把对话历史存到磁盘，支持跨进程恢复 | 会话管理、增量写 |
| **流式 diff 渲染** | `edit_file` 前把改动渲染成彩色 diff | 人在回路（human-in-the-loop）的体验设计 |
| **评估** | 造一组固定的任务 + 期望结果，每次改动跑一遍 | 怎么知道自己改好还是改坏了 |
| **沙箱隔离** | 用容器跑 `run_bash`，而不是直接在本机跑 | 安全的终极答案 |

### 最后一句

**你现在拥有的不是"一个 agent"，而是"一套关于 agent 的判断力"。**

你能读懂 Claude Code 的每一处取舍为什么那么设计；
你能在别人说"上下文管理就该这么做"的时候问一句"对什么任务"；
你能在选型的时候知道代价在哪一边。

**这个项目的目的从来不是那个 REPL，是这些判断力。**
