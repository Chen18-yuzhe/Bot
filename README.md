# 微信 AI 助手

基于 Spring Boot、微信 iLink、阿里云百炼 DashScope Function Calling、和风天气与 Redis 构建的多模态微信 AI 助手。

主要功能包括多轮对话、联网搜索、个人知识库、天气查询、图片理解与编辑、文生图、文档处理、语音识别与合成、定时提醒、运行监控和 24 小时 Redis 会话记忆。

## 文档

- [完整技术文档](TECHNICAL-DOCUMENTATION.md)
- [Function Calling 开发规范](FUNCTION-CALLING.md)
- [Redis 对话记忆](REDIS-MEMORY.md)
- [微信 iLink 接入说明](ILINK-README.md)

## 构建

```powershell
.\mvnw.cmd clean test package
```

可执行 JAR 位于：

```text
target/demo-0.0.1-SNAPSHOT-boot.jar
```
