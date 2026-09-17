package com.fons.cloud.ai.trip.infrastructure.middleware;

import com.fons.cloud.ai.trip.agent.model.TripTimeContext;
import com.fons.cloud.ai.trip.infrastructure.config.TripTimeContextConfiguration;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Function;

/**
 * 为每轮请求提供可信日期，仅向本次模型输入添加时间消息，不修改持久化历史。
 * 根请求必须使用新的 RuntimeContext；子 Agent 沿用从父运行上下文复制的日期对象。
 * Middleware 不保存会话状态，同一轮的多次推理使用同一日期基准。
 * @author hongqy
 */
@Component
public class TripTimeContextMiddleware implements MiddlewareBase {

    private final Clock clock;

    public TripTimeContextMiddleware(
            @Qualifier(TripTimeContextConfiguration.TRIP_AGENT_CLOCK) Clock clock) {
        this.clock = clock;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            getOrCreate(context);
            return next.apply(input);
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            TripTimeContext timeContext = getOrCreate(context);
            String weekday = timeContext.currentDate().getDayOfWeek()
                    .getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE);
            String text = String.format("""
                            【本轮时间基准】
                            当前日期：%s
                            当前星期：%s
                            业务时区：%s
                            本轮新出现的相对日期以此为准；此前已确定的明确日期保持不变。""",
                    timeContext.currentDate(), weekday, timeContext.zoneId().getId());
            Msg timeMessage = Msg.builder()
                    .role(MsgRole.SYSTEM)
                    .content(TextBlock.builder().text(text).build())
                    .build();

            // 复制本次模型输入；不向 AgentState 写入消息，也不原地修改原消息列表。
            ArrayList<Msg> messages = new ArrayList<>(input.messages());
            int index = !messages.isEmpty() && messages.getFirst().getRole() == MsgRole.SYSTEM ? 1 : 0;
            messages.add(index, timeMessage);
            return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
        });
    }

    private TripTimeContext getOrCreate(RuntimeContext context) {
        TripTimeContext timeContext = context.get(TripTimeContext.class);
        if (timeContext == null) {
            timeContext = new TripTimeContext(LocalDate.now(clock), clock.getZone());
            context.put(TripTimeContext.class, timeContext);
        }
        return timeContext;
    }
}
