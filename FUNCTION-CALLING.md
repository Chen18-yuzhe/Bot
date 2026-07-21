# Function Calling 开发规范

项目的普通对话、天气查询和文生图路由已统一使用 DashScope Java SDK 的原生 Function Calling：

- `FunctionDefinition` 声明名称、用途和 JSON Schema。
- `ToolFunction` 将函数注册到 `GenerationParam.tools(...)`。
- `FunctionCallingService` 接收模型返回的 `ToolCallFunction`，执行 Java 工具，并用带 `toolCallId` 的 `tool` 消息回传结果。
- 每轮最多执行 4 轮工具调用，未知工具、非法 JSON、缺失参数都会被拒绝。

## 新增工具

1. 在 `com.example.demo.tools` 中新增实现 `AssistantTool` 的 Spring Bean。
2. 使用 `FunctionDefinition.builder()` 定义唯一、稳定的英文工具名。
3. 参数必须使用严格 JSON Schema，列出 `required` 并设置 `additionalProperties: false`。
4. 在 `execute(JsonNode, ToolContext)` 中再次校验全部参数；不能只信任模型生成的 JSON。
5. 查询或运算结果使用 `ToolResult.text(...)`；生成图片使用 `ToolResult.image(...)`。
6. 为定义、参数校验和执行逻辑编写单元测试。

工具会由 Spring 自动注入 `AssistantToolRegistry`，不需要修改中央 `switch`。禁止新增 `[TOOL:...]`、正则意图解析或让模型伪造实时数据。

## 分层边界

- iLink 层负责附件下载、3 秒合并、缓存、消息幂等和结果发送。
- Function Calling 层负责模型决策、结构化参数、工具执行循环和结果回传。
- 具体工具负责调用天气、图片、文档、语音等确定性服务。
- 图片和文件本体不要放进 JSON 参数；后续媒体工具应通过 `ToolContext` 中的会话资源标识读取本轮附件。

现有工具：

- `query_weather(city, queryType)`
- `generate_image(prompt)`
