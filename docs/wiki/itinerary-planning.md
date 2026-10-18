# 候选如何形成可审核、可展示的行程

[项目地图](index.md) · [外部工具](external-tools.md) · [业务政策](travel-business.md) · [归属与存储](data-and-identities.md)

行程规划 Agent 编排搜索和业务工具；规划应用服务基于已保存的标准候选与可信政策做客观计算，审核服务判断方案，页面服务检查发布资格后生成 HTML。这几步分别有结果和状态，不能用模型总结或任务清单代替它们。

## 从搜索到发布的主流程

```mermaid
flowchart TB
    Search[查询交通 / 酒店 / 具体房型] --> Candidates[按可信归属保存标准候选]
    Candidates --> Plan[加载候选快照，校验并计算往返方案]
    Policy[可信政策 / 偏好 / 可选差旅单] --> Plan
    Plan --> Saved[保存 planId 与方案集合]
    Saved --> Review[重新收集事实，执行各维度审核]
    Review --> Action{仲裁并应用修复上限}
    Action -->|PROCEED| Gate[校验最新规划 / 审核 / 修复状态]
    Gate --> Page[生成 HTML 并保存 OSS]
    Page --> User[授权读取并展示给用户]
    Action -->|REPLAN| Repair[调整条件或候选后有限重规划]
    Repair --> Plan
    Action -->|RETRY_REVIEW| Review
    Action -->|需要输入 / 人工审批 / 停止| Explain[保留真实结果，说明下一步]
```

这是工具与应用服务定义的业务流程，不表示模型在每次真实会话中都已经正确完成所有步骤。当前主要实现面向单目的城市的往返与住宿方案；多城市、无住宿等扩展场景不列为本次已验收能力。

## 候选与计算的可信输入

`ItinerarySearchApplicationService` 保存当前页候选，候选仓储按用户、根业务会话和完整行程条件分组。规划读取对应的去返交通和住宿分组快照；调用方可以引用已保存候选，但不能提交一批任意明细来冒充供应商查询事实。

规划应用服务不重新发起供应商搜索，也不渲染页面。其主步骤是参数/归属校验、关联差旅单核对、候选引用与排除项处理、过滤不可计算候选、生成时间有效的往返组合、费用耗时与评分计算、保存完整结果，再推进修复次数。

酒店列表起价不等于具体房型成本。价格口径 `UNKNOWN`、时间或必要报价字段不足时，不用默认值补成确定总价。缺少可计算的住宿候选可能使有住宿规划无法形成有效结果；这与外部查询成功并不矛盾。

## 审核完整性不等于业务通过

| 层次 | 值或含义 | 读结果时关注什么 |
| --- | --- | --- |
| 执行完整性 | `COMPLETE / PARTIAL / FAILED` | 必需审核维度是否真正形成结论 |
| 业务结论 | `PASS / WARNING / BLOCKED` | 方案是否合规、可行、有风险或阻断 |
| 下一动作 | `PROCEED`、`REPLAN`、`RETRY_REVIEW`、请求用户/人工、停止等 | 本轮流程实际应如何推进 |

当前定义差旅单一致性、政策合规、执行可行性、体验偏好、韧性五类审核维度；实际必需维度与审核器由规则集装配，不能只数类名就认定全部维度已启用或通过。

审核前重新读取关联差旅单当前状态，规划内旧快照只用于关联核对。单个审核器异常形成该维度未完成/失败记录，不阻止其他维度给出结果；最终由仲裁器汇总，而不是以审核调用正常返回就宣布可发布。

## 修复停止条件

`ItineraryRepairCycleValidatorService` 当前允许同一任务最多两次修复重规划，同一方案的审核不完整最多再重试一次。失败的规划计算或保存不消耗修复次数；规划保存、审核报告保存成功后才推进相应状态。

新方案应先审核，不能绕过待审核状态连续规划。等待用户输入或流程关闭时也不能在同一轮通过再次调用规划工具重置次数。`WARNING` 且已有推荐方案的 `PROCEED` 不强制自动修复；停止条件不能反过来篡改原审核事实。

## 页面发布再次验证什么

发布服务读取当前可信归属下的规划、最新审核和修复状态，同时要求：当前方案的修复循环已关闭，行程范围一致，审核 `COMPLETE` 且下一步为 `PROCEED`，推荐 ID 在规划及可推荐审核结果中都存在。

```text
owner + planId -> 读取规划和修复状态
检查当前方案与行程范围 -> 读取最新审核
检查完整性、下一动作、推荐 ID 一致性
用同一快照渲染 HTML -> 保存对应 reviewId 的 OSS 对象
读取页面时再次校验当前资格
```

`CLOSED` 本身不等于审核通过，已有 OSS 页面也不等于当前允许展示。`GET /api/itinerary/plans/{planId}/page` 使用登录用户和业务会话限定归属，并关闭响应缓存；客户端不会因为知道对象路径就获得任意方案访问权。

当前没有从该页面自动完成预订的闭环承诺。完整规划与审核在各种真实供应商响应下的效果、全流程时延、用户修改和异常恢复仍需专门联调；本轮只整理实现。

## 源码与验证入口

- [规划工具](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/agent/tool/ItineraryPlannerTools.java)、[规划应用服务](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/itinerary/ItineraryPlanApplicationService.java)、[候选仓储](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/infrastructure/repository/ItineraryCandidateRepository.java)：候选快照与方案计算入口。
- [审核服务](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/itinerary/ItineraryReviewApplicationService.java)、[审核编排](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/itinerary/reviewer/ItineraryReviewOrchestrator.java)、[审核维度](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/common/constants/ItineraryReviewDimension.java)：事实重新加载与仲裁。
- [修复校验器](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/itinerary/validator/ItineraryRepairCycleValidatorService.java)、[发布资格校验器](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/itinerary/validator/ItineraryPublicationEligibilityValidatorService.java)：停止与发布门禁。
- [页面应用服务](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/itinerary/ItineraryPlanPageApplicationService.java)、[页面存储](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/infrastructure/repository/ItineraryPlanPageStore.java)、[页面 Controller](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/controller/ItineraryPlanPageController.java)。
- [已有行程测试](../../fons4ai-trip-agent-service/src/test/java/com/fons/cloud/ai/trip/application/itinerary/)：可阅读发布、修复和页面的场景定义，本轮未运行。
