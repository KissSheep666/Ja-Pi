# jpi —— Java 21 终端编码 agent

`jpi` 是一个从零手写的终端编码 agent，行为对标 Claude Code / pi：流式对话、工具调用、权限审批、上下文压缩、双协议切换、MCP。它把 `../docs/` 里 M1–M5 的全部能力做成了一个能日常使用的 CLI。

- **不依赖 agent 框架**：只有 Jackson + JLine 两个运行时依赖
- **协议自己解析**：`java.net.http.HttpClient` 手写 SSE，没有官方 SDK
- **两种协议同一套循环**：DeepSeek / OpenAI 格式 与 Anthropic Messages 格式
- **默认保守**：写文件、跑命令都要审批；高风险操作每次都重新确认

## 环境

- JDK 21+
- Maven 3.9+
- 一个 DeepSeek API key（默认使用 `deepseek-flash`）

> ⚠️ API key **只走环境变量**，不要写进任何文件。

```bash
# Git Bash / macOS / Linux
export DEEPSEEK_API_KEY="sk-..."

# Windows PowerShell
$env:DEEPSEEK_API_KEY="sk-..."
```

## 构建与运行

```bash
cd cli
mvn package

# 运行
java -jar target/jpi.jar
```

也可以直接用启动脚本（脚本会自动找同目录下的 `target/jpi.jar`）：

```bash
./jpi                 # Git Bash / macOS / Linux
jpi.cmd               # Windows cmd / PowerShell
```

```text
jpi 0.1.0  ·  Java 21 终端编码 agent
model   deepseek-flash  (deepseek)   thinking off   approvals ask
cwd     D:\codeSpace\demo
tools   read_file · glob · grep · write_file · edit_file · run_bash
budget  24.0k token（超过 14.4k 触发压缩）
输入 /help 查看命令，Ctrl+C 取消，Ctrl+D 退出。

你 › 帮我看看这个项目为什么编译不过

  ⚙ run_bash mvn -q compile
  ✔ run_bash mvn -q compile · 1.2s
    退出码：1 ...
...
[deepseek-flash · 3.1s · in 4.2k (cache 3.1k) · out 312 · ≈$0.0009]
```

## 命令行参数

| 参数 | 作用 |
|---|---|
| `--provider=<name>` | `deepseek`（默认）或 `anthropic` |
| `--model=<id>` | 模型 ID，默认 `deepseek-flash` |
| `--think` | 开启思考模式 |
| `--budget=<tokens>` | 上下文预算，默认 24000 |
| `--keep=<n>` | 压缩时保留最近 n 条消息，默认 8 |
| `--mcp=<command>` | 挂载 MCP 服务器，可重复；`demo` 挂载内置示例 |
| `--cwd=<path>` | 工作目录，默认当前目录 |
| `-y`, `--yes` | 自动批准所有工具调用（谨慎使用） |
| `--raw` | 把原始 SSE 打到 stderr |
| `--no-color` | 关闭颜色 |
| `-h`, `--help` | 帮助 |
| `-V`, `--version` | 版本 |

### 环境变量

| 变量 | 作用 |
|---|---|
| `DEEPSEEK_API_KEY` | DeepSeek key（两种协议默认都需要） |
| `ANTHROPIC_API_KEY` | 显式指向其他 Anthropic 端点时使用 |
| `JPI_PROVIDER` / `JPI_MODEL` | 默认 provider / 模型 |
| `JPI_THINK=1` | 默认开启思考模式 |
| `JPI_BUDGET` / `JPI_KEEP` | 默认预算 / 保留消息数 |
| `JPI_DEEPSEEK_BASE_URL` / `DEEPSEEK_BASE_URL` | 覆盖 DeepSeek 端点 |
| `JPI_ANTHROPIC_BASE_URL` / `ANTHROPIC_BASE_URL` | 覆盖 Anthropic 端点 |
| `NO_COLOR` | 关闭颜色 |

## 斜杠命令

| 命令 | 作用 |
|---|---|
| `/help` | 显示帮助 |
| `/model [id]` | 查看或切换模型 |
| `/provider [name]` | 查看或切换协议（`deepseek` / `anthropic`） |
| `/think [on\|off]` | 开关思考模式 |
| `/compact` | 立即压缩上下文 |
| `/clear` | 清空对话历史 |
| `/budget [tokens]` | 查看或设置上下文预算 |
| `/keep [n]` | 查看或设置压缩时保留的消息数 |
| `/tools` | 列出工具、来源和是否需要审批 |
| `/mcp [list\|add <cmd>\|demo]` | 查看或挂载 MCP 服务器 |
| `/permissions [reset]` | 查看或清空本会话授权 |
| `/usage` | 查看累计 token、缓存命中和估算费用 |
| `/quit` | 退出 |

`Ctrl+C` 取消当前请求（会关闭网络流、杀掉 bash 子进程）；`Ctrl+D` 退出。

## 常用示例

