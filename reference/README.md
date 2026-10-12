# reference —— 参考实现

这里是 M1–M5 的**完整可运行版本**，36 个 Java 文件，约 3965 行。

## 请先读完这段

**这份代码是给你"卡住时对照"用的，不是给你抄的。**

`docs/` 下的教程才是正文。照着文档自己敲一遍，遇到哪一步跑不通、
或者想不通为什么这么写，再翻到这里对照。

直接复制粘贴会跳过学习发生的那一步——**你只会在运行成功时感到满足，
但不会获得"我知道为什么"的判断力。**

## 运行

```bash
cd reference
export DEEPSEEK_API_KEY="你的key"

# 基础：工具 + 权限（默认 OpenAI 格式）
mvn -q compile exec:java

# Anthropic 格式（同一套代码，只换启动参数）
mvn -q compile exec:java -Dexec.args="--provider=anthropic"

# 挂载自带的演示 MCP 服务器
mvn -q compile exec:java -Dexec.args="--mcp=demo"

# 开着思考模式
mvn -q compile exec:java -Dexec.args="--think"

# 把上下文预算调小，几轮就能看到压缩
mvn -q compile exec:java -Dexec.args="--budget=4000 --keep=4"

# 自动批准所有工具调用（谨慎使用，只在你自己的测试目录里跑）
mvn -q compile exec:java -Dexec.args="--yes"

# 打印原始 SSE
mvn -q compile exec:java -Dexec.args="--raw"
```

### 一定要在测试目录里跑

agent 的工作目录是**启动时所在的目录**，而且它有写文件和跑 shell 的权限。

```bash
mkdir -p /tmp/ws && cd /tmp/ws     # 先造个沙箱
java -cp <reference 编译产物> dev.learn.agent.Main
```

**不要直接在 `reference/` 目录里跑** —— 它会让 agent 有权限改自己的源码。

## 参数一览

| 参数 | 作用 |
|---|---|
| `--provider=anthropic` | 换用 Anthropic Messages 格式（连 DeepSeek 的 `/anthropic` 端点） |
| `--mcp=demo` | 挂载自带的演示 MCP 服务器（提供 `current_time` / `word_count`） |
| `--mcp=<命令>` | 挂载任意 MCP 服务器，例如 `--mcp=npx -y @modelcontextprotocol/server-filesystem /tmp` |
| `--think` | 开启思考模式（会显示灰色的推理过程） |
| `--budget=N` | 上下文 token 预算，默认 24000 |
| `--keep=N` | 压缩时保留最近多少条消息，默认 8 |
| `--yes` | 自动批准所有工具调用（跳过审批） |
| `--raw` | 把每一行原始 SSE 打到 stdout |

## 与文档的对应关系

| 包 | 对应文档 |
|---|---|
| `provider/` | [M1](../docs/01-M1-打通流式对话.md)、[M5](../docs/05-M5-验证抽象与MCP.md) |
| `agent/` | [M2](../docs/02-M2-工具系统与核心循环.md) |
| `tool/` | [M2](../docs/02-M2-工具系统与核心循环.md) |
| `permission/` | [M3](../docs/03-M3-写工具与权限系统.md) |
| `context/` | [M4](../docs/04-M4-上下文管理.md) |
| `mcp/` | [M5](../docs/05-M5-验证抽象与MCP.md) |

## 已知的简化

这是学习项目，不是生产代码。几处刻意留下的缺口：

- **路径沙箱不处理符号链接**。工作目录里若有指向外部的软链接，仍可绕过。
  真实实现要用 `toRealPath()`。
- **危险命令识别是正则黑名单**，必然漏报。它只是最后一道提醒，
  不是唯一防线。真正的安全靠容器隔离。
- **MCP 客户端只实现了 3 个方法**（`initialize` / `tools/list` / `tools/call`），
  且所有请求串行化。没有实现 resources、prompts、通知、并发。
- **`TokenEstimator` 是字符数估算**，不是精确 tokenizer。
- **没有会话持久化**，进程退出历史就没了。

这些缺口在对应文档里都有说明。
