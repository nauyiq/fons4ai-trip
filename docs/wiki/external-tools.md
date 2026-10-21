# 外部信息与供应商工具怎样接入

[项目地图](index.md) · [候选与规划](itinerary-planning.md) · [数据身份](data-and-identities.md) · [工程约定](工程约定.md)

Trip 有两种主要接入方式：业务工具委托应用服务/Client，将供应商结果转换为标准业务数据；天气 MCP 则由 SDK 客户端发现工具并按配置白名单装配。工具类存在、MCP 初始化成功、查询成功和业务结果可用分别需要依据。

## 当前接入路径

```mermaid
flowchart TB
    Agent[业务子 Agent] --> Tool[Trip Tool：可信身份与参数适配]
    Tool --> Search[搜索应用服务]
    Search --> Client[ItinerarySearchClient 策略]
    Client --> Tuniu[途牛 Streamable HTTP MCP]
    Client --> Convert[转换为标准交通 / 酒店候选]
    Convert --> Search
    Search --> Repo[按用户 / 根会话 / 行程分组保存]
    Tool --> Other[天气 HTTP / 签证 Client]
    Tool --> NewsQuery[News 查询应用服务：统一候选规则]
    Review[行程审核事实收集] --> NewsQuery
    NewsQuery --> NewsClient[News Client：单页 HTTP 与协议转换]
    Agent --> MCP[WeatherMcp 已启用工具白名单]
    MCP --> Weather[天气 Streamable HTTP MCP]
```

图只描述已定位的装配与调用职责，不表示每次请求会调用全部供应商。候选先保存到业务仓储，再供规划引用；不能把供应商大段原始结果直接当作可信规划输入。

| 能力 | 当前入口 | 关键契约 |
| --- | --- | --- |
| 当前天气与短期预报 | `DestinationLiveTools` → `WeatherClient` | 工具声明支持今天及未来两天；以实际返回日期和城市校验，不代替远期预报 |
| 更长周期天气 | `WeatherMcp` | Streamable HTTP + Bearer 认证，发现后只装配启用列表；目标日期必须被实际响应覆盖 |
| 火车票、机票 | `ItinerarySearchTools` → 搜索应用服务 → `TuniuSearchClient` | 单程、分页、席别/舱位、时区、金额和库存分别保留 |
| 酒店与房型 | 同上，先酒店列表再具体房型 | 列表起价、具体报价和完整住宿总价不同；人数、儿童年龄、间夜条件保持一致 |
| 目的地资讯 | `DestinationNewsQueryApplicationService` → `DestinationNewsClient` | 对话与审核共用候选门槛、基础去重；保留本页原始数量、来源及时间，空结果和外部失败分开 |
| 签证 | `OriznVisaClient` / `VisaTools` | 按护照签发国、目的地国家及返回适用条件解释，不把信息查询等同于担保结论 |

## MCP 发现与业务装配

`WeatherMcp` 使用 AgentScope 的 MCP 客户端包装，读取 `trip.mcp.weather`。`MasterAgentConfig` 仅在客户端非空时向规划/信息 Agent 注册启用工具。端点、认证和工具名都必须匹配实际服务，不能把“工具列表能返回”当作调用已成功。

途牛 Client 使用 MCP Java 客户端，经 `apiKey` 请求头发送企业服务凭据，配置来源为 `trip.mcp.tuniu`。当前路径不从员工个人 `user_api_key` 读取途牛搜索凭据；`ApiKeyTools` 的存在不代表它已装配到这些 Agent。不要把密钥交给模型或保存进候选。

当前主模型适配与供应商 MCP 客户端是不同协议边界；调整模型 API Base 不会自动修复 MCP 的认证或 Endpoint。

## 响应、分页与报价

`TuniuSearchClient` 优先读取 MCP `structuredContent`，兼容文本块中的明确业务 JSON。针对单字段 `{"result": ...}` 包装只解开一层，再按原业务状态、数据数组与条目结构校验；不递归剥离任意包装，也不忽略业务失败标记。

