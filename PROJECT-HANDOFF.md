# 项目对话交接说明

> 用途：开启新的 Codex 对话后，先让新对话完整阅读本文件，再继续开发。
>
> 最后更新：2026-07-22
>
> 项目目录：`D:\demo`

## 1. 用户目标与开发原则

这是一个个人开发的微信 AI 助手项目，不再依赖小组协作。项目目标是把聊天、天气、图片、文件、语音、提醒和知识库等能力统一放到 Function Calling 架构中。

后续开发必须遵守：

- 新的外部能力优先实现为 `AssistantTool`，使用阿里云 DashScope SDK 的 `FunctionDefinition` 声明工具。
- 工具默认由大模型根据名称、描述和 JSON Schema 自主选择。
- 附件处理、明确生图等必须保证真正执行的入口，可以保留最小化的确定性保护路由。
- 不再使用 `[TOOL:...]` 等文本协议，也不能让模型用文字假装已经执行工具。
- 每个新功能都要有清楚的成功、失败和耗时日志，禁止在日志中记录 API Key、Token、密码或附件原始字节。
- 修改后必须运行测试和打包；不要只改代码不验证。
- 当前工作区有大量尚未提交的有效改动，禁止使用 `git reset --hard`、`git checkout --` 等方式清除。

## 2. 当前项目状态

- 当前分支：`chen`
- Gitee 远程：`https://gitee.com/zhangdede_git/ykd-s301-2.git`
- 可手动推送：

```powershell
git push https://gitee.com/zhangdede_git/ykd-s301-2.git chen:chen
```

- 本地最新版已于 2026-07-30 部署到服务器 `101.200.53.44`，systemd、Actuator、Redis 和 Agent 类型接口均已在线验证。
- 最新 JAR：`D:\demo\target\wechat-agent-platform-1.0.0-boot.jar`
- 当前这次构建的 SHA-256（重新打包后可能变化）：

```text
2e7b08094d0b960aa0f5926becf84e3ce048f6a07130582788fab88496a82c16
```

- 最后一轮验证结果：`178` 项测试全部通过，失败 `0`，错误 `0`；JaCoCo 行覆盖率 `48.43%`，门禁 `45%`。
- `deploy-latest.ps1` 会在运行时自动计算当前 JAR 的 SHA-256，并校验服务器上传文件和安装文件，不需要手动同步固定哈希。

## 3. 技术栈

- Java 21
- Spring Boot 4.1.0
- DashScope Java SDK 2.22.15
- 微信社区 iLink SDK 2.3.3
- Redis：会话记忆和提醒
- Apache POI：Word、Excel、PPT
- PDFBox：PDF
- 和风天气 API
- Maven Wrapper、JUnit 5、Mockito
- Linux systemd 部署

## 4. 当前主要功能

### 对话和记忆

- 按微信用户隔离多轮对话。
- Redis 保存会话，24 小时无活动后滑动过期。
- 默认保留最近 20 个完整轮次，更早内容压缩为摘要。
- Redis 不可用时降级到内存和本地 JSON。
- `/reset`、`清除对话`、`重新开始` 会清除当前用户记忆。

### 图片和文件接收

- Bot 收到单独图片后立即回复已接收，并等待约 3 秒收集指令。
- 3 秒内收到文字时，文字和附件合并后再交给模型。
- 3 秒内没有文字时，图片默认识别，文件默认总结。
- 图片和文件在内存中缓存 10 分钟，支持后续的“背景换成夜景”“把刚才文件加入知识库”等指令。
- 附件内容不写入 Redis；服务重启后近期附件缓存会丢失。

### Function Calling 工具

当前注册了 26 个工具。下表是基础通用能力；Agent 与旅行域工具详见
`AGENT-PLATFORM.md` 和 `TRAVEL-AGENT.md`：

| 工具 | 作用 |
| --- | --- |
| `query_weather` | 实时天气和未来三天预报 |
| `generate_image` | 万相文生图 |
| `edit_image` | 换背景、增删内容、修复、扩图、增强等图片编辑 |
| `search_web` | DashScope 联网搜索并保留来源链接 |
| `search_knowledge_base` | 检索个人本地知识文件 |
| `add_to_knowledge_base` | 提取上传文档并写入个人知识库 |
| `manage_reminder` | 创建、查看、取消一次性或重复提醒 |
| `process_document` | 文档总结、问答、翻译、改写和 DOCX 导出 |
| `transcribe_audio` | 识别上传的音频文件 |
| `synthesize_speech` | 生成 WAV 语音文件 |
| `manage_voice` | 查看、切换个人音色 |

模型工具循环最多 4 轮，禁止并行工具调用。工具执行指标包括调用次数、成功数、失败数和平均耗时。

### 监控

本机管理接口：

```http
GET /api/admin/status
X-Admin-Token: <ADMIN_API_TOKEN>
```

返回运行时长、堆内存、工具清单和指标、活跃会话数、待执行提醒数、Redis 状态及知识库目录。`ADMIN_API_TOKEN` 为空时不鉴权，因此只能在服务绑定 `127.0.0.1` 时使用。

