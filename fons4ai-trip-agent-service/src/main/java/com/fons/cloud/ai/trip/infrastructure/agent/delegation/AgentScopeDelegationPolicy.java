package com.fons.cloud.ai.trip.infrastructure.agent.delegation;

import com.fons.cloud.common.base.exception.SystemIntervalException;

/**
 * AgentScope委派工具的运行约束，不承载路由、业务规则或失败降级策略。
 *
 * <p>策略在Agent构建时确定，模型声明、技术说明和实际执行使用同一份策略。
 * 关闭约束时保留SDK对应的原生行为；不额外实现子Agent调度或工具重试。</p>
 *
 * @param requireInitialTask 首次agent_spawn是否必须携带非空任务，防止空创建后再发送任务
 * @param forceSync 是否强制agent_spawn/agent_send同步等待，不允许超时提升为后台任务
 * @param syncTimeoutSeconds 强制同步时的等待上限，单位秒；与普通工具执行超时独立
 * @param blockPendingApproval 是否阻止向等待审批的子会话发送普通任务，以及新建同类型会话绕过审批
 * @author hongqy
 */
public record AgentScopeDelegationPolicy(
        boolean requireInitialTask,
        boolean forceSync,
        int syncTimeoutSeconds,
        boolean blockPendingApproval) {

    /** SDK强制同步等待的最大秒数；超过该值SDK会截断，因此构建时明确校验。 */
    private static final int MAX_SYNC_TIMEOUT_SECONDS = 600;

    /** 保留原三参数构造入口；默认开启子Agent等待审批保护。 */
    public AgentScopeDelegationPolicy(boolean requireInitialTask, boolean forceSync, int syncTimeoutSeconds) {
        this(requireInitialTask, forceSync, syncTimeoutSeconds, true);
    }

    public AgentScopeDelegationPolicy {
        if (syncTimeoutSeconds < 1 || syncTimeoutSeconds > MAX_SYNC_TIMEOUT_SECONDS) {
            throw SystemIntervalException.of("委派同步等待时间必须为1到600秒");
        }
    }
}
