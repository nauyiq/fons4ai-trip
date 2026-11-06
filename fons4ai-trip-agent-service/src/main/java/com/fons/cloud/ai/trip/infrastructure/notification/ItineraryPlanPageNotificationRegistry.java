package com.fons.cloud.ai.trip.infrastructure.notification;

import com.fons.cloud.ai.trip.common.response.ItineraryPlanPageReady;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按管道运行 ID 保存待发送的页面通知。聊天流结束或取消后立即移除，
 * 页面本身仍由OSS和页面读取接口提供，不依赖该临时通知状态。
 *
 * @author hongqy
 */
@Component
public class ItineraryPlanPageNotificationRegistry {

    /** 仅为仍有聊天流的管道运行保留通知槽。 */
    private final ConcurrentMap<String, Map<String, ItineraryPlanPageReady>> pending = new ConcurrentHashMap<>();
    /** 工具取得的是 Agent Run ID，必须先映射到实际的管道 Run ID。 */
    private final ConcurrentMap<String, String> pipelineByAgentRun = new ConcurrentHashMap<>();

    public void register(String pipelineRunId) {
        pending.putIfAbsent(pipelineRunId, new LinkedHashMap<>());
    }

    public void bindAgentRun(String agentRunId, String pipelineRunId) {
        pipelineByAgentRun.putIfAbsent(agentRunId, pipelineRunId);
    }

    public void unbindAgentRun(String agentRunId, String pipelineRunId) {
        pipelineByAgentRun.remove(agentRunId, pipelineRunId);
    }

    /** 提交成功后发送；聊天流已关闭时忽略迟到通知，避免污染其他运行。 */
    public void publishForAgentRun(String agentRunId, ItineraryPlanPageReady page) {
        if (agentRunId == null || page == null || page.orderId() == null) {
            return;
        }
        String pipelineRunId = pipelineByAgentRun.get(agentRunId);
        if (pipelineRunId != null) {
            // 同一运行可发布多张差旅单；同一单只通知最后一次成功发布。
            pending.computeIfPresent(pipelineRunId, (key, pages) -> {
                pages.put(page.orderId(), page);
                return pages;
            });
        }
    }

    public List<ItineraryPlanPageReady> consume(String pipelineRunId) {
        Map<String, ItineraryPlanPageReady> result = pending.remove(pipelineRunId);
        return result == null ? List.of() : List.copyOf(result.values());
    }

    public void discard(String pipelineRunId) {
        pending.remove(pipelineRunId);
    }
}
