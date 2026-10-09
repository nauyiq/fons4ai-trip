package com.fons.cloud.ai.trip.agent.mcp;

import com.fons.cloud.ai.trip.infrastructure.config.properties.TripMcpConfigProperties;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import jakarta.annotation.Resource;

import java.util.List;

/**
 * @author hongqy
 */
public abstract class BaseMcp {

    @Resource
    protected TripMcpConfigProperties properties;

    /**
     * 获取MCP客户端
     * @return
     */
    public abstract McpClientWrapper getMcpClient();

    /**
     * 获取启用的工具列表
     * @return
     */
    public abstract List<String> getEnabledTools();
}
