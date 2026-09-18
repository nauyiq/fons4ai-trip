package com.fons.cloud.ai.trip.infrastructure.client;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.dto.DestinationNewsQueryResult;
import com.fons.cloud.ai.trip.common.dto.DestinationNewsQueryResult.NewsArticle;
import com.fons.cloud.ai.trip.infrastructure.client.model.NewsDataResponse;
import com.fons.cloud.ai.trip.infrastructure.client.model.NewsDataResponse.Article;
import com.fons.cloud.ai.trip.infrastructure.client.model.NewsDataResponse.ResponseStatus;
import com.fons.cloud.ai.trip.infrastructure.config.properties.DestinationNewsClientProperties;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * 目的地资讯HTTP客户端，封装NewsData.io请求与协议转换，不依赖AgentScope。
 * 城市与业务主题的查询词组装、R封装和降级提示由工具层负责，不包含分页或自动重试。
 *
 * @author hongqy
 */
@Slf4j
@Component
public class DestinationNewsClient {

    private static final String SOURCE = "newsdata.io";
    private static final String API_KEY_HEADER = "X-ACCESS-KEY";
    private static final String SUCCESS_STATUS = "success";
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 50;

    private final RestClient restClient;
    private final String apiKey;
    private final String language;
    private final int pageSize;
    private final int maxQueryLength;

    /** 使用独立HTTP配置，保留Spring的Builder定制，不修改共享Builder。 */
    public DestinationNewsClient(RestClient.Builder builder, DestinationNewsClientProperties properties) {
        Assert.notBlank(properties.getBaseUrl(), () -> SystemIntervalException.of("资讯服务地址不能为空"));
        URI baseUri;
        try {
            baseUri = URI.create(properties.getBaseUrl().trim());
        } catch (IllegalArgumentException e) {
            throw SystemIntervalException.of("资讯服务地址格式无效", e);
        }
        Assert.isTrue(("https".equalsIgnoreCase(baseUri.getScheme()) || "http".equalsIgnoreCase(baseUri.getScheme()))
                        && baseUri.getHost() != null && baseUri.getRawQuery() == null && baseUri.getRawFragment() == null,
                () -> SystemIntervalException.of("资讯服务地址必须为合法的HTTP或HTTPS地址，不能包含查询参数或片段"));
        validateTimeout(properties.getConnectTimeout(), "连接");
        validateTimeout(properties.getReadTimeout(), "读取");
        Assert.notBlank(properties.getLanguage(), () -> SystemIntervalException.of("资讯查询语言不能为空"));
        Assert.isTrue(properties.getPageSize() >= MIN_PAGE_SIZE && properties.getPageSize() <= MAX_PAGE_SIZE,
                () -> SystemIntervalException.of("资讯查询单页条数必须在" + MIN_PAGE_SIZE + "至" + MAX_PAGE_SIZE + "之间"));
        Assert.isTrue(properties.getMaxQueryLength() > 0, () -> SystemIntervalException.of("资讯查询词长度上限必须大于0"));

        this.apiKey = StringUtils.trimToEmpty(properties.getApiKey());
        Assert.isTrue(apiKey.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)),
                () -> SystemIntervalException.of("资讯服务API Key不能包含内部空白或控制字符"));
        this.language = properties.getLanguage().trim();
        this.pageSize = properties.getPageSize();
        this.maxQueryLength = properties.getMaxQueryLength();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = builder.clone().baseUrl(baseUri.toString()).requestFactory(requestFactory).build();
    }

    /**
     * 使用上层已经组装好的关键词检索单页资讯，不解释traffic、safety等业务主题。
     * 无服务凭据返回available=false；正常无匹配返回available=true及空列表；请求或解析失败抛出系统异常。
     *
     * @param query 查询词，例如“北京 交通”，首尾空格会去除，支持供应商查询表达式
     * @return 具体资讯DTO，不包含凭据或原始响应
     */
    public DestinationNewsQueryResult queryNews(String query) {
        Assert.notBlank(query, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "资讯查询词不能为空"));
        String normalizedQuery = query.trim();
        Assert.isTrue(normalizedQuery.codePointCount(0, normalizedQuery.length()) <= maxQueryLength,
                () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "资讯查询词长度不能超过" + maxQueryLength + "个字符"));
        if (apiKey.isEmpty()) {
            log.info("[DestinationNewsClient] 未配置资讯服务API Key，跳过请求");
            return new DestinationNewsQueryResult(normalizedQuery, SOURCE, false, List.of());
        }

        String body;
        try {
            // Key放入供应商支持的请求头，避免进入URL及URL观测日志。
            body = restClient.get().uri("/api/1/news?q={query}&language={language}&size={size}", normalizedQuery, language, pageSize)
                    .header(API_KEY_HEADER, apiKey).accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            // 不回传远端错误正文，正文及底层异常可能包含凭据回显。
            log.warn("[DestinationNewsClient] 资讯服务请求失败，httpStatus={}", e.getStatusCode().value());
            throw SystemIntervalException.of("资讯服务HTTP请求失败，状态码：" + e.getStatusCode().value());
        } catch (RestClientException e) {
            log.warn("[DestinationNewsClient] 资讯服务请求异常，exceptionType={}", e.getClass().getSimpleName());
            throw SystemIntervalException.of("资讯服务HTTP请求失败，请稍后重试");
        }
        Assert.notBlank(body, () -> SystemIntervalException.of("资讯服务返回空响应"));
        NewsDataResponse response;
        try {
            ResponseStatus status = JSON.parseObject(body, ResponseStatus.class);
            Assert.isTrue(status != null && SUCCESS_STATUS.equalsIgnoreCase(status.status()),
                    () -> SystemIntervalException.of("资讯服务未返回成功状态，请检查服务凭据、额度或请求配置"));
            response = JSON.parseObject(body, NewsDataResponse.class);
        } catch (JSONException e) {
            throw SystemIntervalException.of("资讯服务响应格式无效");
        }
        // 只有明确成功且结果数组存在，才能把空数组解释为没有匹配资讯。
        Assert.isTrue(response != null && response.results() != null,
                () -> SystemIntervalException.of("资讯服务响应缺失结果数组"));
        if (response != null) {
            if (response.results() != null) {
                Assert.isTrue(response.results().size() <= pageSize,
                        () -> SystemIntervalException.of("资讯服务返回条数超出请求的单页上限"));
            }
        }
        List<NewsArticle> news = null;
        if (response != null) {
            if (response.results() != null) {
                news = response.results().stream().map(this::toNewsArticle).toList();
            }
        }
        if (news != null) {
            log.info("[DestinationNewsClient] 资讯查询完成，count={}", news.size());
        }
        return new DestinationNewsQueryResult(normalizedQuery, SOURCE, true, news);
    }

    private NewsArticle toNewsArticle(Article article) {
        Assert.isTrue(article != null && StringUtils.isNoneBlank(article.title(), article.link()),
                () -> SystemIntervalException.of("资讯结果缺失标题或原文链接"));
        return new NewsArticle(article.title().trim(), article.description(), article.link().trim(), article.publishedAt(),
                article.publishedTimeZone(), article.sourceId(), article.sourceName());
    }

    private void validateTimeout(Duration timeout, String label) {
        Assert.isTrue(timeout != null && !timeout.isNegative() && !timeout.isZero(),
                () -> SystemIntervalException.of("资讯服务" + label + "超时必须大于0"));
    }
}
