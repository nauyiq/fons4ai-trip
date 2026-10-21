# 一次对话怎样执行、展示与结束

[项目地图](index.md) · [协作与 HITL](agents-and-hitl.md) · [数据与身份](data-and-identities.md)

会话 HTTP 接口负责可信用户入口，应用服务持久化请求并组织执行权，Pipeline 组合分析步骤和 Master，前端消费 SSE 并保存页面状态。业务聊天记录、SDK 会话和执行 Trace 分别承担不同职责。

## 正常请求的主要时序

```mermaid
sequenceDiagram
    participant UI as ChatWindow / chat API
    participant C as ConversationController
    participant A as ConversationApplicationService
    participant DB as 业务数据库
    participant M as ReactiveRunManager
    participant P as Pipeline / Master
    UI->>C: POST stream
    C->>A: 注入登录用户，处理请求
    A->>DB: 保存会话、请求轨迹、用户消息
    A->>P: 创建未启动的 Pipeline Run
    A->>M: 注册 taskId 与 Run，绑定取消和退出确认
    A->>DB: 条件占用 active_run_id
    A->>P: 订阅事件，惰性启动执行
    P-->>UI: 过程事件经适配器转发
    P->>A: 结果处理器保存业务输出与轨迹
    P-->>A: completion 与 termination 分别确认
    A->>M: 确认退出后释放本次租约
    A->>DB: 清理匹配的执行占用，交接审批状态
    A-->>UI: run_ended 权威分段结果
```

图是正常执行的主路径，省略注册失败和恢复票证准备。实际流式序列由 `ChatStreamOutputAdapter` 组织；注册本身不订阅结果，避免比前端事件订阅更早启动任务。具体分析分支见 [Agent 编排](agents-and-hitl.md)。

`ConversationApplicationService.streamRun()` 独立订阅一个缓存的退出处理链：先等待 `termination()`，再读可回放的 `completion()`，完成状态、租约和业务占用交接。这个观察链不依赖 SSE 连接继续存在，避免断连后无人收尾。

## 页面以什么判断状态

| 信号 | 用途与边界 |
| --- | --- |
| `conversation_ready` | 返回可信业务会话和 Pipeline Run ID，供前端把临时会话对应到服务端 |
| 过程事件 | 展示思考、内容、任务过程等；内部 `type=hitl` 不直接开放正式审批 |
| `itinerary_page_ready` | 通知存在行程页面；页面读取仍需服务端重新校验归属与最新审核 |
| `run_ended` | 含 `pipelineRunId`、可用时的 `agentRunId`、`state`、`nextAction`、错误或人工交互信息；表示当前分段交接结果 |
| 执行查询接口 | 刷新/重新进入会话时读取服务端占用；不能仅用前端 fetch 是否结束判断空闲 |

审批卡来自已持久化并就绪的 `approvalMessages`；`approvalReady=true` 不由模型正文推断。`state=completed` 表示 Agent 当前分段结束，不代表业务审批通过、规划审核通过或已经完成预订。

失败或取消结束时，`terminalMessages` 返回已入库的系统提示，沿用历史消息的 ID、创建时间和内容。前端按真实 ID 合并；`extra.runState`（`failed` / `cancelled`）与 `extra.pipelineRunId` 用于核对本次终态，不用旧记录覆盖后续请求的错误。收到本轮持久化提示后不再重复显示错误横幅。

`INPUT_REQUIRED` 以普通助手消息保存问题与业务数据，同时在 `extra.interactionKind` 保存可信交互类型。追问只在消息正文展示一份，用户从底部原聊天输入框补充，前端不再为这类交互额外生成输入表单。实时使用 `run_ended` 设置待补充状态；历史加载、手动刷新、断线后补读及会话切换时，只从最后一条带标记的助手消息恢复相应输入提示。用户提交回答或收到后续终态消息后关闭旧的待补充状态。

回答原样通过普通聊天提交，由原子会话结合当前日期、业务时区与历史解析“明天出发、大后天回来”等自然语言。标准日期格式只用于内部工具参数，不要求用户按模板填写；仅信息缺失、实质歧义或日期顺序冲突时追问。前端不新增日期解析器或格式校验。旧消息没有标记时保留正文与普通聊天入口，不猜测历史待办，也不回写旧数据。独立的旧 `user_interaction` 事件与工具审批仍使用各自既有入口；`INPUT_REQUIRED` 不创建工具审批票证。

