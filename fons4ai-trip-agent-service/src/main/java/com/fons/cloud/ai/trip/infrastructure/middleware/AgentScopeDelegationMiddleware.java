package com.fons.cloud.ai.trip.infrastructure.middleware;

import com.fons.cloud.ai.trip.infrastructure.agent.delegation.AgentScopeDelegationPolicy;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 将委派策略适配到AgentScope工具声明、运行上下文和模型输入。
 *
 * <p>通过标准middleware入口接入，不构建或继承Fons Agent。
 * 首次onAgent进入时，在核心执行前一次性装饰该原生Agent独立持有的委派工具；
 * 后续调用只检查初始化标记，不重复注册。并发首次调用通过Toolkit锁串行初始化。
 * 真实工具Schema同时用于模型声明和SDK参数校验，避免只修改提示而没有执行约束。
 * 可纠正的调用错误返回原生工具错误结果，继续由SDK发布事件、保存历史并让模型纠错；
 * 有效调用、权限审批、中断和INPUT_REQUIRED均交给原生链路处理。</p>
 *
 * <p>该组件不依赖Trip业务配置和子Agent类型。只适配Harness原生、无分组及预置参数的
 * 委派工具，不能直接用于任意MCP或业务工具的重注册。</p>
 *
 * @author hongqy
 */
public final class AgentScopeDelegationMiddleware implements MiddlewareBase {

    /** 同步模式不开放的后台任务工具；不包含todo计划看板或业务任务管理工具。 */
    private static final Set<String> BACKGROUND_TOOLS = Set.of(
            "task_list", "task_output", "task_cancel", "wait_async_results");

    /** 临时技术消息的保留名称，避免同一模型输入重复注入。 */
    private static final String POLICY_MESSAGE_NAME = "agentscope-delegation-policy";

    /** 构建期确定的不可变策略，所有请求只读取，不修改。 */
    private final AgentScopeDelegationPolicy policy;

