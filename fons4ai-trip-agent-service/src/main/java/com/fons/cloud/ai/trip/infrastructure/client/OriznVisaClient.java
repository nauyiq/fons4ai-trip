package com.fons.cloud.ai.trip.infrastructure.client;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.infrastructure.config.properties.OriznVisaConfigProperties;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;

/**
 * Orizn visa 客户端， 提供了任意两国之间需要的签证类型， 费用， 处理时间等等信息
 * <p>
 * 可参考官方API文档 <a href="https://visa.orizn.app/visa-api/docs">orizn-visa-api</a>
 * github有提供MCP的方式 但是是基于stdio 传输启动， 需要Node、npm、npx 等环境 对容器环境等有要求 因此当前client封装对应的API
 * </p>
 *
 * @author hongqy
 */
@Slf4j
@Component
public class OriznVisaClient {

    private final RestClient restClient;
    private final OriznVisaConfigProperties properties;

    public OriznVisaClient(RestClient.Builder builder, OriznVisaConfigProperties properties) {
        Assert.notNull(properties, () -> SystemIntervalException.of("Orizn-visa configuration is null."));
        Assert.isTrue(StringUtils.isAnyBlank(properties.getApiKey(), properties.getBaseUrl()), () -> SystemIntervalException.of("Orizn-visa configuration is blank."));
        URI baseUri;
        try {
            baseUri = URI.create(properties.getBaseUrl().trim());
        } catch (IllegalArgumentException e) {
            throw SystemIntervalException.of("Orin-visa服务地址格式无效", e);
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = builder.clone().baseUrl(baseUri.toString()).requestFactory(requestFactory).build();
        this.properties = properties;
    }




}
