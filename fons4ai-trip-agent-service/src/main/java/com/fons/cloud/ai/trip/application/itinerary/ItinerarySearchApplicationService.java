package com.fons.cloud.ai.trip.application.itinerary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 行程搜索应用服务，选择客户端策略，按可信身份关联、合并并保存Trip标准候选。
 * 供应商请求和响应转换由ItinerarySearchClient的具体实现负责，应用层不解析供应商协议。
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItinerarySearchApplicationService {
}
