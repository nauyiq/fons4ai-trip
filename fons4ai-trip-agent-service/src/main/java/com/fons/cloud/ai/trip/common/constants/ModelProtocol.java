package com.fons.cloud.ai.trip.common.constants;

/**
 * 模型调用协议，只声明当前已经实现的客户端适配方式。
 *
 * @author hongqy
 */
public enum ModelProtocol {

    /** OpenAI Chat Completions 兼容协议，不代表服务商一定是 OpenAI。 */
    OPENAI_CHAT_COMPLETIONS,

    /** 阿里云 DashScope 原生协议，使用其文本或多模态端点。 */
    DASHSCOPE
}
