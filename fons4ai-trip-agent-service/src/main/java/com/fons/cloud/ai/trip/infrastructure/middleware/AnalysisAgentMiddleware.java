package com.fons.cloud.ai.trip.infrastructure.middleware;

import io.agentscope.core.middleware.MiddlewareBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 将分析性Agent的分析内容注入到主任务Agent中， 并输出thinkingEvent
 * @author hongqy
 */
@Slf4j
@Component
public class AnalysisAgentMiddleware implements MiddlewareBase {
}
