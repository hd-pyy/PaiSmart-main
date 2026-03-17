# Redis 用途与数据格式说明

## 1. Redis 在项目中的核心用途

### 1.1 聊天会话历史缓存（主用场景）
- 文件：`src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- key: `conversation:<conversationId>:messages`
  - 类型：Redis List
  - 内容：按顺序存储对话消息，例如：
    - `{"role":"user","content":"你好","timestamp":"2026-03-17T..."}`
    - `{"role":"assistant","content":"您好，请问...","timestamp":"..."}`
- key: `conversation:<conversationId>:metadata`
  - 类型：Redis String (JSON)
  - 内容：
    - `{"lastActivity":"2026-03-17T...","messageCount":"12"}`

### 1.2 用户会话ID映射
- key: `user:<userId>:current_conversation`
- 类型：Redis String
- 值：conversationId
- 过期：`Duration.ofDays(7)`

## 2. 数据格式说明

### 2.1 对话列表（List）
- 使用 List 保证历史顺序, `opsForList().range(key,0,-1)` 读取、`rightPushAll` 追加
- JSON 字符串是一行一条，降低解析复杂度，前端/日志对比方便

### 2.2 metadata（String）
- 保存会话上下文状态（活跃时间、消息数），避免重建时重复计算
- 结构化 JSON + Redis String 适合快速读写

## 3. 设计理由（为什么这个结构）
1. 易读易调试：JSON 直接可查、日志可读。
2. 兼容性强：Spring `RedisTemplate` + Jackson 无需额外序列化机制。
3. 顺序性保障：List 保证历史消息顺序，符合聊天语义。
4. 存储可控：可通过 `trim` 和过期策略限制内存。
5. 扩展性：后续可加 `source`、`score`、`tokenCount` 等字段。

## 4. 可能的优化方向
- 使用 `Redis Hash` 存 metadata 与状态，模型统一；
- 使用 `ZSET`/`Stream` 支持多会话/时序检索；
- 使用 `pub/sub` 做跨进程WebSocket消息推送与重连；
- 结合 `Redis 模块`（如 RedisJSON）实现更丰富结构化查询。
