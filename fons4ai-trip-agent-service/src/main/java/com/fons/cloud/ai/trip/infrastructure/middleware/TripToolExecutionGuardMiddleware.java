package com.fons.cloud.ai.trip.infrastructure.middleware;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 对Trip业务工具的已知失败执行有限、可观察的运行约束。
 *
 * <p>仅装饰显式声明、返回Trip R协议的原生业务工具和todo_write，不接管MCP、文件、
 * 记忆或委派工具。不重试业务操作，也不根据工具结束事件猜测业务成功。
 * 原始业务结果保持不变，仅将success=false对齐为原生ERROR状态；拒绝的调用由SDK
 * 正常发布事件并保存工具结果，不使用流异常中断整轮Agent。</p>
 *
 * <p>失败记录仅存在于本次call的RuntimeContext，各业务Agent独立维护。
 * todo约束只防止存在已知未解决失败时清空清单或把全部任务标为完成，不能证明单项
 * 自由文本任务已经执行；任务状态和历史仍由原生AgentState持久化。</p>
 *
 * @author hongqy
 */
public final class TripToolExecutionGuardMiddleware implements MiddlewareBase {

    /** 临时模型说明的保留名称；不写入AgentState或业务聊天记录。 */
    private static final String GUARD_MESSAGE_NAME = "trip-tool-execution-guard";

    /** 由SDK维护的计划清单工具，与后台任务工具task_list无关。 */
    private static final String TODO_TOOL_NAME = "todo_write";

    /** 可以通过修正已确认参数继续执行的失败码。 */
    private static final Set<String> PARAMETER_ERROR_CODES = Set.of(
            TripAgentToolResultCode.INVALID_PARAM.getCode(),
            TripAgentToolResultCode.INVALID_DATE_RANGE.getCode());

    /** 只接受Trip当前明确声明的业务结果码，避免解释任意JSON或普通工具文本。 */
    private static final Set<String> BUSINESS_RESULT_CODES = Set.of(
            java.util.Arrays.stream(TripAgentToolResultCode.values())
                    .map(TripAgentToolResultCode::getCode).toArray(String[]::new));

    /** 需要适配R结果及限制无效调用的业务工具名称白名单。 */
    private final Set<String> businessTools;

    /** 同一工具在本次call内允许实际返回INTERNAL_ERROR的累计上限。 */
    private final int maxSameToolSystemFailures;

    /** 失败不阻止todo全量收口的可选工具，例如不影响规划的天气和资讯查询。 */
    private final Set<String> nonBlockingTodoTools;

    public TripToolExecutionGuardMiddleware(Set<String> businessTools, int maxSameToolSystemFailures) {
        this(businessTools, maxSameToolSystemFailures, Set.of());
    }

