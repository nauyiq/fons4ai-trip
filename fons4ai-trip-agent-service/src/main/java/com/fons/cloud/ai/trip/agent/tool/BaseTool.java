package com.fons.cloud.ai.trip.agent.tool;

import java.util.List;

/**
 * @author hongqy
 */
public interface BaseTool {

    /**
     * 获取工具列表
     * @return
     */
     default List<String> tools() {
         return List.of();
     }

}