```bash
# 挂载内置 MCP 示例（提供 current_time / word_count）
jpi --mcp=demo

# 挂载任意 stdio MCP 服务器
jpi --mcp='npx -y @modelcontextprotocol/server-filesystem /tmp'
# Windows 上 npx/npm/pnpm/yarn/uvx 会自动套一层 cmd /c

# 用 Anthropic Messages 协议连 DeepSeek 的兼容端点
jpi --provider=anthropic --think

# 把预算调小，几轮就能看到自动压缩
jpi --budget=4000 --keep=4

# 在测试目录里自动批准所有操作
jpi --cwd=/tmp/workspace --yes
```

## 结构

```text
cli/src/main/java/dev/jpi/
├── Main.java                 启动、参数、装配
├── agent/
│   ├── Agent.java            核心循环：调模型 → 工具 → 回灌
│   ├── AssistantTurn.java    流式事件累积器（分片 JSON）
│   └── AgentListener.java    UI 回调
├── provider/
│   ├── StreamEvent.java      协议无关的事件词汇表（sealed）
│   ├── SwitchingProvider.java 运行时切换协议
│   ├── Providers.java        环境变量 → Provider
│   ├── deepseek/             OpenAI / Chat Completions 方言
│   └── anthropic/            Anthropic Messages 方言
├── tool/
│   ├── Tool.java             工具接口
│   ├── ToolContext.java      工作目录、路径沙箱、取消令牌
│   ├── ToolRegistry.java     注册、并发执行、权限检查
│   └── impl/                 6 个工具
├── permission/               风险分级、审批、危险命令识别
├── context/                  token 估算、预算、压缩、费用
├── mcp/                      stdio JSON-RPC 客户端、工具适配器
├── ui/                       JLine REPL、渲染、审批、命令
└── util/Cancellation.java    取消令牌
```

## 实现要点

1. **SSE 自己解析**：工具参数是分片 JSON，按 `index → (id, name)` 建表，累积完再整体 parse；`finish_reason` 才是循环继续的信号。
2. **reasoning_content 必须回传**：带 tools 的请求里，历史 assistant 消息的思考内容必须一起发回去，否则 DeepSeek 返回 400。
3. **工具结果一条一条发**：OpenAI 格式要求每个结果独立成 `role:"tool"`；Anthropic 格式要求合并进同一条 user 消息。差异由 Provider 抹平。
4. **权限是横切关注点**：放在 `ToolContext` / `ToolRegistry` 里，工具本身不知道权限存在；高风险永不进入"本会话总是允许"。
5. **路径沙箱处理符号链接**：逐段 `toRealPath()` 校验，断链和指向外部的链接都拒绝。
6. **压缩切在 user 边界**：`tool_calls` 和 `tool` 结果不能被切开；摘要请求不带工具、关思考。
7. **MCP 并发安全**：读线程按 id 分发响应，请求有超时；写操作串行化。
8. **取消是正常控制流**：关闭网络流/进程，但保证已写入历史的 `tool_calls` 一定有对应的 tool 结果。

## 安全模型

- 读工具（`read_file` / `glob` / `grep`）不打扰用户；
- 写文件、改文件、跑命令、MCP 工具都要审批；
- 审批粒度：`write_file` 到父目录、`edit_file` 到具体文件、`run_bash` 到命令首词；
- 危险命令识别是**正则黑名单**，必然漏报，只是最后一道提醒；
- `run_bash` 能绕过文件路径沙箱；真正的隔离要靠容器 / 低权限用户；
- `--yes` 会跳过所有审批，只在一次性沙箱里用。

## 测试

```bash
cd cli
mvn test
```

覆盖：核心循环、分片参数累积、权限决策、危险命令识别、路径沙箱、压缩切割、DeepSeek/Anthropic 的 SSE 解析与请求序列化、MCP stdio 往返。

> Windows 上如果没有创建符号链接的权限，2 个符号链接沙箱用例会自动跳过；开启开发者模式或管理员终端后可以跑全量。

## 已知限制

- 不是全屏 TUI，是增强 REPL；没有 diff 高亮和全屏重绘。
- 没有会话持久化，进程退出历史就没了（输入历史会存在 `~/.jpi/history`）。
- token 数是字符估算，不是精确 tokenizer；真实值以服务端 usage 为准。
- 费用按 `deepseek-flash` 非高峰价估算，换模型/端点后只具参考意义。
- MCP 只实现 `initialize` / `tools/list` / `tools/call`，没有 resources、prompts、通知。
- Anthropic Provider 主要针对 DeepSeek 的 `/anthropic` 兼容端点验证；连接官方 Anthropic 端点时，thinking 参数等细节可能需要调整。

## 与教程的关系

`../docs/` 是 M1–M5 的教程正文，`../reference/` 是教学参考实现。这个目录是**成品**：在同样的核心设计上补充了增强 REPL、运行时设置、并发 MCP、请求取消、UTF-8/Windows 兼容、单元测试和打包。想理解每一块为什么这么写，先读教程；想直接用，跑 `jpi`。