## 5. 核心代码位置

| 文件或目录 | 作用 |
| --- | --- |
| `src/main/java/com/example/demo/chat/AssistantService.java` | 对话和媒体请求总编排 |
| `src/main/java/com/example/demo/chat/FunctionCallingService.java` | 工具定义发送、模型工具循环、保护路由 |
| `src/main/java/com/example/demo/tools/` | 11 个工具及注册表 |
| `src/main/java/com/example/demo/ilink/IlinkChatRunner.java` | 微信消息接收、3 秒合并、附件缓存、回复发送 |
| `src/main/java/com/example/demo/chat/ConversationMemory.java` | Redis 会话记忆和本地降级 |
| `src/main/java/com/example/demo/chat/ImageEditingService.java` | 万相图片编辑 |
| `src/main/java/com/example/demo/search/WebSearchService.java` | DashScope 联网搜索 |
| `src/main/java/com/example/demo/knowledge/KnowledgeBaseService.java` | 本地知识库写入和检索 |
| `src/main/java/com/example/demo/reminder/ReminderService.java` | Redis 提醒、定时扫描、重复和重试 |
| `src/main/java/com/example/demo/monitoring/` | 工具指标和管理接口 |
| `src/main/resources/application.properties` | 所有默认配置和环境变量映射 |

进一步阅读：

- `TECHNICAL-DOCUMENTATION.md`：完整技术架构和部署说明
- `FUNCTION-CALLING.md`：新增工具的开发规范
- `ILINK-README.md`：微信 iLink 行为和限制
- `REDIS-MEMORY.md`：Redis 会话设计

## 6. 重要环境变量

服务器环境文件：`/etc/wechat-agent-platform/wechat-agent-platform.env`

必要变量：

```text
DASHSCOPE_API_KEY
DASHSCOPE_BASE_URL
QWEATHER_API_HOST
QWEATHER_API_KEY
REDIS_URL=redis://127.0.0.1:6379/0
```

主要可选变量：

```text
DASHSCOPE_MODEL=qwen3.7-plus
DASHSCOPE_IMAGE_MODEL=wan2.7-image-pro
DASHSCOPE_IMAGE_EDIT_MODEL=wan2.7-image-pro
CONVERSATION_REDIS_ENABLED=true
CONVERSATION_TTL_MINUTES=1440
KNOWLEDGE_BASE_PATH=/var/lib/wechat-agent-platform/knowledge
REMINDERS_ENABLED=true
REMINDERS_POLL_MS=10000
ADMIN_API_TOKEN=
```

不要把任何真实密钥写入代码、Markdown 或 Git。

## 7. 构建、测试和部署

完整测试与打包：

```powershell
cd D:\demo
.\mvnw.cmd clean test package
```

计算 SHA-256：

```powershell
Get-FileHash -Algorithm SHA256 "D:\demo\target\wechat-agent-platform-1.0.0-boot.jar"
```

服务器：`root@101.200.53.44`

最新版部署：

```powershell
cd D:\demo
.\deploy-latest.ps1
```

该脚本会上传 JAR 和 Redis 配置脚本、替换 `/opt/wechat-agent-platform/wechat-agent-platform.jar`、重启服务，并输出哈希、服务状态和日志。脚本需要手动输入服务器密码。

在线冒烟测试：

```powershell
.\smoke-live.ps1
```

服务器常用命令：

```bash
systemctl status wechat-agent-platform --no-pager
systemctl restart wechat-agent-platform
journalctl -u wechat-agent-platform -n 100 --no-pager
journalctl -u wechat-agent-platform -f
redis-cli ping
sha256sum /opt/wechat-agent-platform/wechat-agent-platform.jar
```

## 8. 已知限制和注意事项

- iLink 是第三方社区接入，不是微信官方 SDK，建议继续使用测试账号和低频运行。
- 微信原生语音气泡是 SILK，目前不支持；WAV、MP3 等音频文件可以识别。
- 图片编辑、联网搜索等云端能力会产生 DashScope 调用费用。
- 知识库当前是轻量本地文本检索，不是向量数据库，适合个人项目和中小规模资料。
- 提醒依赖 iLink 在线投递；发送失败后 60 秒重试。
- 近期附件缓存在单机内存中，不适合多实例部署。
- HTTP `/api/chat` 使用固定测试会话，不应作为正式多用户入口。
- 本地测试时若 Redis 没启动，日志会出现一次降级警告，这是预期行为。
- 本轮已验证服务器健康检查、Redis、制品哈希、微信会话恢复和 5 类 Agent 注册；图片编辑、文档导入知识库和到期提醒仍建议在微信中做一次人工端到端验收。

## 9. 新对话建议的第一条指令

可以把下面这句话发给新对话：

```text
请先完整阅读 D:\demo\PROJECT-HANDOFF.md、D:\demo\TECHNICAL-DOCUMENTATION.md 和 D:\demo\FUNCTION-CALLING.md，再检查当前 Git 状态。不要清理或覆盖现有未提交改动。读完后先用几句话总结你理解的项目现状，再继续我的新需求。
```