    public TripToolExecutionGuardMiddleware(Set<String> businessTools, int maxSameToolSystemFailures,
            Set<String> nonBlockingTodoTools) {
        if (businessTools == null || nonBlockingTodoTools == null
                || businessTools.stream().anyMatch(name -> name == null || name.isBlank())
                || nonBlockingTodoTools.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw SystemIntervalException.of("工具执行约束的工具名称不能为空");
        }
        if (maxSameToolSystemFailures < 1) {
            throw SystemIntervalException.of("同一工具系统失败上限必须大于0");
        }
        if (!businessTools.containsAll(nonBlockingTodoTools)) {
            throw SystemIntervalException.of("可选工具必须属于受控业务工具白名单");
        }
        this.businessTools = Set.copyOf(businessTools);
        this.maxSameToolSystemFailures = maxSameToolSystemFailures;
        this.nonBlockingTodoTools = Set.copyOf(nonBlockingTodoTools);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            if (!(agent instanceof ReActAgent nativeAgent) || context == null) {
                throw SystemIntervalException.of("工具执行约束需要原生ReActAgent及本次运行上下文");
            }
            adaptToolkit(nativeAgent.getToolkit());
            // 子Agent可能复制父上下文；必须重新创建，不能复用浅复制的可变失败记录。
            context.put(ExecutionLedger.class, new ExecutionLedger());
            return next.apply(input);
        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            ExecutionLedger ledger = context.get(ExecutionLedger.class);
            if (ledger == null) {
                return next.apply(input);
            }
            FailureSnapshot snapshot = ledger.snapshot();
            if (snapshot.failures().isEmpty() && snapshot.stoppedTools().isEmpty()) {
                return next.apply(input);
            }
            var tools = input.tools().stream()
                    .filter(tool -> !snapshot.stoppedTools().contains(tool.getName()))
                    .toList();
            List<Msg> messages = new ArrayList<>(input.messages());
            messages.removeIf(message -> message.getRole() == MsgRole.SYSTEM
                    && GUARD_MESSAGE_NAME.equals(message.getName()));
            int insertIndex = 0;
            while (insertIndex < messages.size() && messages.get(insertIndex).getRole() == MsgRole.SYSTEM) {
                insertIndex++;
            }
            messages.add(insertIndex, Msg.builder()
                    .name(GUARD_MESSAGE_NAME)
                    .role(MsgRole.SYSTEM)
                    .content(TextBlock.builder().text(failureInstructions(snapshot)).build())
                    .metadata(Map.of(Msg.METADATA_SYNTHETIC, true))
                    .build());
            return next.apply(new ModelCallInput(messages, tools, input.options(), input.model()));
        });
    }

    private String failureInstructions(FailureSnapshot snapshot) {
        return "【本次业务工具的已知执行事实】\n"
                + String.join("\n", snapshot.failures())
                + "\n已停止的工具：" + snapshot.stoppedTools() + "。"
                + "停止仅针对本次调用，修改无关参数不能恢复系统服务。参数错误可依据已确认事实修正，"
                + "不要原样重复已经失败的参数。\n"
                + "success=false或原生ERROR不是无候选，也不是业务成功；只在任务确实完成并验证后标completed。"
                + "存在未解决的必需业务工具失败时，不得清空清单或把全部任务标为completed。"
                + "可选天气、资讯等失败仍须如实说明，但不强制阻止其他可继续的业务。\n"
                + "本轮对话可以以真实失败、部分完成或需要用户补充结束，这不等于业务目标或清单全部完成。"
                + "上述记录仅是已知失败，不证明未列出的任务已经执行或成功。";
    }

    /** 只在执行入口初始化该Agent独立持有的Toolkit，不改变原生并行安全属性。 */
    private void adaptToolkit(Toolkit toolkit) {
        List<GuardedTool> adaptedTools = new ArrayList<>();
        for (String name : toolkit.getToolNames()) {
            if (!businessTools.contains(name) && !TODO_TOOL_NAME.equals(name)) {
                continue;
            }
            if (toolkit.getTool(name) instanceof GuardedTool existing) {
                if (!sameConfiguration(existing.guard)) {
                    throw SystemIntervalException.of("同一Agent不能注册不同的工具执行约束");
                }
                continue;
            }
            if (!(toolkit.getTool(name) instanceof ToolBase delegate)) {
                throw SystemIntervalException.of("工具执行约束需要原生ToolBase：" + name);
            }
            adaptedTools.add(new GuardedTool(delegate, this));
        }
        // 当前Trip工具无分组和预置参数；不把此重注册路径泛化到任意MCP或第三方工具。
        adaptedTools.forEach(toolkit::registerAgentTool);
    }

    private boolean sameConfiguration(TripToolExecutionGuardMiddleware other) {
        return businessTools.equals(other.businessTools)
                && maxSameToolSystemFailures == other.maxSameToolSystemFailures
                && nonBlockingTodoTools.equals(other.nonBlockingTodoTools);
    }

    /** R必须是单个完整JSON对象，并包含真实布尔success、已声明code及文本message。 */
    private BusinessOutcome readBusinessOutcome(ToolResultBlock result) {
        if (result.getOutput().size() != 1 || !(result.getOutput().getFirst() instanceof TextBlock text)) {
            return null;
        }
        String body = text.getText() == null ? "" : text.getText().strip();
        if (!body.startsWith("{") || !body.endsWith("}")) {
            return null;
        }
        try {
            JSONObject value = JSON.parseObject(body);
            if (!(value.get("success") instanceof Boolean success)
                    || !(value.get("code") instanceof String code) || !BUSINESS_RESULT_CODES.contains(code)
                    || !value.containsKey("message")
                    || (value.get("message") != null && !(value.get("message") instanceof String))) {
                return null;
            }
            return new BusinessOutcome(success, code, (String) value.get("message"));
        } catch (RuntimeException ignored) {
            // 未识别的协议不能猜成成功或失败；保留原生结果供上层处理。
            return null;
        }
    }

    /** 排序对象字段、去掉字符串外层空白，数组顺序及参数值类型保持不变。 */
    private String fingerprint(Map<String, Object> input) {
        return JSON.toJSONString(canonicalValue(input));
    }

    private Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> sorted = new TreeMap<>();
            values.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
            return sorted;
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(this::canonicalValue).toList();
        }
        if (value instanceof String text) {
            return text.strip();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        return value;
    }

    /** 只识别全量成功/清空的收口，不推断自由文本任务与业务工具的依赖关系。 */
    private boolean closesTodoList(Map<String, Object> input) {
        Object value = input.get("todos");
        if (value == null) {
            return true;
        }
        if (!(value instanceof Collection<?> todos)) {
            return false;
        }
        return todos.isEmpty() || todos.stream().allMatch(item -> item instanceof Map<?, ?> todo
                && todo.get("status") instanceof String status && "completed".equals(status.strip()));
    }

    /** 只装饰真实工具调用，原生权限、中断、入参注入及结果持久化路径继续使用原实现。 */
    private static final class GuardedTool extends ToolBase {

        /** 保留所有原生工具行为的实际执行器。 */
        private final ToolBase delegate;

        /** 不可变的工具约束配置；运行态只从本次RuntimeContext读取。 */
        private final TripToolExecutionGuardMiddleware guard;

        private GuardedTool(ToolBase delegate, TripToolExecutionGuardMiddleware guard) {
            super(delegate.getName(), delegate.getDescription(), delegate.getParameters(), delegate.isReadOnly(),
                    delegate.isConcurrencySafe(), delegate.isMcp(), delegate.getMcpName(),
                    delegate.isExternalTool(), delegate.isStateInjected());
            this.delegate = delegate;
            this.guard = guard;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.defer(() -> {
                RuntimeContext context = param.getRuntimeContext();
                ExecutionLedger ledger = context == null ? null : context.get(ExecutionLedger.class);
                if (ledger == null) {
                    return Mono.just(ToolResultBlock.error("工具执行约束缺少本次运行上下文，未执行该工具。"));
                }
                if (TODO_TOOL_NAME.equals(getName())) {
                    if (guard.closesTodoList(param.getInput()) && ledger.hasBlockingFailures()) {
                        // 在调用原生todo_write之前拒绝，因此原tasksContext完全保留。
                        return Mono.just(ToolResultBlock.error("当前仍有未解决的必需业务工具失败，"
                                + "不能清空任务清单或将全部任务标为completed。请保留未完成项，"
                                + "按真实失败或部分完成结束本轮，而不是制造全部成功状态。"));
                    }
                    return delegate.callAsync(param);
                }
                CallKey key = new CallKey(getName(), guard.fingerprint(param.getInput()));
                String rejection = ledger.rejection(key);
                if (rejection != null) {
                    return Mono.just(ToolResultBlock.error(rejection));
                }
                // 不捕获异常，不自动重试，不转换暂停；由原生SDK执行这些职责。
                return delegate.callAsync(param).map(result -> {
                    if (result.isSuspended()) {
                        return result;
                    }
                    BusinessOutcome outcome = guard.readBusinessOutcome(result);
                    if (outcome == null) {
                        return result;
                    }
                    if (!outcome.success()) {
                        ledger.failed(key, outcome);
                        // JSON正文及全部metadata均保留；SDK事件和会话历史不再误报SUCCESS。
                        return result.withState(ToolResultState.ERROR);
                    }
                    if (result.getState() != ToolResultState.ERROR && result.getState() != ToolResultState.DENIED) {
                        ledger.succeeded(key);
                    }
                    return result;
                });
            });
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

    /** 单次call内的失败记录；锁只保护记录，不串行化真实工具执行。 */
    private final class ExecutionLedger {

        /** 同工具累计系统失败数量；成功或改参不重置本次停止预算。 */
        private final Map<String, Integer> systemFailures = new LinkedHashMap<>();

        /** 已发生的无效参数指纹，避免原样重发；正常改参仍能继续。 */
        private final Set<CallKey> invalidParameters = new LinkedHashSet<>();

        /** 未被对应成功纠正的真实R失败，按工具及规范化参数分别保存。 */
        private final Map<CallKey, BusinessOutcome> unresolvedFailures = new LinkedHashMap<>();

        private synchronized String rejection(CallKey key) {
            if (systemFailures.getOrDefault(key.toolName(), 0) >= maxSameToolSystemFailures) {
                return "工具" + key.toolName() + "在本次请求中已达到系统失败上限，不再实际执行。"
                        + "修改无关参数不能恢复服务，请如实说明未完成，不把系统失败当作无候选或业务成功。";
            }
            if (invalidParameters.contains(key)) {
                return "该工具的相同参数已校验失败，未重复执行。"
                        + "请依据已确认事实修正参数，不能原样重发；需要用户决定时再请求必要补充。";
            }
            return null;
        }

        private synchronized void failed(CallKey key, BusinessOutcome failure) {
            unresolvedFailures.put(key, failure);
            if (TripAgentToolResultCode.INTERNAL_ERROR.getCode().equals(failure.code())) {
                systemFailures.merge(key.toolName(), 1, Integer::sum);
            }
            if (PARAMETER_ERROR_CODES.contains(failure.code())) {
                invalidParameters.add(key);
            }
        }

        private synchronized void succeeded(CallKey key) {
            unresolvedFailures.remove(key);
            // 参数纠正必然可能改变指纹；成功只恢复该工具之前的参数校验失败。
            // 其他对象/日期的系统或业务失败不能被一次无关成功清除。
            unresolvedFailures.entrySet().removeIf(entry -> entry.getKey().toolName().equals(key.toolName())
                    && PARAMETER_ERROR_CODES.contains(entry.getValue().code()));
        }

        private synchronized boolean hasBlockingFailures() {
            return unresolvedFailures.keySet().stream()
                    .anyMatch(key -> !nonBlockingTodoTools.contains(key.toolName()));
        }

        private synchronized FailureSnapshot snapshot() {
            Set<String> stopped = new LinkedHashSet<>();
            systemFailures.forEach((tool, count) -> {
                if (count >= maxSameToolSystemFailures) {
                    stopped.add(tool);
                }
            });
            List<String> failures = new ArrayList<>();
            unresolvedFailures.forEach((key, outcome) -> {
                String message = outcome.message() == null ? "" : outcome.message().replace('\n', ' ');
                failures.add(key.toolName() + ": " + outcome.code() + "，"
                        + message.substring(0, Math.min(message.length(), 180))
                        + (nonBlockingTodoTools.contains(key.toolName()) ? "（可选信息，不阻止其他业务）" : ""));
            });
            return new FailureSnapshot(Set.copyOf(stopped), List.copyOf(failures));
        }
    }

    /**
     * @param toolName 实际工具名称，不根据自然语言猜测供应商或跨工具依赖。
     * @param fingerprint 规范化入参，用于识别同一无效调用，不写入提示或日志。
     */
    private record CallKey(String toolName, String fingerprint) {}

    /**
     * @param success 原业务R结果的真实布尔值。
     * @param code 原业务R结果的已声明响应码。
     * @param message 原业务错误说明，不用于推断额外业务事实。
     */
    private record BusinessOutcome(boolean success, String code, String message) {}

    /**
     * @param stoppedTools 本次运行后续不再实际执行的工具。
     * @param failures 仅包含已知未解决失败的模型提醒快照。
     */
    private record FailureSnapshot(Set<String> stoppedTools, List<String> failures) {}
}