    public AgentScopeDelegationMiddleware(AgentScopeDelegationPolicy policy) {
        if (policy == null) {
            throw SystemIntervalException.of("AgentScope委派策略不能为空");
        }
        this.policy = policy;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (policy.requireInitialTask() || policy.forceSync()) {
                if (!(agent instanceof ReActAgent nativeAgent)) {
                    throw SystemIntervalException.of("委派中间件需要AgentScope原生ReActAgent");
                }
                // Harness已完成构建及Toolkit复制，此处取得的是最终使用的原生Toolkit。
                // 先完成一次性工具初始化，再进入推理或执行，不能只收紧模型可见Schema。
                adaptToolkit(nativeAgent.getToolkit());
            }
            if (policy.forceSync()) {
                // 使用SDK原生运行开关，覆盖模型传入的timeout_seconds。
                // 到期中断子任务并返回timeout，而不是生成需要轮询的后台task_id。
                context.put(AgentSpawnTool.CTX_FORCE_SYNC, true);
                context.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, policy.syncTimeoutSeconds());
            }
            return next.apply(input);
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (!policy.requireInitialTask() && !policy.forceSync()) {
                return next.apply(input);
            }
            // 此处只过滤可见工具，参数声明已经在真实Toolkit中适配，不维护第二份Schema。
            var tools = input.tools().stream()
                    .filter(tool -> !policy.forceSync() || !BACKGROUND_TOOLS.contains(tool.getName()))
                    .toList();
            List<Msg> messages = new ArrayList<>(input.messages());
            messages.removeIf(message -> message.getRole() == MsgRole.SYSTEM
                    && POLICY_MESSAGE_NAME.equals(message.getName()));
            int insertIndex = 0;
            while (insertIndex < messages.size() && messages.get(insertIndex).getRole() == MsgRole.SYSTEM) {
                insertIndex++;
            }
            // SDK仍提供子Agent清单和agent_key等基础说明；本消息明确覆盖其中不适用的默认模式。
            // 只改变本次模型输入，不写AgentState、DistributedStore或业务聊天记录。
            messages.add(insertIndex, Msg.builder()
                    .name(POLICY_MESSAGE_NAME)
                    .role(MsgRole.SYSTEM)
                    .content(TextBlock.builder().text(policyInstructions()).build())
                    .metadata(Map.of(Msg.METADATA_SYNTHETIC, true))
                    .build());
            return next.apply(new ModelCallInput(messages, tools, input.options(), input.model()));
        });
    }

    private String policyInstructions() {
        StringBuilder instructions = new StringBuilder("【当前运行生效的委派工具约束】\n"
                + "下列技术约束覆盖SDK默认说明中的可选任务、异步执行等对应约定，不改变业务规则。\n");
        if (policy.requireInitialTask()) {
            instructions.append("首次agent_spawn必须携带完整、非空的task和必要上下文。"
                    + "不要先空创建再agent_send；agent_send用于向已有子会话发送新的后续任务。\n");
        }
        if (policy.forceSync()) {
            instructions.append("agent_spawn和agent_send强制同步等待，timeout_seconds不能切换到后台模式，"
                    + "实际等待上限为").append(policy.syncTimeoutSeconds()).append("秒。"
                    + "超时会中断子任务并返回timeout，不会返回可轮询的后台task_id。"
                    + "task_list、task_output、task_cancel、wait_async_results不可用。\n"
                    + "同步成功结果已经包含本次执行的回复，不只是创建回执；"
                    + "不要为获取同一个结果重复发送原任务或调用后台轮询工具。\n");
        }
        return instructions.append("参数校验或策略拒绝会作为工具错误结果返回。"
                + "请根据错误修正调用，不要原样重复无效参数，也不要把错误当成业务成功结果。\n"
                + "agent_send的agent_key使用agent_spawn返回的原始agent_key或创建时的label，优先使用原始agent_key，"
                + "不能使用agent_id、session_id或task_id代替。").toString();
    }

    /** 核心执行前一次性适配原生Toolkit；不在每轮模型调用中修改共享工具。 */
    private void adaptToolkit(Toolkit toolkit) {
        synchronized (toolkit) {
            // 原生工具包装本身就是初始化标记，无需额外持有Agent或维护运行状态表。
            if (toolkit.getTool("agent_spawn") instanceof DelegationTool adapted) {
                if (!policy.equals(adapted.delegationPolicy)) {
                    throw SystemIntervalException.of("同一Agent不能注册不同的委派策略");
                }
                return;
            }
            if (!(toolkit.getTool("agent_spawn") instanceof ToolBase)) {
                throw SystemIntervalException.of("委派适配需要Harness原生agent_spawn工具");
            }
            List<DelegationTool> adaptedTools = new ArrayList<>();
            for (String name : List.copyOf(toolkit.getToolNames())) {
                boolean spawn = "agent_spawn".equals(name);
                boolean send = "agent_send".equals(name);
                boolean denied = policy.forceSync() && BACKGROUND_TOOLS.contains(name);
                if (!spawn && !send && !denied) {
                    continue;
                }
                if (!(toolkit.getTool(name) instanceof ToolBase delegate)) {
                    throw SystemIntervalException.of("委派适配需要ToolBase工具：" + name);
                }
                Map<String, Object> parameters = spawn && policy.requireInitialTask()
                        ? requireTask(delegate.getParameters()) : delegate.getParameters();
                String description = delegate.getDescription();
                if (spawn) {
                    description = "创建专业子Agent并执行任务，返回agent_key、agent_id、session_id和执行结果。"
                            + (policy.requireInitialTask() ? "task必填且不能为空白，不允许先空创建再agent_send。" : "")
                            + (policy.forceSync() ? "强制同步等待，超时不转为后台任务。" : "支持SDK原生同步或异步模式。");
                } else if (send && policy.forceSync()) {
                    description = "向已有子Agent发送后续任务并同步等待结果。"
                            + "agent_key必须使用agent_spawn返回的原始agent_key或创建时的label，"
                            + "不能使用agent_id、session_id或task_id；超时不转为后台任务。";
                }
                adaptedTools.add(new DelegationTool(delegate, description, parameters, denied, policy));
            }
            // 所有包装器验证、构造成功后再注册，避免校验失败留下部分初始化状态。
            // spawn最后注册，作为全部工具适配成功的标记。
            for (DelegationTool tool : adaptedTools) {
                if (!"agent_spawn".equals(tool.getName())) {
                    toolkit.registerAgentTool(tool);
                }
            }
            adaptedTools.stream().filter(tool -> "agent_spawn".equals(tool.getName()))
                    .forEach(toolkit::registerAgentTool);
        }
    }

    /** 只复制需要修改的层级，不修改SDK工具持有的共享Schema。 */
    private Map<String, Object> requireTask(Map<String, Object> original) {
        Map<String, Object> parameters = new LinkedHashMap<>(original);
        List<Object> required = new ArrayList<>();
        if (parameters.get("required") instanceof List<?> names) {
            required.addAll(names);
        }
        if (!required.contains("task")) {
            required.add("task");
        }
        parameters.put("required", List.copyOf(required));
        Map<String, Object> properties = new LinkedHashMap<>();
        if (parameters.get("properties") instanceof Map<?, ?> originalProperties) {
            originalProperties.forEach((key, value) -> properties.put((String) key, value));
        }
        Map<String, Object> task = new LinkedHashMap<>();
        if (properties.get("task") instanceof Map<?, ?> originalTask) {
            originalTask.forEach((key, value) -> task.put((String) key, value));
        }
        task.put("type", "string");
        task.put("minLength", 1);
        task.put("pattern", "\\S");
        task.put("description", "必填：子Agent本次需要执行的完整任务及必要上下文，不能仅包含空白");
        properties.put("task", Collections.unmodifiableMap(task));
        parameters.put("properties", Collections.unmodifiableMap(properties));
        return Collections.unmodifiableMap(parameters);
    }

    /** 保留原生安全属性和权限检查，只装饰委派Schema及策略拒绝结果。 */
    private static final class DelegationTool extends ToolBase {

        /** 原生工具执行器；有效调用使用原始参数直接转交，保留事件和取消上下文。 */
        private final ToolBase delegate;

        /** 同步模式下该后台管理工具是否禁止执行。 */
        private final boolean denied;

        /** 该工具已安装的不可变策略，用于幂等初始化及配置冲突检查。 */
        private final AgentScopeDelegationPolicy delegationPolicy;

        private DelegationTool(ToolBase delegate, String description,
                Map<String, Object> parameters, boolean denied, AgentScopeDelegationPolicy delegationPolicy) {
            super(delegate.getName(), description, parameters, delegate.isReadOnly(),
                    delegate.isConcurrencySafe(), delegate.isMcp(), delegate.getMcpName(),
                    delegate.isExternalTool(), delegate.isStateInjected());
            this.delegate = delegate;
            this.denied = denied;
            this.delegationPolicy = delegationPolicy;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            if (denied) {
                // 防御历史调用或模型仍输出被隐藏的工具。返回工具错误，不终止整轮Agent。
                // SDK负责回填id/name、发布工具事件、保存工具结果及下一轮推理。
                return Mono.just(ToolResultBlock.error("当前委派策略为强制同步，该后台任务工具不可用。"
                        + "请使用已经返回的同步结果，不要轮询后台任务。"));
            }
            // task的实际Schema校验由SDK ToolExecutor完成，不再在onActing抛流异常。
            // 不捕获ToolSuspendException、不转换原生结果，保留审批及INPUT_REQUIRED语义。
            return delegate.callAsync(param);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
                PermissionContextState context) {
            return delegate.checkPermissions(toolInput, context);
        }

        @Override
        public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
            return delegate.matchRule(ruleContent, toolInput);
        }

        @Override
        public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
            return delegate.generateSuggestions(toolInput);
        }

        @Override
        public Boolean getStrict() {
            return delegate.getStrict();
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return delegate.getOutputSchema();
        }
    }
}
