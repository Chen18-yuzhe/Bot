# 微信 iLink 个人测试接入

本项目使用第三方社区 SDK
[`io.github.lith0924:wechat-ilink-sdk:2.3.3`](https://github.com/lith0924/wechat-ilink-sdk-java)
接收和回复微信消息。它不是微信官方 SDK，不能保证账号零风险，仅建议使用测试微信号、小范围低频测试。

## 当前能力

- SDK 二维码登录和本地会话恢复
- SDK 自动管理 `contextToken`、游标和发送幂等 `client_id`
- 收到消息后立即显示“正在输入”
- 在 13～17 秒之间发送回复，避免固定节奏
- SDK 网络重试带随机抖动，连续失败时最长等待 60 秒，避免请求风暴
- 按微信用户隔离连续对话，默认保留最近 8 轮、30 分钟
- 对话记忆仅在内存中，不写入磁盘；程序重启后自动清空
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
assistant.ilink.min-reply-delay-ms=13000
assistant.ilink.max-reply-delay-ms=17000
assistant.ilink.max-reply-chars=1800
assistant.conversation.max-turns=8
assistant.conversation.ttl-minutes=30
```

为了降低测试风险，不建议把延迟设置为 0，也不建议同时运行多个使用同一登录凭据的程序实例。
