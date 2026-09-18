package com.fons.cloud.ai.trip.agent.mcp;

import com.fons.cloud.ai.trip.infrastructure.config.properties.TripMcpConfigProperties;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import lombok.RequiredArgsConstructor;

/**
 * @author hongqy
 */
@RequiredArgsConstructor
public abstract class BaseMcp {

    protected final TripMcpConfigProperties properties;

    /**
     * 获取MCP客户端
     * @return
     */
    protected abstract McpClientWrapper getMcpClient();

}
