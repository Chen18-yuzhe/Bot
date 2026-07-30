# 微信旅游 Agent

## 用户体验

在微信中切换到 Agent 模式后，用户直接描述目标即可：

```text
/agent
国庆想去杭州玩 3 天，2 个人，预算 3000 元，喜欢历史和美食
```

框架会把该目标路由到 `travel` 任务类型。第一次生成均衡经典、轻松深度、兴趣主题三类方案；之后用户只说“第二个方案”“查西湖门票”“确认购买”时，会携带同一微信会话最近上下文继续执行。

自然控制命令：

- `停止任务`：在当前步骤边界保存断点并进入 `PAUSED`。
- `恢复任务`：从断点继续。
- `任务进度`：查看最新任务状态。
- 登录、实名或权限不足时任务进入 `WAITING_HUMAN`；完成处理后使用 `/agent #任务号 人工 <处理结果>` 继续。

当前个人版 Agent 会选择最合适的商品方向并打开工具核验过的商品详情页，再根据用户发回的页面截图指出应选择的可见票种、日期和套票。最终 SKU、实名信息与付款由用户在外部平台确认。本服务不接收身份证号、银行卡号、支付密码或验证码。

## 上下文与清理

会话采用 SQLite 持久化、Redis 热缓存和进程内缓存三层结构：

- SQLite 文件由 `SQLITE_DATABASE_PATH` 指定，是重启后的主要恢复来源。
- Redis 使用相同滑动 TTL，Redis 故障时继续从 SQLite/内存读取。
- 默认保留 7 天：`CONVERSATION_TTL_MINUTES=10080`。
- 每小时清理一次过期 SQLite 会话和内存会话：`CONVERSATION_CLEANUP_INTERVAL_MS=3600000`。
- Agent 只带入最近 3 轮、最多约 3000 字符，并通过 SQLite 读取最近结构化行程，避免上下文无限增长。

## Agent Loop

旅游任务使用现有持久化 Agent Loop：

1. 模型选择下一步工具。
2. 框架执行工具并记录 checkpoint、trace、步骤数和 Token。
3. 网络超时、502/503/504、连接重置等归为 `TRANSIENT`，框架指数退避重试，重试过程不再次调用 LLM。
4. 工具可以返回 `degraded` 产物，例如地图 API 未接入时保留站点顺序、票务未接入时返回“非实时”结果。
5. `AUTH_REQUIRED`、`PERMISSION_DENIED` 自动进入人工接管。
6. 每个步骤边界均持久化；暂停、进程重启后可以恢复，删除只允许用于已结束任务。

旅游任务只允许调用以下白名单工具：

- `travel_discovery`：景点发现、地图路线；当前内置北京、杭州、上海、成都、西安基础目录。
- `travel_plan`：生成/读取三种多日方案。
- `query_weather`：现有和风天气实时/三日预报。
- `search_web`：查询临时闭馆、活动和具体票务商品页面。
- `travel_checkout`：购票导航与外部结果记录。保存推荐商品方向、建议票种、数量、套票取舍和商品详情入口；付款后可登记外部订单与电子票。
- `travel_transport`：远途火车与航班比较。火车适配聚合数据/供应商接口；航班适配 Amadeus Flight Offers Search。
- `travel_fliggy_search`：只读调用飞猪 FlyAI，查询火车、航班、酒店、景点和旅行产品，保留其返回的 `jumpUrl`；不创建订单、不接触实名信息或付款。
- `travel_ticket`、`travel_ticket_market`、`travel_order`：代码边界仅为未来商业供应商接入保留，不在个人旅游 Agent 的工具白名单中。

## 个人版购票导航

当前默认流程：

