package com.fons.cloud.ai.trip.infrastructure.middleware;

import com.fons.cloud.ai.trip.infrastructure.config.properties.MasterAgentConfigProperties;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Master 专用的串行委派约束，不注册到子 Agent，也不改变工具审批策略。
 * SDK 默认允许空创建、后台执行和超时转后台；Trip 的同步工作流不使用这些路径。
 * 工具声明仅在本次模型输入中收紧，不修改共享 Toolkit 或持久化会话历史。
 *
 * @author hongqy
 */
@Component
@RequiredArgsConstructor
public class MasterDispatchMiddleware implements MiddlewareBase {

    /** SDK 子任务同步等待的最大秒数；与 SDK 上限一致，避免默认 30 秒转后台。 */
    private static final int MAX_SYNC_TIMEOUT_SECONDS = 600;

    /** 后台任务工具不属于 Trip 串行调度；保留 agent_list、记忆工具和计划看板工具。 */
    private static final Set<String> BACKGROUND_TOOLS = Set.of(
            "task_list", "task_output", "task_cancel", "wait_async_results");

    /** 复用现有工具超时配置，不额外引入一套委派超时配置。 */
    private final MasterAgentConfigProperties properties;

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            // 使用 SDK 的运行时策略，而不是只依赖 LLM 遵守 timeout_seconds 参数约定。
            // 到期由 SDK 中断子任务并返回 timeout，不提升为需要轮询的后台任务。
            context.put(AgentSpawnTool.CTX_FORCE_SYNC, true);
            context.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS,
                    Math.min(properties.getToolTimeoutSeconds(), MAX_SYNC_TIMEOUT_SECONDS));
            context.put(AgentSpawnTool.CTX_EXPOSE_TO_USER, false);
            return next.apply(input);
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            // 在最终模型调用入口过滤，避免后续 Harness 中间件重新注入后台工具声明。
            List<ToolSchema> tools = input.tools().stream()
                    .filter(tool -> !BACKGROUND_TOOLS.contains(tool.getName()))
                    .map(tool -> "agent_spawn".equals(tool.getName()) ? requireSpawnTask(tool) : tool)
                    .toList();
            return next.apply(new ModelCallInput(input.messages(), tools, input.options(), input.model()));
        });
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            // 历史消息或模型仍可能产生不符合新声明的调用；在创建子会话前明确拒绝，
            // 不把用户请求自动拼入 task，也不静默创建空实例再追加 agent_send。
            for (var call : input.toolCalls()) {
                if (BACKGROUND_TOOLS.contains(call.getName())) {
                    return Flux.error(SystemIntervalException.of("Master串行编排不支持后台任务工具"));
                }
                if ("agent_spawn".equals(call.getName())) {
                    Object task = call.getInput() == null ? null : call.getInput().get("task");
                    if (!(task instanceof String text) || text.isBlank()) {
                        return Flux.error(SystemIntervalException.of("首次委派子Agent必须提供非空task"));
                    }
                }
            }
            return next.apply(input);
        });
    }

    private ToolSchema requireSpawnTask(ToolSchema tool) {
        // 只复制需要调整的 schema 层级；不能修改 SDK 共享声明中的 required/properties。
        Map<String, Object> parameters = new LinkedHashMap<>(tool.getParameters());
        List<String> required = new ArrayList<>();
        if (parameters.get("required") instanceof List<?> originalRequired) {
            for (Object name : originalRequired) {
                required.add((String) name);
            }
        }
        if (!required.contains("task")) {
            required.add("task");
        }
        parameters.put("required", required);

        Map<String, Object> parameterProperties = new LinkedHashMap<>();
        if (parameters.get("properties") instanceof Map<?, ?> originalProperties) {
            originalProperties.forEach((name, value) -> parameterProperties.put((String) name, value));
        }
        Map<String, Object> task = new LinkedHashMap<>();
        if (parameterProperties.get("task") instanceof Map<?, ?> originalTask) {
            originalTask.forEach((name, value) -> task.put((String) name, value));
        }
        task.put("type", "string");
        task.put("minLength", 1);
        task.put("description", "必填：本次需要子Agent立即执行的完整任务及必要上下文，不允许空创建");
        parameterProperties.put("task", task);
        parameters.put("properties", parameterProperties);

        return ToolSchema.builder()
                .name(tool.getName())
                .description("创建专业子Agent并立即同步执行task，返回agent_key和执行结果。"
                        + "不得先空创建再agent_send；续接已有子会话时使用agent_send。"
                        + "Trip不使用后台任务或expose_to_user。")
                .parameters(parameters)
                .outputSchema(tool.getOutputSchema())
                .strict(tool.getStrict())
                .build();
    }
}
