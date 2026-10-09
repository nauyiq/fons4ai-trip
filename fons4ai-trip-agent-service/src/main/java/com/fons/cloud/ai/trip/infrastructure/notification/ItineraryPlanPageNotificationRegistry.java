package com.fons.cloud.ai.trip.infrastructure.notification;

import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanPageReady;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 保存当前会话运行中待发送的页面通知。聊天流结束或取消后立即移除，
 * 页面本身仍由OSS和页面读取接口提供，不依赖该临时通知状态。
 *
 * @author hongqy
 */
@Component
public class ItineraryPlanPageNotificationRegistry {

    private final ConcurrentMap<CandidateOwner, ItineraryPlanPageReady> pending = new ConcurrentHashMap<>();

    public void publish(CandidateOwner owner, ItineraryPlanPageReady page) {
        pending.put(owner, page);
    }

    public ItineraryPlanPageReady consume(CandidateOwner owner) {
        return pending.remove(owner);
    }

    public void discard(CandidateOwner owner) {
        pending.remove(owner);
    }
}
