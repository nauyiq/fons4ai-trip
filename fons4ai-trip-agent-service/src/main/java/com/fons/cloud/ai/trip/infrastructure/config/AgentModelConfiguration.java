package com.fons.cloud.ai.trip.infrastructure.config;

import cn.hutool.http.HttpUtil;
import com.fons.cloud.ai.trip.common.constants.ModelMessageFormat;
import com.fons.cloud.ai.trip.common.constants.ModelProtocol;
import com.fons.cloud.ai.trip.common.constants.ModelType;
import com.fons.cloud.ai.trip.infrastructure.config.properties.ModelConfigProperties;
import com.fons.cloud.ai.trip.common.exception.TripExceptions;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.EndpointType;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 按业务角色统一装配模型。协议决定客户端，消息格式和生成参数在此处适配。
 * 保留原有四个 Bean，ModelFacade 和业务 Agent 的调用方式不变。
 * @author hongqy
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties({ModelConfigProperties.class})
public class AgentModelConfiguration {

    /** 消息与工具由 AgentScope 管理，配置扩展不能覆盖这些协议字段。 */
    private static final Set<String> RESERVED_BODY_FIELDS = Set.of(
            "model", "messages", "input", "tools", "tool_choice", "stream", "stream_options",
            "response_format", "parameters", "extra_body");

    private final ModelConfigProperties modelConfigProperties;

    @Bean(name = "fastModel")
    public Model fastModel() {
        return createModel(ModelType.FAST_MODEL);
    }

    @Bean(name = "strongModel")
    public Model strongModel() {
        return createModel(ModelType.STRONG_MODEL);
    }

    @Bean(name = "strongThinkModel")
    public Model strongThinkModel() {
        return createModel(ModelType.STRONG_THING_MODEL);
    }

    @Bean(name = "stableModel")
    public Model stableModel() {
        return createModel(ModelType.STABLE_MODEL);
    }

    /** 启动时完成装配，不在一次运行中根据异常或模型名隐式切换协议和服务商。 */
    private Model createModel(ModelType type) {
        ModelConfigProperties.ModelConfig config = validModelConfig(type);
        // 使用独立参数副本，避免后续 Builder 修改配置绑定对象中的集合。
        Map<String, Object> extraBody = Collections.unmodifiableMap(new LinkedHashMap<>(config.getExtraBody()));
        Model model = switch (config.getProtocol()) {
            case OPENAI_CHAT_COMPLETIONS -> buildOpenAiModel(config, extraBody);
            case DASHSCOPE -> buildDashScopeModel(type, config, extraBody);
        };
        // 只记录装配选择，不记录凭据、完整地址和扩展参数值。
        log.info("[MODEL]模型装配完成, modelType:{}, protocol:{}, modelName:{}, messageFormat:{}", type, config.getProtocol(), config.getModelName(), config.getMessageFormat());
        return model;
    }

    private Model buildOpenAiModel(ModelConfigProperties.ModelConfig config, Map<String, Object> extraBody) {
        OpenAIChatFormatter formatter = switch (config.getMessageFormat()) {
            case STANDARD -> new OpenAIChatFormatter();
            case DEEPSEEK -> new DeepSeekFormatter();
            case GLM -> new GLMFormatter();
        };
        return OpenAIChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .apiKey(config.getApiKey())
                .stream(true)
                .formatter(formatter)
                // SDK 将这些参数合入请求体，不额外创建名为 extra_body 的 JSON 层。
                .generateOptions(GenerateOptions.builder().additionalBodyParams(extraBody).build())
                .build();
    }

    private Model buildDashScopeModel(ModelType type, ModelConfigProperties.ModelConfig config,
                                     Map<String, Object> extraBody) {
        // 旧配置保持原行为：fast 关闭思考，其余角色开启；显式配置优先。
        // 原生思考开关属于 parameters，由 Builder 映射，不能直接复用 OpenAI 的顶层扩展报文。
        Boolean enableThinking = extraBody.containsKey("enable_thinking")
                ? (Boolean) extraBody.get("enable_thinking") : type != ModelType.FAST_MODEL;
        return DashScopeChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .apiKey(config.getApiKey())
                .stream(true)
                .endpointType(config.getEndpointType())
                .enableThinking(enableThinking)
                .build();
    }

    /** 只校验本地配置，不在 Spring 启动时请求外部模型服务。 */
    private ModelConfigProperties.ModelConfig validModelConfig(ModelType type) {
        Map<ModelType, ModelConfigProperties.ModelConfig> models = modelConfigProperties.getConfigs();
        if (MapUtils.isEmpty(models) || !models.containsKey(type)) {
            throw TripExceptions.system("未找到TRAVEL-AGENT模型配置, 请检查");
        }
        ModelConfigProperties.ModelConfig config = models.get(type);
        if (config == null || StringUtils.isAnyBlank(config.getApiKey(), config.getBaseUrl(), config.getModelName())) {
            throw TripExceptions.system("模型配置不合法, 请检查, modelType:" + type);
        }
        if (config.getProtocol() == null || config.getMessageFormat() == null || config.getEndpointType() == null) {
            throw TripExceptions.system("模型协议、消息格式和端点类型不能为空, modelType:" + type);
        }
        validateBaseUrl(type, config.getBaseUrl());
        if (config.getExtraBody() == null) {
            throw TripExceptions.system("模型extra-body不能显式配置为null, modelType:" + type);
        }
        for (String field : config.getExtraBody().keySet()) {
            if (StringUtils.isBlank(field) || RESERVED_BODY_FIELDS.contains(field)) {
                throw TripExceptions.system("模型extra-body包含空字段或AgentScope管理的协议字段, modelType:" + type);
            }
        }
        if (config.getProtocol() == ModelProtocol.DASHSCOPE) {
            if (config.getMessageFormat() != ModelMessageFormat.STANDARD) {
                throw TripExceptions.system("DashScope原生协议只接受STANDARD消息格式, modelType:" + type);
            }
            if (config.getExtraBody().keySet().stream().anyMatch(field -> !"enable_thinking".equals(field))) {
                throw TripExceptions.system("当前DashScope原生适配的extra-body只支持enable_thinking, modelType:" + type);
            }
        } else if (config.getEndpointType() != EndpointType.AUTO) {
            throw TripExceptions.system("endpoint-type仅适用于DashScope原生协议, modelType:" + type);
        }
        if (config.getExtraBody().containsKey("enable_thinking")
                && !(config.getExtraBody().get("enable_thinking") instanceof Boolean)) {
            throw TripExceptions.system("模型enable_thinking必须是布尔值, modelType:" + type);
        }
        return config;
    }

    private void validateBaseUrl(ModelType type, String baseUrl) {
        try {
            if (!HttpUtil.isHttp(baseUrl) && !HttpUtil.isHttps(baseUrl)) {
                throw TripExceptions.system("模型base-url必须是有效的HTTP服务地址, modelType:" + type);
            }
        } catch (Exception e) {
            throw TripExceptions.system("模型base-url格式不合法, modelType:" + type);
        }
    }
}
