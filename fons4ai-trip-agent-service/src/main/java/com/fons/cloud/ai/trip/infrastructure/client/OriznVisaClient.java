package com.fons.cloud.ai.trip.infrastructure.client;

import cn.hutool.core.lang.Assert;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.VisaQuickCheckResult;
import com.fons.cloud.ai.trip.common.dto.VisaRequirementResult;
import com.fons.cloud.ai.trip.infrastructure.client.model.orizn.OriznVisaCheckResponse;
import com.fons.cloud.ai.trip.infrastructure.config.properties.OriznVisaConfigProperties;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Orizn 签证 REST 客户端。只在服务端使用企业凭据，并将供应商响应转换为 Trip 契约。
 *
 * @see <a href="https://visa.orizn.app/visa-api/docs">Orizn REST API</a>
 */
@Slf4j
@Component
public class OriznVisaClient {

    private static final String API_KEY_HEADER = "x-api-key";
    private static final List<String> EXTENDED_FIELDS = List.of("visa_fee", "processing_days", "transit_visa",
            "vaccinations_required", "insurance_required", "health_requirements", "safety", "embassy",
            "photo_specs", "entry_by_mode");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public OriznVisaClient(RestClient.Builder builder, ObjectMapper objectMapper, OriznVisaConfigProperties properties) {
        Assert.notNull(properties, () -> SystemIntervalException.of("签证服务配置不能为空"));
        Assert.notBlank(properties.getBaseUrl(), () -> SystemIntervalException.of("签证服务地址不能为空"));
        URI baseUri;
        try {
            baseUri = URI.create(properties.getBaseUrl().trim());
        } catch (IllegalArgumentException e) {
            throw SystemIntervalException.of("签证服务地址格式无效", e);
        }
        Assert.isTrue(("https".equalsIgnoreCase(baseUri.getScheme()) || "http".equalsIgnoreCase(baseUri.getScheme()))
                        && baseUri.getHost() != null && baseUri.getRawQuery() == null && baseUri.getRawFragment() == null,
                () -> SystemIntervalException.of("签证服务地址必须为合法的HTTP或HTTPS地址"));
        validateTimeout(properties.getConnectTimeout(), "连接");
        validateTimeout(properties.getReadTimeout(), "读取");
        this.apiKey = StringUtils.trimToEmpty(properties.getApiKey());
        Assert.isTrue(apiKey.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)),
                () -> SystemIntervalException.of("签证服务API Key不能包含空白或控制字符"));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = builder.clone().baseUrl(baseUri.toString()).requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    /** 快速查询免签、签证要求及允许停留天数。 */
    public VisaQuickCheckResult quickCheck(String passport, String destination) {
        String body = get("/api/v1/visa/check?passport={passport}&destination={destination}", passport, destination);
        try {
            OriznVisaCheckResponse response = objectMapper.readValue(body, OriznVisaCheckResponse.class);
            Assert.isTrue(response != null && passport.equals(response.passport()) && destination.equals(response.destination())
                            && StringUtils.isNotBlank(response.requirement()) && response.visaRequired() != null,
                    () -> SystemIntervalException.of("签证服务快速查询响应缺失必要字段"));
            return new VisaQuickCheckResult(response.passport(), response.destination(), response.requirement(),
                    response.visaFreeDays(), response.visaRequired(), response.lastVerified());
        } catch (JsonProcessingException e) {
            throw SystemIntervalException.of("签证服务快速查询响应格式无效");
        }
    }

    /** 查询签证详情；套餐未解锁的升级占位对象不作为签证事实返回。 */
    public VisaRequirementResult checkRequirement(String passport, String destination, String language) {
        String body = get("/api/v1/visa?passport={passport}&destination={destination}&lang={lang}",
                passport, destination, language);
        try {
            JsonNode root = objectMapper.readTree(body);
            Assert.isTrue(root != null && root.isObject(), () -> SystemIntervalException.of("签证服务详情响应格式无效"));
            JsonNode data = root.path("data");
            Assert.isTrue(data.isObject() && passport.equals(text(data, "passport"))
                            && destination.equals(text(data, "destination"))
                            && StringUtils.isNotBlank(text(data, "requirement")),
                    () -> SystemIntervalException.of("签证服务详情响应缺失必要字段"));
            Map<String, Object> extendedDetails = new LinkedHashMap<>();
            for (String field : EXTENDED_FIELDS) {
                JsonNode value = data.path(field);
                if (!value.isMissingNode() && !value.isNull() && !value.has("upgrade")) {
                    extendedDetails.put(field, objectMapper.convertValue(value, new TypeReference<Object>() {}));
                }
            }
            return new VisaRequirementResult(passport, destination, language, text(data, "requirement"),
                    integer(data, "visa_free_days"), bool(data, "visa_required"), text(data, "description"),
                    stringList(data, "documents_required"), stringList(data, "process"), stringList(data, "tips"),
                    integer(data, "passport_validity_months"), bool(data, "verified"), Map.copyOf(extendedDetails));
        } catch (JsonProcessingException e) {
            throw SystemIntervalException.of("签证服务详情响应格式无效");
        }
    }

    private String get(String path, Object... params) {
        if (apiKey.isEmpty()) {
            throw SystemIntervalException.of("签证服务未配置，请联系管理员");
        }
        try {
            String body = restClient.get().uri(path, params).header(API_KEY_HEADER, apiKey)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
            Assert.notBlank(body, () -> SystemIntervalException.of("签证服务返回空响应"));
            return body;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("[OriznVisaClient] 请求失败，httpStatus={}", status);
            if (status == 400) {
                throw BusinessRuntimeException.of(TripAgentToolResultCode.INVALID_PARAM.getCode(),
                        "签证服务不支持所提供的国家代码或语言，请核对后重试");
            }
            if (status == 404) {
                throw BusinessRuntimeException.of(TripAgentToolResultCode.VISA_RECORD_NOT_FOUND.getCode(),
                        "签证服务未收录该护照签发国与目的地组合，不能据此认定免签");
            }
            String message = status == 429 ? "签证服务请求额度已用尽，请稍后重试或联系管理员"
                    : status == 401 || status == 403 ? "签证服务凭据或套餐不可用，请联系管理员"
                    : "签证服务请求失败，请稍后重试";
            throw SystemIntervalException.of(message);
        } catch (RestClientException e) {
            log.warn("[OriznVisaClient] 请求异常，exceptionType={}", e.getClass().getSimpleName());
            throw SystemIntervalException.of("签证服务请求失败，请稍后重试");
        }
    }

    private String text(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private Integer integer(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isInt() ? value.intValue() : null;
    }

    private Boolean bool(JsonNode data, String field) {
        JsonNode value = data.path(field);
        return value.isBoolean() ? value.booleanValue() : null;
    }

    private List<String> stringList(JsonNode data, String field) {
        JsonNode value = data.path(field);
        if (!value.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(value.spliterator(), false).filter(JsonNode::isTextual)
                .map(JsonNode::asText).toList();
    }

    private void validateTimeout(Duration timeout, String label) {
        Assert.isTrue(timeout != null && !timeout.isNegative() && !timeout.isZero(),
                () -> SystemIntervalException.of("签证服务" + label + "超时必须大于0"));
    }
}
