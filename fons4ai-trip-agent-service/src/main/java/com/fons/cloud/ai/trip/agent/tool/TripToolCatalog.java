package com.fons.cloud.ai.trip.agent.tool;

import java.util.Set;

/**
 * Trip 已装配业务工具名的不可变快照；仅用于请求摘要分类，不接管工具执行。
 *
 * @author hongqy
 */
public record TripToolCatalog(Set<String> toolNames) {
    public TripToolCatalog {
        toolNames = Set.copyOf(toolNames);
    }
}