标准候选区分班次和报价：同车次不同席别可以是不同候选。分页沿用原查询条件及继续查询凭据；返回的当前页与仓储累计候选池不是同一集合。换路线、日期或入住条件时重新确认归属，避免旧分页参数串查询。

报价以 `SearchPriceBasis` 显式区分单人单程、单间每晚、单间全程和 `UNKNOWN`。当前酒店供应商响应缺少足够依据时保留原始金额并标记 `UNKNOWN`，不根据住几晚、取消费用或历史样本反推总价。规划服务会排除不满足客观计算条件的候选，具体在规划专题维护。

## News 单页数量、候选与去重

2026-10-21 的源码实现由查询应用服务统一供对话工具与审核输入使用：服务端按城市和固定主题词构造表达式，Client 使用 qInMeta 和 removeduplicate=1；成功返回后按标题/摘要中的城市、主题证据筛选，再按规范链接或标题去重并保留第一条。供应商去重不替代本地门槛，也不保证不同标题的同一事件已经合并。生产编译通过；新会话 `TRC179255489111093049e` 的综合资讯真实返回5条、保留5条并展示2个事件，模型归并同一大会报道。交通续问返回5条、保留1条，模型排除历史回顾内容后展示0个交通运行事件；两次审批恢复和刷新均完成，回复未再误述许可。供应商交通覆盖仍有限，其余边界未全部验收，具体Run及证据见 [本轮任务](../development/features/20261021-news-quality-and-approval-narration/任务规划.md)。

`retrievedCount` 是供应商本页原始条目数，`news.size()` 是保留候选数，不是全网命中数或最终事件数。available=false 表示未配置且未请求；成功返回0条、返回若干条但过滤为空、供应商失败分别表达。旧工具结果没有数量字段时保持未知，不回填为0。链接去重移除 utm_* 和片段，规范协议/主机大小写及标准端口，保留新闻身份参数，不合并 HTTP 与 HTTPS，也不访问原文链接。严格的城市/主题文本门槛会漏召回，同名实体及跨标题转载仍需模型核对，新闻不能代替实时运行状态。

审核只从统一候选中取每城最多3条符合既有元数据门槛的证据；未配置、无候选、缺发布时间及查询失败分别沿用 NEWS_* 覆盖缺口。此处的候选处理是已实现代码，审核运行分支仍待专项验证；详细规则和剩余验收见 [本轮任务](../development/features/20261021-news-quality-and-approval-narration/任务规划.md)。

## 失败与重复调用边界

| 情况 | 应保留的含义 |
| --- | --- |
| 成功但无候选 | 真实空结果；不虚构班次或酒店 |
| 参数或日期范围不支持 | 说明具体限制，调整条件或请求补充 |
| 鉴权、超时、响应解析失败 | 外部/系统失败，不改写为“没有票” |
| 报价缺少单位或计价口径 | 保留不确定性，不能进入确定总价计算 |
| 可选天气资讯失败 | 不应抹去已经获得的核心交通住宿结果；实际是否阻塞还需核对工具与审核策略 |

业务工具保护中间件、SDK 单次调用重试与模型下一轮再次调用是不同机制。`toolFailureLimit` 默认限制同一次子 Agent 调用中同一业务工具的系统失败次数，非阻塞可选工具另行配置；它不是给所有 MCP/文件工具统一增加可靠性保证。

提示词对日期选择、禁止编造、失败后不重复和确认操作有指导作用；只有代码、SDK 权限或实际服务执行的约束才可表述为强制保证。

## 新增或调整工具的阅读路线

先定位业务 Agent 的实际 Toolkit，再检查 `BaseTool.tools()` 名单、失败保护和可选 ASK 规则；供应商参数/返回由 Client 转换，业务应用服务保存可信候选，最后核对页面与规划能否消费。

知识初始化时未调用供应商。历史天气、交通住宿只读查询有局部联调材料，候选根会话与酒店未知价格口径曾重点核对；这些不能作为当前全部日期、分页、供应商故障或规划闭环已通过的证明。

