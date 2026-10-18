# Trip 项目地图

[项目概览与运行](../../README.md) · [Agent 协作指引](../../AGENTS.md) · [工程约定](工程约定.md)

Trip 围绕对话查询、差旅申请和方案整理工作。先按下表找到业务问题，再沿专题中的少量入口阅读实现；项目介绍和运行命令在 README 单处维护。

## 当前核查基线

本次初始化日期为 **2026-10-18**。依据 Trip 本地 `feature_front` 分支 `d328b7a` 加未提交改动，以及相邻 Fons4AI 仓库 `044d783` 的相关实现与设计。Trip 依赖声明是 Fons4AI `1.0.0`；源码提交、Maven 同版本产物和正在运行的服务不是同一个身份。

本轮只读核查源码、配置结构、构建清单、已有测试及历史记录，没有运行单元测试、重新发起 LLM/供应商请求或查询数据库。各页描述已定位的实现，局部历史验证不能扩大为完整端到端或生产保证。数据库图表示代码/迁移定义，实际库结构仍须按授权只读核验。

## 按问题选择专题

| 要理解的问题 | 主要入口 | 专题 |
| --- | --- | --- |
| 一条请求如何到页面？停止、标题、历史与删除怎样衔接？ | `ConversationController` → `ConversationApplicationService` → `ChatStreamOutputAdapter`；前端 `api/chat.ts`、`ChatWindow` | [会话执行与前端交互](conversation.md) |
| 规则、改写、意图与 Master 如何协作？多项审批如何恢复？ | `AgentExecutePipeline`、步骤 Handler、`MasterAgentConfig`、`ApprovalBatchCard` | [Agent 编排与 HITL](agents-and-hitl.md) |
| 差旅单和政策有哪些业务限制？什么叫审批成功？ | `TravelBusinessApplicationService`、`TravelPolicyApplicationService`、领域服务 | [差旅业务与政策](travel-business.md) |
| 外部数据如何进入系统？报价、分页、错误如何转换？ | `BaseTool`、`WeatherMcp`、`TuniuSearchClient`、`ItinerarySearchApplicationService` | [外部工具与供应商接入](external-tools.md) |
| 候选怎样形成可展示方案？审核不完整或修复耗尽时怎么办？ | 规划、审核、修复校验器、页面发布服务 | [行程规划、审核与展示](itinerary-planning.md) |
| 哪个 ID 用于查会话、候选、运行或 Trace？谁是数据权威？ | `ChatConversation`、`ChatRequestTrace`、`ChatMessageHitl`、`TripRuntimeContext`、仓储 | [数据归属与身份](data-and-identities.md) |
| 框架提供什么，Trip 怎样接入？异常先查哪里？ | 服务 POM、`MasterAgentConfig`、配置类、`TripObservationConvention` | [框架接入与运行排查](framework-integration.md) |

## 推荐阅读路线

- 理解主链路：会话执行 → Agent/HITL → 根据任务选择业务专题。
- 调整供应商工具：外部工具 → 数据归属 → 行程规划 → 对应 Client 源码。
- 排查运行问题：会话执行中的当前状态 → 框架接入 → 对应 Run/Trace 与真实结果。
- 修改领域逻辑：对应业务专题 → 工程约定 → 应用服务、领域服务和已有测试。

## 已有验证入口与待核实范围

已有测试集中于 [服务测试目录](../../fons4ai-trip-agent-service/src/test/java/com/fons/cloud/ai/trip/)：对话应用、SSE、页面发布、审核修复校验、签证客户端与渲染器。本次仅将其作为阅读线索，未执行，也不推断覆盖了所有业务。

本地历史手工材料位于服务 `target/manual/` 中的天气、途牛、观测、取消及租约目录。它们是可被构建清理的环境产物，不是克隆仓库必然获得的证据；引用历史结果时应核对具体场景和产物，长期结论应连同适用边界保存在受影响专题或实际需要的开发记录中。

当前需要持续关注的边界：取消/失败提示尚未统一保存为业务历史消息；酒店未知计价口径不能用于确定住宿总价；完整规划与预订闭环不能由查询或类名推断；异常退出后的业务占用不自动接管。各项原因与源码位置在对应专题说明。

`docs/development/` 在确有需求、决策或联调过程需要长期保留时再建立；本次不创建空目录、模板报告或未经核实的验收记录。
