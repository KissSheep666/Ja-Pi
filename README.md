# java-agent

从零手写一个编码 agent（类 Claude Code / pi），**目的是学习**，不是做产品。

用 Java 21 手写 SSE 流式解析、工具调用循环、权限系统和上下文压缩，
不依赖任何 agent 框架。

## 为什么用 Java，为什么手写 HTTP

官方 SDK 会把你最该学的东西全部封装掉——流式分片怎么拼、工具参数怎么累积、
一轮什么时候算结束。所以这里用 `java.net.http.HttpClient` + Jackson 手写，
全程只有 **1 个第三方运行时依赖**。

## 里程碑

| | 内容 | 主角 | 学到什么 |
|---|---|---|---|
| **M1** | REPL + 流式对话，无工具 | `StreamEvent` / `DeepSeekProvider` | SSE 协议、事件归一化 |
| **M2** | 只读工具 + 完整循环 | `Agent` / `Tool` / `AssistantTurn` | 工具循环、增量参数累积、并发 |
| **M3** | 写工具 + 审批 | `PermissionManager` / `DangerDetector` | 权限模型、路径沙箱、危险命令拦截 |
| **M4** | token 预算 + 自动压缩 | `Compactor` / `TokenEstimator` | 上下文管理、缓存成本 |
| **M5** | 第二个 Provider + MCP | `AnthropicProvider` / `McpToolAdapter` | 抽象到底成不成立 |

## 文档

全部在 [`docs/`](docs/)。**每一节的代码都实际编译并端到端跑通过**，
文档里的协议细节是抓真实 SSE 抓出来的，不是从记忆里写的。

| 文档 | 内容 |
|---|---|
| [00 · 总览与架构](docs/00-总览与架构.md) | 选型、模型事实、**六条铁律**、架构图、路线 |
| [01 · M1 打通流式对话](docs/01-M1-打通流式对话.md) | SSE 是什么、事件归一化、sealed interface |
| [02 · M2 工具系统与核心循环](docs/02-M2-工具系统与核心循环.md) | 工具 schema、参数分片累积、虚拟线程并发 |
| [03 · M3 写工具与权限系统](docs/03-M3-写工具与权限系统.md) | 审批疲劳、风险分级、授权粒度、进程死锁 |
| [04 · M4 上下文管理](docs/04-M4-上下文管理.md) | 压缩策略、KV 缓存前缀匹配、成本测算 |
| [05 · M5 验证抽象与 MCP](docs/05-M5-验证抽象与MCP.md) | 双协议对照、MCP 客户端与适配器 |

## 关键设计

```
Main (REPL)
  │
  ▼
Agent ── 循环：调模型 → 有工具? → 执行 → 回灌
  │
  ▼
Provider (接口)  ← 万能插头
  ├── DeepSeekProvider   (OpenAI / Chat Completions 格式)
  └── AnthropicProvider  (Anthropic / Messages 格式)
```

**分界线**：`Provider` 左边是统一的 `sealed interface StreamEvent`，
右边是各家的协议方言。换协议只需要换右边。

实测：同一套 agent 代码，在两种协议下 token 统计对齐到
`1607/1778/1973` vs `1607/1777/1972`。

## 环境

需要 JDK 21+ 和 Maven。

```bash
export DEEPSEEK_API_KEY="你的key"
```

⚠️ API key **只走环境变量**，不要写进任何 `.java` 文件。

## 仓库结构

```
docs/        六份教程（正文）
reference/   完整可运行的参考实现（36 个 java 文件，约 3965 行）
```

⚠️ **`reference/` 是给你卡住时对照用的，不是给你抄的。**
照着 `docs/` 自己敲一遍，学习发生在敲的过程里。
详见 [reference/README.md](reference/README.md)。

## 六个坑，提前告诉你

1. 工具参数是**分片** JSON，必须按 index 累积后整体 parse
2. 继续循环的信号是 `finish_reason == "tool_calls"`，不是"有没有 tool_calls 字段"
3. 思考模式 + 带工具时，历史轮次的 `reasoning_content` **必须回传**，否则 400
4. `edit_file` 必须做唯一性检查，否则会改错地方
5. 跑 shell 命令时输出要**并发读**，否则管道缓冲区满会死锁
6. Anthropic 的 `input_tokens` **只算未命中缓存的部分**，和 OpenAI 的 `prompt_tokens` 语义不同

详见每一节文档。