2026-10-21 已通过新消息的审批恢复、交互标记入库、整页刷新后的统一输入框和自然语言提交检查。相对日期首次续接在前置 GLM 改写空正文处失败；19:07 重启后同一补充重发已通过分析，在待审批的记忆参数中正确记录 `2026-10-22` 出发、`2026-10-24` 返回，记忆保存后的最终回复待用户审批。对应 Run 与证据范围见 [模型分场景验收](framework-integration.md#模型如何按协议装配)。

## 停止不是立刻释放

```mermaid
flowchart LR
    Request[停止请求] --> Accept[取消已受理]
    Accept --> Drain[停止新执行并等待受管工作退出]
    Drain --> Term[termination 正常完成]
    Term --> Release[释放本次运行租约]
    Release --> Persist[同事务保存取消提示并清理匹配的数据库占用]
    Persist --> End[发送最终 run_ended 与持久化消息]
```

`POST /api/conversation/interrupt/{conversationId}` 从登录用户定位会话，再对当前 Pipeline Run 发起取消。前端应在停止收尾期间保持占用。`GET /{conversationId}/execution` 可能返回 `IDLE`、`RUNNING`、`STOPPING` 或 `UNKNOWN`；数据库仍记录执行而 Redis 不可确认时保留占用，不能自动清空或抢占。

当前服务的 SSE 取消路径会调用 `run.cancel()`；这意味着客户端断连可能影响执行，不能假定关页面后任务一定继续。原生调用是否已真实退出，仍由框架和 SDK 生命周期确认，见 [框架接入](framework-integration.md)。

## 标题、历史和删除

- 新会话在后端从首次有效文本生成标题：规范化空白，最多 24 个 Unicode 码点；无有效文本使用“新会话”。标题随 `ChatConversation` 一起入库，当前没有首次 LLM 自动命名机制。
- 历史正文从业务 `chat_message` 读取；分析与执行摘要按业务 `runId` 批量关联 `chat_request_trace`。SDK 的记忆、transcript 或中断回复不会自动变为前端聊天历史。
- 删除首先校验登录归属和空闲状态，事务内以 `active_run_id IS NULL` 条件删除会话并删除对应业务轨迹/消息。会话使用 MyBatis Plus 逻辑删除；不据此推断已清理全部 SDK、Redis、OSS 或 Langfuse 数据。

取消提示在实际退出后的收尾链保存：Pipeline 被取消而未产生结果，以及 Master 返回 `CANCELLED`，都进入 `finalizeUnsuccessfulRun()`，写入一条 `role=system`、内容为“本次执行已取消。”的业务消息。它与清理匹配的数据库占用同事务提交，保存失败时回滚占用清理，不能宣告取消已完成。收尾链由后台独立观察并缓存，SSE 断开不会放弃落库，事件发布和后台观察也不会各写一条。

这里保存的是取消状态提示；SDK 的内部中断文案和未完成的流式正文不会因此自动复制到业务历史。停止受理到实际退出之间仍保持 `STOPPING`；没有可靠来源时不区分“用户点击停止”与“连接断开触发取消”。

2026-10-21 补充系统失败收尾：Pipeline 异常而未产生 Master 结果，以及 Master 返回 `FAILED`，复用同一收尾事务保存失败消息。对外错误码使用 `TA100025`，提示为“系统处理异常，本次请求执行失败，请稍后重试。”；原始异常留在日志，不直接写入正文或回显供应商错误。页面请求摘要将 `failed` 明确显示为“执行失败”，旧记录即使缺少摘要也能显示失败状态。不新增表字段，也不补写历史缺失的消息；独立的超时、拒绝等结构化状态暂沿用既有行为。

本轮后端生产编译、前端 TypeScript 检查与构建通过，并在已有失败请求上核对了“执行失败”显示。19:07 重启后的最小请求通过分析并进入 `memory_save` 审批，没有复现系统失败；新失败提示入库、实时去重与整页刷新仍未实测，不能据此宣称失败路径已通过。

2026-10-18：后端生产编译、前端 TypeScript 检查与构建通过。重启后完成一条普通请求的最小手工验证：在问题改写阶段点击停止，页面先显示“正在停止”，随后显示取消提示；整页刷新后提示仍保留且只出现一次，输入框恢复可用。MySQL MCP 只读确认用户消息与系统取消消息属于同一业务 Run，且 `active_run_id` 已清空。会话为 `TRC1792335241603879d5d`，业务 Run 为 `TRT1792335241730879d5e`，Pipeline 为 `af7b8b91115341aba571bd3348cd5c93`。

本次未新增或执行单元测试，也未验证 Master 返回 `CANCELLED`、审批恢复中取消、断连后后台落库、保存失败回滚或多节点场景。截图和摘要保存在本地 `target/manual/cancellation-history-20261018/`，属于可清理的环境产物。旧会话缺失的取消记录不自动补写。

## 请求摘要与模型思考

当前工作树已接入两表读取与保存：正文及 Master 实际思考在 `chat_message`；问题改写、意图结果在 `chat_request_trace.analysis_content`；执行事实在 `execution_summary`，`used_tools` 只投影实际装配且已执行的业务工具名。框架内部工具仍保留在明细，待审批与业务防重短路不计作实际业务执行。

### 两表保存与历史读取

```mermaid
sequenceDiagram
    participant E as Pipeline 与现有 Run 上下文
    participant A as 会话写入与读取服务
    participant M as chat_message
    participant T as chat_request_trace
    participant UI as 客户端
    E-->>A: 分段 PipelineResult，含分析及 Master 结果
    Note over M,T: 同一业务数据库，沿用结果保存事务
    A->>M: 保存正文或审批消息，一份实际 Master 思考
    A->>T: 保存分析结果、业务工具及本段用量摘要
    E-->>A: termination 确认实际退出
    A->>E: 读取最终已知执行摘要
    A->>T: 补齐本 Run 统计快照，不累加旧值
    Note over A,T: 同事务补齐并清占用，等待审批时开放批次
    A-->>UI: run_ended，触发历史同步
    UI->>A: 刷新后 GET messages
    A->>A: 校验登录用户与会话归属
    A->>M: 按会话分页查询消息
    M-->>A: 消息、thinking 与各自 runId
    A->>T: 按同一会话与本页去重 runId 批量查询
    T-->>A: RequestTraceInfo
    A-->>UI: 消息及关联 requestTrace
    UI->>UI: 按 messageId 合并，每个已加载 Run 展示一份摘要
```

图覆盖正常完成和审批等待的主要路径，参与者是执行契约、业务服务与表，不限定某个 SDK。写入入口为 [ConversationApplicationService](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/conversation/ConversationApplicationService.java) 的 `handleChatResult`、`saveFinalExecutionSummary`；读取入口为 [ConversationReadApplicationService](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/conversation/ConversationReadApplicationService.java) 的分页查询。取消或失败没有正常 PipelineResult 时，沿已有退出收尾保存已知摘要；取消提示与占用释放的时序见上文，不要求持久化未完成模型文本。

分析/工具/用量字段的完整口径见 [数据分工](data-and-identities.md#消息与请求摘要字段)，框架如何形成摘要见 [执行摘要接入](framework-integration.md#执行摘要如何接入业务)。历史读取不依赖浏览器过程缓存，也不从 Langfuse 查询业务结果；缺失或损坏的旧摘要安全退化，不把未知用量显示成零。

### 思考展示与验证边界

2026-10-20 的只读双任务已确认分析、Master 思考、父子模型用量及业务工具分类落库；模型阶段取消也保存了未知用量和一条刷新可读的取消提示。重启后已确认历史摘要解析、工具终态修复生效；双审批一次提交后，新 Run 单独统计新增查询与模型用量，并通过 originRunId 关联旧分段。刷新后两段摘要和已处理卡片保留。审批等待结果遗漏分段内容的修复也已通过新请求复验：两张审批消息仅第一张携带496字符 Master 思考，批次上方仅展示一次，刷新后与流式原文逐字一致。上述是核心路径的最小验收，未覆盖边界和具体 Run/截图集中见 [任务规划](../development/features/20261019-request-summary/任务规划.md)。

同 Run 的首条 TEXT Agent/AgentHitl 消息承载一份 Master 思考，`extra.thinkingAgentName` 明确来源；审批批次上方只展示一次。分析过程提示和刷新后的分析结果来自各自入口，不混成模型思考。取消仍保存既有状态提示和已知请求摘要，本阶段不补存未完成的模型正文/思考。

`used_token` 是本 Run 可取得的输入与输出 Token 小计，缓存输入不重复相加；完整性以 JSON 中的 `usageComplete` 判断。SDK 原生压缩/记忆维护可能绕过模型事件，相应节点存在这些中间件时保守标为不完整。未知不能解释为零，关闭观测不改变摘要链路。详细规则与验收范围见 [本次设计](../development/features/20261019-request-summary/技术设计.md)。

## 接口与源码阅读入口

API 基路径为 `/api/conversation`：`POST /stream`、`POST /replay`、`POST /interrupt/{conversationId}`、`GET /{conversationId}/execution`、`GET /list`、`GET /{conversationId}/messages`、`DELETE /{conversationId}`。请求与返回以当前 DTO 和 Controller 为准，审批细节在下一专题维护。

1. [ConversationController](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/controller/ConversationController.java)：鉴权身份和接口。
2. [ConversationApplicationService](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/conversation/ConversationApplicationService.java)：`streamRun`、`handleChatResult`、`finalizeRunResult`、`interrupt`、`delete`。
3. [ChatStreamOutputAdapter](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/application/conversation/ChatStreamOutputAdapter.java)：事件过滤、执行权交接与权威终态。
4. [ChatConversation](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/domain/entity/ChatConversation.java)、[会话领域服务](../../fons4ai-trip-agent-service/src/main/java/com/fons/cloud/ai/trip/domain/service/impl/ChatConversationDomainServiceImpl.java)：标题、逻辑删除和条件更新。
5. [前端 chat API](../../fons4ai-trip-agent-ui/src/api/chat.ts)、[ChatWindow](../../fons4ai-trip-agent-ui/src/components/ChatWindow.tsx)、[chatStore](../../fons4ai-trip-agent-ui/src/store/chatStore.ts)：SSE 解析与页面状态。

已有对话和 SSE 测试可从 [测试目录](../../fons4ai-trip-agent-service/src/test/java/com/fons/cloud/ai/trip/application/conversation/) 阅读。本轮未执行这些测试；取消提示的手工验证范围见上文。
