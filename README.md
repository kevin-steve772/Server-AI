# Server-AI

面向 Paper/Folia 1.21+ 的 Minecraft AI 问答插件，兼容 OpenAI Chat Completions 格式的 API。

## 功能

- `/ask <问题>`：向 AI 提问并把问题、回答广播到服务器
- `/ask reload`：热重载配置
- `/npc spawn|remove|say`：生成一个流浪商人聊天 NPC
- 异步 HTTP 请求，不占用服务器区域线程
- AI 回答支持标题、强调、代码、列表、引用、链接和表格等 Markdown 格式
- 每玩家冷却、重复请求保护、全局 AI 请求并发上限
- API 超时、问题长度和错误响应长度限制
- 支持从环境变量读取 API 密钥
- 支持无需鉴权的本地 OpenAI 兼容服务

NPC 由插件直接创建，不依赖 Citizens。当前版本不包含 NPC 寻路、装备或 AI Function Calling。

## 环境要求

- Java 21+
- Paper 或 Folia 1.21.4+
- Maven 3.9+（仅构建时需要）

## 安装

1. 执行 `mvn clean package`。
2. 将 `target/Server-AI-1.0.1.jar` 放入服务器的 `plugins/` 目录。
3. 启动服务器并编辑 `plugins/Server-AI/config.yml`。
4. 设置 API 密钥后执行 `/ask reload`，或重启服务器。

## 配置

```yaml
api:
  key: "your-api-key-here"
  key-env: "SERVER_AI_API_KEY"
  require-key: true
  endpoint: "https://api.openai.com/v1"
  model: "gpt-3.5-turbo"
  max-tokens: 1024
  temperature: 0.7
  timeout: 30
  max-concurrent-requests: 4
  max-question-length: 1000

npc:
  name: "&b[AI]助手"
  chat-format: "<%npc_name%> %message%"

cooldown: 5
```

`key-env` 指定的环境变量优先于 `api.key`，推荐在生产环境中使用：

```bash
export SERVER_AI_API_KEY="sk-..."
```

对于 Ollama 等无需 API 密钥的本地服务，可使用：

```yaml
api:
  key: ""
  require-key: false
  endpoint: "http://127.0.0.1:11434/v1"
  model: "llama3"
```

`endpoint` 可以填写 API 根路径，也可以直接填写完整的 `/chat/completions` 地址。

## 命令与权限

| 命令 | 权限 | 默认值 |
| --- | --- | --- |
| `/ask <问题>` | `serverai.ask` | 所有人 |
| `/ask reload` | `serverai.reload` | OP |
| `/npc spawn` | `serverai.npc` | OP |
| `/npc remove` | `serverai.npc` | OP |
| `/npc say <消息>` | `serverai.npc` | OP |

## 构建与测试

```bash
mvn clean verify
```

构建生成的插件 JAR 已包含并重定位 Jackson，避免依赖服务端内部版本或与其他插件冲突。

## 自动发布

仓库包含 GitHub Actions 发布工作流。推送与 `pom.xml` 版本一致的 `v*` Tag 后，工作流会自动运行测试、构建插件，并创建带 JAR 和 SHA-256 校验文件的 GitHub Release。

例如发布 `1.0.1`：

```bash
git tag -a v1.0.1 -m "Release v1.0.1"
git push origin v1.0.1
```

如果 Tag 与 `pom.xml` 中的版本不一致，发布会直接失败。向不属于自己的仓库推送前，请先将远端切换到有写权限的 Fork。

## 许可证

MIT License