2026-10-20 补充了 News 最小真实联调，会话为 `TRC17924715554149a18a0`，本地证据位于服务 `target/manual/news-e2e-20261020/`：

- 上海综合资讯查询经过一次工具审批后成功调用 NewsData.io，实际返回 5 条资讯；标题、发布时间及 UTC 时区、媒体来源和原文链接已进入工具结果，页面展示了查询结果。恢复 Run `TRT17924716414739a18a4` 已保存 `used_tools=query_destination_news`、`provider=newsdata.io` 及已知 Token 小计；用量仍标记不完整，不能称为全部消耗。刷新后结果保留。
- 接口可用不等于差旅相关性合格。本次结果相关性偏弱，且有同一事件的多渠道转载；Agent 虽提示了相关性限制，仍把重复报道单独列为一条。当时工具/Client 未提供业务候选筛选或基础去重，2026-10-21 的代码调整见上文，不据此改写旧验证结果。
- 同会话续问上海交通主题时，`agent_send` 在框架委派结果核验处出现 `Delegation result has no matching native spawn entry`；随后子会话显示等待审批，但前端没有新的审批卡。该 Run 为 `TRT17924717678419a18a7`，未实际调用 News 接口，不能记为交通主题验证通过。后续已确认根因：SDK 2.0.3的send返回头缺少agent_id，而框架误用了spawn的必需类型校验；修复及复验如下。

框架修复后的复验会话为 `TRC179247508618314623a`，本地证据在服务 `target/manual/news-agent-send-fix-20261020/`，安装的starter SHA-256为 `19CE8D119AAB39806E33436E9D4FB96697E0AC356627C36B8A98E679405CA6F8`：

- 首次spawn审批及恢复成功后，续问Run `TRT1792492772080146241` 使用agent_send，正常以suspended结束并发布新的上海交通审批卡；两轮审批来自同一个InfoAgent子会话，使用不同的pauseId，未复用首轮决定。
- 新审批恢复Run `TRT1792492857943146244` 实际调用 `query_destination_news`，以success保存，供应商为newsdata.io；两张审批票证最终均为CONSUMED，会话占用已清空。已知Token小计为20705，仍标记用量不完整。整页刷新后审批处理状态、结果和摘要均保留。
- 上海交通查询实际返回5条关键词匹配资讯，Agent判断相关条目为0并说明不能据此判断交通正常；检索相关性仍需优化。最终回复中“本次查询未触发新的执行许可审批”与真实卡片和票证记录不符，属于另一个待处理的流程描述问题，不能用该句否定已发生的审批。供应商鉴权失败、额度、真实空数组及其他主题未在本轮逐项验证。

## 依据与入口

- [WeatherMcp](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/agent/mcp/WeatherMcp.java)、[MCP 属性](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/infrastructure/config/properties/TripMcpConfigProperties.java)、[Master 装配](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/agent/core/MasterAgentConfig.java)。
- [搜索工具](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/agent/tool/ItinerarySearchTools.java)、[搜索服务](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/itinerary/ItinerarySearchApplicationService.java)、[客户端接口](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/infrastructure/client/api/ItinerarySearchClient.java)。
- [TuniuSearchClient](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/infrastructure/client/TuniuSearchClient.java)、[供应商转换](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/infrastructure/client/model/tuniu/TuniuSearchConverter.java)、[计价口径](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/common/constants/SearchPriceBasis.java)。
- [天气/资讯工具](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/agent/tool/DestinationLiveTools.java)、[签证工具](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/agent/tool/VisaTools.java)、[业务工具保护](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/infrastructure/middleware/TripToolExecutionGuardMiddleware.java)。
- [资讯查询服务](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/news/DestinationNewsQueryApplicationService.java)、[News Client](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/infrastructure/client/DestinationNewsClient.java)、[主题](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/common/constants/DestinationNewsTopic.java)、[结果契约](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/common/dto/DestinationNewsQueryResult.java)：两入口共用规则及单页数量。
