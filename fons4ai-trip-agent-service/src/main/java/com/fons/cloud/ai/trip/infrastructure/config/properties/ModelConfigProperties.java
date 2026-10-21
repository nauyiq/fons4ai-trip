package com.fons.cloud.ai.trip.infrastructure.config.properties;

import com.fons.cloud.ai.trip.common.constants.ModelMessageFormat;
import com.fons.cloud.ai.trip.common.constants.ModelProtocol;
import com.fons.cloud.ai.trip.common.constants.ModelType;
import io.agentscope.extensions.model.dashscope.EndpointType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按业务模型角色配置服务商、协议和生成参数，业务 Agent 不直接选择客户端实现。
 *
 * @author hongqy
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "trip.model")
public class ModelConfigProperties {

    /**
     * 每个角色独立装配；保持现有 ModelType 与 Spring Bean 名称的对应关系。
     */
    private Map<ModelType, ModelConfig> configs = new HashMap<>();

    @Getter
    @Setter
    public static class ModelConfig {

        /**
         * 未填写时兼容原有 DashScope 配置；新接入应明确指定协议。
         */
        private ModelProtocol protocol = ModelProtocol.DASHSCOPE;

        /**
         * 服务商 API 凭据；不得写入装配日志或异常信息。
         */
        private String apiKey;

        /**
         * 当前协议的服务地址；OpenAI Chat 使用包含兼容路径的 Base URL。
         */
        private String baseUrl;

        /**
         * 服务端实际识别的模型 ID，不用于推断调用协议或消息兼容规则。
         */
        private String modelName;

        /**
         * OpenAI 兼容消息规则；DashScope 原生协议只接受 STANDARD。
         */
        private ModelMessageFormat messageFormat = ModelMessageFormat.STANDARD;

        /**
         * 仅用于 DashScope 原生协议；AUTO 沿用 SDK 的模型端点识别。
         */
        private EndpointType endpointType = EndpointType.AUTO;

        /**
         * OpenAI Chat 请求体的供应商扩展参数，不允许覆盖消息、工具和流式协议字段。
         * 当前 DashScope 适配只接收 enable_thinking，并通过原生 Builder 设置；其他扩展明确拒绝。
         */
        private Map<String, Object> extraBody = new LinkedHashMap<>();
    }

}
