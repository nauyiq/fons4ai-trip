package com.fons.cloud.ai.trip.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Orizn visa 客户端， 提供了任意两国之间需要的签证类型， 费用， 处理时间等等信息
 * <p>
 *     可参考官方API文档 <a href="https://visa.orizn.app/visa-api/docs">orizn-visa-api</a>
 *     github有提供MCP的方式 但是是基于stdio 传输启动， 需要Node、npm、npx 等环境 对容器环境等有要求 因此当前client封装对应的API
 * </p>
 * @author hongqy
 */
@Slf4j
@Component

public class OriznVisaClient {




}
