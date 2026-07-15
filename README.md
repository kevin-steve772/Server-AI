# Server-AI

Minecraft 服务器 AI 聊天插件，支持 Paper/Folia 服务端，兼容 OpenAI 格式 API（OpenAI、DeepSeek、Azure OpenAI、本地模型等）。

## 功能特性

- `/ask <问题>` - 玩家向 AI 提问
- `/ask reload` - 重载配置文件（需权限）
- **冷却时间限制** - 防止刷屏，可配置冷却秒数
- **权限系统** - 支持权限插件控制权限
- **Folia 支持** - 完全兼容 Folia 多线程架构
- **OpenAI 兼容 API** - 支持 OpenAI、DeepSeek、Azure、本地模型等
- **异步请求** - 使用 Folia 调度器异步请求，不阻塞主线程
- **配置热重载** - 无需重启服务器即可修改配置
- **AI NPC 系统** - 基于 Citizens 创建可由 AI 控制的 NPC
  - NPC 生成、移除、列表
  - NPC 聊天（全服/私聊）
  - NPC 移动（寻路/瞬移）
  - NPC 看向坐标/玩家
  - NPC 装备/护甲
  - **Function Calling** - AI 可直接调用工具控制 NPC

## 安装

1. 安装 **Citizens** 插件（必需，用于 NPC 功能）
2. 下载 `Server-AI-1.0.0.jar` 放入服务器 `plugins/` 目录
3. 重启服务器
4. 修改 `plugins/Server-AI/config.yml` 配置 API 密钥
5. 使用 `/ask reload` 重载配置或重启服务器

## 配置文件

```yaml
# config.yml
api:
  key: "your-api-key-here"          # 必填：API 密钥
  endpoint: "https://api.openai.com/v1"  # API 地址（OpenAI 兼容格式）
  model: "gpt-3.5-turbo"            # 模型名称
  max-tokens: 1024                  # 最大回复长度
  temperature: 0.7                  # 创造性 (0.0-2.0)
  timeout: 30                       # 请求超时秒数

npc:
  default-type: "PLAYER"            # 默认 NPC 类型
  max-npcs: 20                      # 最大同时存在 NPC 数量
  default-speed: 1.0                # NPC 移动速度
  arrival-distance: 2.0             # NPC 到达判定距离

messages:
  no-permission: "&c你没有权限执行此命令。"
  usage: "&e用法: /ask <问题>"
  thinking: "&e正在思考中，请稍候..."
  no-key: "&cAPI密钥未配置，请设置 config.yml 中的 api.key"
  error: "&c请求AI时出错: %error%"
  rate-limit: "&c请求过于频繁，请稍后再试。"
  cooldown: "&c请等待 %seconds% 秒后再询问。"
  reloaded: "&a配置已重载。"

cooldown: 5  # 冷却时间（秒）
```

### 常用 API 配置示例

**OpenAI:**
```yaml
api:
  key: "sk-xxx"
  endpoint: "https://api.openai.com/v1"
  model: "gpt-3.5-turbo"
```

**DeepSeek:**
```yaml
api:
  key: "sk-xxx"
  endpoint: "https://api.deepseek.com/v1"
  model: "deepseek-chat"
```

**本地模型:**
```yaml
api:
  key: "不需要"  # 本地模型通常不需要 key
  endpoint: "http://localhost:11434/v1"  # Ollama 等
  model: "llama3"
```

## 命令与权限

### /ask 命令

| 命令 | 别名 | 权限 | 说明 |
|------|------|------|------|
| `/ask <问题>` | `/ai`, `/问` | `serverai.ask` | 向 AI 提问 |
| `/ask reload` | - | `serverai.reload` | 重载配置 |

### /npc 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/npc spawn <名称> <world> <x> <y> <z> [类型]` | `serverai.npc` | 创建 NPC |
| `/npc remove <名称>` | `serverai.npc` | 移除 NPC |
| `/npc list` | `serverai.npc` | 列出所有 NPC |
| `/npc chat <名称> <消息>` | `serverai.npc` | NPC 说话 |
| `/npc move <名称> <world> <x> <y> <z> [速度]` | `serverai.npc` | NPC 移动 |
| `/npc stop <名称>` | `serverai.npc` | 停止移动 |
| `/npc look <名称> <world> <x> <y> <z>` | `serverai.npc` | NPC 看向坐标 |
| `/npc equip <名称> <材质> [名称] [数量]` | `serverai.npc` | 给 NPC 装备 |
| `/npc tp <名称> <world> <x> <y> <z>` | `serverai.npc` | 传送 NPC |
| `/npc info <名称>` | `serverai.npc` | 查看 NPC 信息 |

**默认权限:**
- `serverai.ask`: 默认 `true`（所有人可用）
- `serverai.reload`: 默认 `op`（仅 OP 可用）
- `serverai.npc`: 默认 `op`（仅 OP 可用）

## AI 控制 NPC (Function Calling)

AI 可以通过 Function Calling 直接控制 NPC，无需玩家手动输入命令。

### 可用工具函数

| 函数 | 说明 | 参数 |
|------|------|------|
| `spawn_npc` | 生成 NPC | name, world, x, y, z, type? |
| `remove_npc` | 移除 NPC | name |
| `npc_chat` | NPC 全服聊天 | name, message |
| `npc_chat_to` | NPC 私聊玩家 | name, player, message |
| `npc_move` | NPC 移动到坐标 | name, world, x, y, z, speed? |
| `npc_stop` | 停止 NPC 移动 | name |
| `npc_look_at` | NPC 看向坐标 | name, world, x, y, z |
| `npc_look_at_player` | NPC 看向玩家 | name, player |
| `npc_equip` | 给 NPC 手持物品 | name, material, custom_name?, amount? |
| `npc_armor` | 给 NPC 穿戴护甲 | name, helmet?, chestplate?, leggings?, boots? |
| `npc_teleport` | 瞬间传送 NPC | name, world, x, y, z |
| `npc_get_location` | 获取 NPC 位置 | name |
| `npc_list` | 列出所有 NPC | - |
| `get_online_players` | 获取在线玩家 | - |
| `get_player_location` | 获取玩家位置 | player |

### 使用示例

玩家输入：`/ask 让 NPC 小明 走到出生点 并对大家说你好`

AI 会自动调用：
1. `npc_move` - 让小明移动到出生点坐标
2. `npc_chat` - 让小明说 "你好大家！"

### 系统提示词建议

在配置中添加系统提示词让 AI 知道如何使用工具：

```yaml
# 可以通过修改代码添加系统提示，或让 AI 通过上下文学习
```

## 权限插件配置示例

```yaml
# LuckPerms 示例
/lp group default permission set serverai.ask true
/lp group admin permission set serverai.reload true
/lp group admin permission set serverai.npc true
```

## 构建

需要 Java 21 + Maven：

```bash
mvn clean package
```

输出: `target/Server-AI-1.0.0.jar`

## 依赖

- Paper/Folia 1.21+
- Citizens 2.0.30+
- Java 21+

## 许可证

MIT License