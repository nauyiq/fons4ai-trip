package com.fons.cloud.ai.trip.common.constants;

/**
 * OpenAI 兼容协议的消息格式规则，复用 AgentScope 已有的 Formatter。
 * 服务商可以托管不同系列模型，因此消息格式独立于服务地址配置。
 *
 * @author hongqy
 */
public enum ModelMessageFormat {

    /** 标准 Chat Completions 消息格式；DashScope 原生协议也保留此默认值。 */
    STANDARD,

    /** DeepSeek 的工具历史与 reasoning_content 兼容规则。 */
    DEEPSEEK,

    /** GLM 的消息与工具选择兼容规则。 */
    GLM
}
