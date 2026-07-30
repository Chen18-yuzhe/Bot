# Redis 对话记忆

每个用户的连续对话以独立 Redis 键保存，默认采用 24 小时滑动过期：每次读取或写入都会续期，连续 24 小时没有活动后由 Redis 自动删除。

## 数据结构

- 键前缀：`bot:conversation:`
- 键后缀：微信用户 ID 的 SHA-256，不在 Redis 键名中保存原始用户 ID
- 值：有界的 JSON 会话对象，包含摘要、最近轮次和最后访问时间
- TTL：默认 `1440` 分钟

仍保留以下限制：最多 20 个完整轮次，超出的旧轮次压缩进有长度上限的摘要；单条消息和摘要也有限长。

## 配置

```text
REDIS_URL=redis://127.0.0.1:6379/0
CONVERSATION_REDIS_ENABLED=true
CONVERSATION_TTL_MINUTES=1440
CONVERSATION_REDIS_KEY_PREFIX=bot:conversation:
```

生产环境应让 Redis 仅监听本机或私网，不要将 6379 端口暴露到公网。若使用远端 Redis，应启用账号密码和 TLS，并把凭据放入服务器环境文件，不要提交到仓库。

## 故障与迁移

- Redis 短暂不可用时，应用降级到本地内存和 JSON 文件，不中断 Bot 回复。
- Redis 恢复后，新消息会重新写入 Redis。
- 首次启用 Redis时，24 小时内仍有效的本地 JSON 会话会自动迁移。
- `/reset` 或“清除对话”会同时删除内存、JSON 和 Redis 中的用户会话。