1. Agent 结合已选行程确定必须购买的景点、游玩日期和人数。
2. 使用飞猪只读结果比较候选商品方向，排除日期或适用人群不符以及包含无关演出、餐饮、接送等内容的套票。
3. FlyAI 返回的 `jumpUrl` 只标记为 `PRODUCT_DETAIL`；不声称它已预选 SKU，也不声称价格和库存实时。
4. 用户打开详情页并发回截图，Agent 结合近期行程上下文识别页面可见选项，指出应选项和排除项。
5. 用户在外部页面最终确认日期、票种、数量、价格、库存和退改规则，然后本人登录、填写实名信息并付款。
6. 用户将平台订单号、订单截图或电子票发回微信，Agent 可登记到本地行程；敏感字段只展示末四位。
7. 取消或退款由用户在原渠道完成，Agent 保存进度和最终退款结果。

`providerProductId`、`providerSkuId` 和 `HOSTED_CHECKOUT` 仅作为未来接入商业票务供应商时的扩展边界保留。当前个人 Agent 不调用该路径，也不会要求 FlyAI 返回这些字段。系统继续拒绝 HTTP、搜索页、占位链接和示例域名。

支付宝 AI 付可以在将来接管已经生成的支付宝订单链接，但它不提供景区商品、SKU、实时价格或库存，也不替票务商创建订单。因此当前个人版不把它放入购票主链路；拿到支持下单的票务供给后，再把它作为用户授权的支付执行层接入。

## 待申请的外部 API

所有配置默认留空；未接入时系统不会伪造实时票价、库存、支付或出票。半自动购票本身不需要申请微信支付或票务供应商 API。

| 能力 | 可选供应方 | 环境变量 |
| --- | --- | --- |
| 地图、POI、路线 | 高德地图、腾讯位置服务 | `TRAVEL_MAP_API_URL`、`TRAVEL_MAP_API_KEY` |
| 景点详情 | 地图 POI、景区/OTA 开放平台、自建 CMS | `TRAVEL_ATTRACTION_API_URL`、`TRAVEL_ATTRACTION_API_KEY` |
| 天气 | 和风天气（项目已有适配器） | `QWEATHER_API_HOST`、`QWEATHER_API_KEY` |
| 实时票务、锁库存、出票、退票（个人模式不需要） | 获批的 OTA B2B 或景区直连接口 | `TRAVEL_SUPPLIER_API_URL`、`TRAVEL_SUPPLIER_API_KEY` |
| 精确 SKU、实时价与库存 | 票付通等获批票务分销平台的适配器 | `TRAVEL_TICKET_MARKET_API_URL`、`TRAVEL_TICKET_MARKET_API_KEY` |
| 火车时刻/可售班次 | 聚合数据或获批的飞猪火车票接口 | `TRAVEL_RAIL_API_URL`、`TRAVEL_RAIL_API_KEY` |
| 航班报价比较 | Amadeus Self-Service | `TRAVEL_FLIGHT_AMADEUS_CLIENT_ID`、`TRAVEL_FLIGHT_AMADEUS_CLIENT_SECRET` |
| 飞猪只读旅行信息 | 飞猪 FlyAI CLI（可无 Key 使用，正式 Key 可提高额度/稳定性） | `TRAVEL_FLIGGY_ENABLED`、`TRAVEL_FLIGGY_COMMAND`、`FLYAI_API_KEY` |
| 实名核验（个人模式由原购票渠道完成） | 合规实名服务或票务供应商内置能力 | `TRAVEL_IDENTITY_API_URL`、`TRAVEL_IDENTITY_API_KEY` |
| 微信登录 | 微信小程序 `code2Session` / OAuth | `TRAVEL_AUTH_SIGNING_KEY` |
| 微信支付 V3（个人模式不需要） | 微信支付商户平台 | `WECHAT_PAY_MERCHANT_ID`、证书/APIv3 密钥；当前配置仅预留回调边界 |
| 行程/票券推送 | 微信订阅消息、客服消息或卡券 | `WECHAT_MESSAGE_API_URL`、`WECHAT_MESSAGE_API_KEY` |

接入真实供应商时，需要在 `TravelIntegrationGateway` 后实现供应商专用适配器，并保留现有的来源标记、报价过期校验、金额复核、幂等和回调验签。
