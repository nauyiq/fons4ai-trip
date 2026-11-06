# 开发过程索引

过程规格用于评审与任务验收；当前已验证的长期知识仍从 [项目地图](../wiki/index.md) 阅读。

| 功能 | 规格与状态 |
| --- | --- |
| 业务能力与异常边界梳理 | [现状与待确认](features/20261103-capability-exception-baseline/现状与待确认.md)；基于当前工作树的静态调查，领域划分与异常迁移方案待讨论 |
| 差旅单行程规划与搜索边界 | [需求](features/20261029-order-itinerary-scope/需求说明书.md) · [设计](features/20261029-order-itinerary-scope/技术设计.md) · [任务](features/20261029-order-itinerary-scope/任务规划.md)；已确认每单仅最新已发布方案、候选逐条保留 30 分钟、规划单独建表，完整设计待评审，尚未实施 |
| 请求结果持久化与历史展示 | [需求](features/20261019-request-summary/需求说明书.md) · [设计](features/20261019-request-summary/技术设计.md) · [任务](features/20261019-request-summary/任务规划.md)；普通/取消摘要、双审批恢复统计及审批思考持久化核心路径已通过最小验收；其他未覆盖边界见任务 |
| News 检索质量与审批流程描述优化 | [需求](features/20261021-news-quality-and-approval-narration/需求说明书.md) · [设计](features/20261021-news-quality-and-approval-narration/技术设计.md) · [任务](features/20261021-news-quality-and-approval-narration/任务规划.md)；统一查询与提示词已实现、生产编译通过；综合5→5→展示2、交通5→1→展示0，同子会话两次审批恢复及整页刷新核心路径通过；其他AC边界见任务 |
| 原生记忆、偏好与工具许可技术预研 | [验证结论](research/20261022-native-memory-feasibility/验证结论.md)；23 项原生/H2 检查、真实 DeepSeek 提取矩阵及受控事实撤回验证已完成；复现默认写入、并发和旧策略缺口。原生复用路线具备进入 SDD 的依据，MySQL 多节点及完整业务集成仍待验收 |
| 差旅偏好 CRUD 与 Agent 接入 | [需求](features/20261022-native-memory-preferences-permissions/需求说明书.md) · [设计](features/20261022-native-memory-preferences-permissions/技术设计.md) · [任务](features/20261022-native-memory-preferences-permissions/任务规划.md)；2026-10-28 R2.3 已轻量收敛：页面和查询/修改工具共用 CRUD，业务默认优先于历史原生记忆；移除出处核验、确认时间仲裁及逐次推理注入。七类目录、四字段 JSON List 与独立版本列保留，兼容旧列表，无新 DDL；现有两列迁移后随应用正常接入，不设独立偏好开关。移除开关后的生产编译与 22 项本地 CRUD 复核通过，旧关闭用例仅作历史；真实页面/模型待用户重启验收。原生记忆与固定许可不扩展 |
