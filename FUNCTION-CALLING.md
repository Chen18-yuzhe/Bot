# Function Calling 开发规范

项目的外部能力统一使用 DashScope Java SDK 的原生 Function Calling：

- `FunctionDefinition` 声明名称、用途和 JSON Schema。
- `AssistantToolRegistry` 把每个函数包装为 `ToolFunction`，注册到 `MultiModalConversationParam.tools(...)`。
- `FunctionCallingService` 接收模型返回的 `ToolCallFunction`，执行 Java 工具，并用带 `toolCallId` 的 `tool` 消息回传结果。
- 支持顺序链式调用：模型可以多次调用同一工具，也可以把前一步结果用于后续不同工具。
- 每个请求最多执行 6 轮模型工具决策、8 次实际工具调用，单个工具最多 5 次。
- 工具链守卫会规范化参数后识别重复调用；相同参数成功执行后禁止再次执行，避免重复提醒、重复写入和重复产生云端费用。
- 同一工具连续失败或返回无有效结果时只允许修正参数重试一次；再次无进展后关闭工具调用，要求模型向用户澄清。
- 工具执行失败会以结构化失败结果回传模型，模型可以调整参数、改用其他工具或基于已成功步骤回答；未知工具和非法 JSON 仍会被拒绝。
- 始终使用 `parallelToolCalls(false)`，保证有依赖关系的工具按顺序执行。

## 新增工具

1. 在 `com.example.bot.tools` 中新增实现 `AssistantTool` 的 Spring Bean。
2. 使用 `FunctionDefinition.builder()` 定义唯一、稳定的英文工具名。
3. 参数必须使用严格 JSON Schema，列出 `required` 并设置 `additionalProperties: false`。
4. 在 `execute(JsonNode, ToolContext)` 中再次校验全部参数；不能只信任模型生成的 JSON。
5. 按结果类型使用 `ToolResult.text(...)`、`image(...)`、`document(...)` 或 `audio(...)`。
6. 为定义、参数校验和执行逻辑编写单元测试。

工具会由 Spring 自动注入 `AssistantToolRegistry`，不需要修改中央 `switch`。禁止新增 `[TOOL:...]` 文本协议或让模型伪造实时数据。工具选择默认交给模型；只有附件类型、明确生图等必须保证执行的入口，才允许增加轻量的确定性保护路由。

## 分层边界

- iLink 层负责附件下载、3 秒合并、缓存、消息幂等和结果发送。
- Function Calling 层负责模型决策、结构化参数、工具执行循环和结果回传。
- 具体工具负责调用天气、图片、文档、语音等确定性服务。
- 图片和文件本体不要放进 JSON 参数；媒体工具通过 `ToolContext` 读取本轮附件。

现有工具：

- `query_weather(city|cities, queryType)`：单城市查询兼容原参数；比较天气时可用 `cities` 一次查询 2～5 个城市
- `generate_image(prompt)`
- `edit_image(instruction)`
- `search_web(query)`
- `search_knowledge_base(query, limit)`
- `add_to_knowledge_base(name)`
- `manage_reminder(action, ...)`
- `process_document(instruction, output)`
- `transcribe_audio(instruction)`
- `synthesize_speech(text, voice)`
- `manage_voice(action, voice)`
- `query_time(location, timeZone|locations)`：查询世界各地区当前时间；省略参数时默认北京时间，批量最多8个地区
- `scientific_calculator(expression, angleUnit, precision)`：本地精确科学计算，默认三角函数使用角度制
- `calculate_body_fat(gender, heightCm, waistCm, neckCm, hipCm)`：成人美国海军围度法体脂率估算，结果非医疗诊断
- `lookup_daily_info(category, query, ...)`：联网查询全网热搜、城市 AQI、营养成分、菜谱或中国大陆节假日；返回内容保留来源

所有工具调用都会记录工具名、轮次、成功或失败状态及耗时指标（含 Micrometer P95/P99 与 Redis 用量日桶），但不会记录 API Key 或附件字节。

计费上游（生图、图片编辑、联网搜索）由 `UpstreamGuard` 熔断保护：连续失败后快速失败一段时间再放行探测；参数错误和内容审核拒绝不计入熔断。微信消息入口按用户每分钟限流（`assistant.rate-limit.*`）。

## 链式调用约定

- 同一问题需要多个实时事实时，模型必须先获得全部必要事实，再生成最终回答。例如比较北京和杭州天气时，应批量查询两个城市。
- 后一步依赖前一步结果时，使用多轮顺序调用。例如“查询天气，如果下雨就创建提醒”先调用天气工具，再根据实际结果决定是否调用提醒工具。
- 工具失败结果包含 `success=false`、错误码、可读消息和是否建议重试。失败动作不得被描述为已经完成。
- 工具可以用 `ToolResult.noResult(...)` 明确表示“执行正常但没有命中”。这类结果不计为成功进展，不能触发无限换词重试。
- 保护触发后下一轮强制使用 `toolChoice=none`。如果模型仍返回工具请求，服务使用固定澄清回复兜底，不再执行外部调用。
- 工具自身存在天然批量语义时，优先提供受限批量参数；复杂的跨能力流程继续交由通用模型工具循环编排。

## 健康与生活信息边界

- 科学计算和体脂率必须调用本地工具，避免模型心算误差。
- 全球时间复用 `query_time`，默认北京时间；不新增重复工具。
- 热搜、空气质量、营养成分、菜谱和节假日使用 `lookup_daily_info` 联网获取，避免将过期数据当作实时结果。
- “健康小妙招”属于通用生活建议，不单独调用工具；不得诊断疾病、替代医生或承诺疗效。涉及急性症状、孕期、未成年人、用药或慢性病管理时，应建议用户咨询专业人士。
