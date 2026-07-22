# 微信 iLink 个人测试接入

本项目使用第三方社区 SDK
[`io.github.lith0924:wechat-ilink-sdk:2.3.3`](https://github.com/lith0924/wechat-ilink-sdk-java)
接收和回复微信消息。它不是微信官方 SDK，不能保证账号零风险，仅建议使用测试微信号、小范围低频测试。

## 当前能力

- SDK 二维码登录和本地会话恢复
- SDK 自动管理 `contextToken`、游标和发送幂等 `client_id`
- 收到消息后立即显示“正在输入”
- 按配置的随机延迟发送回复，当前默认 300～1200 毫秒
- SDK 网络重试带随机抖动，连续失败时最长等待 60 秒，避免请求风暴
- 按微信用户隔离连续对话，默认保留最近 20 轮，24 小时无活动后过期
- 接收微信图片和视频，使用 DashScope Java SDK 调用千问进行内容理解
- 视觉结果会再次交给千问按微信聊天语气组织，避免机械的教程式画面描述
- 明确要求画图或生成图片时，由千问整理提示词并路由到 `wan2.7-image-pro`，生成后直接发送图片到微信
- 支持对已上传图片进行换背景、增删内容、风格变换、修复和增强
- 支持联网搜索、个人知识库导入与检索、Redis 定时提醒
- 文档处理、语音识别、语音合成和音色选择均通过 Function Calling 调用
- 对话记忆优先保存在 Redis，并保留本地 JSON 故障降级
- 回复最长 1800 字，避免单条消息过长

发送 `/reset`、`清除对话` 或 `重新开始` 可以清除当前微信用户的对话记忆。

## 启动

先停止旧程序，再运行最新 JAR：

```powershell
java -jar "D:\demo\target\demo-0.0.1-SNAPSHOT-boot.jar" `
  --assistant.console-enabled=false `
  --assistant.ilink-enabled=true `
  --server.port=8080
```

原自写客户端保存的登录凭据会自动迁移到 SDK 格式。会话文件默认位于：

```text
%USERPROFILE%\.demo-ilink\credentials.json
```

该文件包含登录令牌，不要上传到 GitHub、发给别人或放进项目目录。令牌失效时，先停止程序，删除该文件，再启动程序重新扫码。

## 可调参数

```properties
assistant.ilink.min-reply-delay-ms=300
assistant.ilink.max-reply-delay-ms=1200
assistant.ilink.max-reply-chars=1800
assistant.ilink.max-media-bytes=104857600
assistant.ilink.batch-window-ms=3000
assistant.conversation.max-turns=20
assistant.conversation.ttl-minutes=1440
assistant.conversation.redis-enabled=true
aliyun.dashscope.model=qwen3.7-plus
aliyun.dashscope.image-model=wan2.7-image-pro
aliyun.dashscope.image-size=2K
aliyun.dashscope.video-fps=2
```

`video-fps` 范围为 0.1～10，运动较快的视频可适当提高，长视频可降低以控制处理量。

当前完整架构、部署和扩展说明请参阅 `TECHNICAL-DOCUMENTATION.md`。不建议同时运行多个使用同一登录凭据的程序实例。
