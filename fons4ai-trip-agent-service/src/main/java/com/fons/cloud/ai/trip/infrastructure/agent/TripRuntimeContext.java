package com.fons.cloud.ai.trip.infrastructure.agent;

import com.fons.cloud.ai.agent.core.AgentScopeHarnessAgent;
import io.agentscope.core.agent.RuntimeContext;

/**
 * Trip业务身份的原生上下文适配入口，独立于可观测性和SDK会话状态。
 *
 * @author hongqy
 */
public final class TripRuntimeContext {
    private TripRuntimeContext() {
    }

    /**
     * 读取框架从请求固化的根业务会话，供候选、规划及页面使用相同归属。
     * 不回退到子Agent的sessionId；缺失时由工具原有的身份校验拒绝执行。
     *
     * @param context 父子Agent或审批恢复的原生执行上下文
     * @return 根业务会话ID，未提供上下文或业务身份时为null
     */
    public static String conversationId(RuntimeContext context) {
        return context == null ? null
                : context.get(AgentScopeHarnessAgent.CONVERSATION_ID_ATTRIBUTE, String.class);
    }

}
