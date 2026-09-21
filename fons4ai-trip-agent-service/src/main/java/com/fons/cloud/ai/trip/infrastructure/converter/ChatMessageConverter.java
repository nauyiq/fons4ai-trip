package com.fons.cloud.ai.trip.infrastructure.converter;

import cn.hutool.core.io.IoUtil;
import com.fons.cloud.ai.agent.infrastructure.utils.AgentScopeMessageConverter;
import com.fons.cloud.ai.agent.model.request.AgentInputContent;
import com.fons.cloud.ai.agent.model.request.AgentInputContentType;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.file.api.OssStoreService;
import com.fons.cloud.file.common.request.OssObjectRequest;
import com.fons.cloud.file.common.response.OssObjectResponse;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageConverter {

    private final OssStoreService ossStoreService;
    private final AgentScopeMessageConverter agentScopeMessageConverter = AgentScopeMessageConverter.getInstance();

    public AgentInputContent convertAgentInputContent(ChatMessage message) {
        return switch (message.getType()) {
            case TEXT -> AgentInputContent.builder()
                    .text(message.getContent())
                    .type(AgentInputContentType.TEXT)
                    .build();
            case IMAGE, VOICE -> {
                OssObjectResponse objectInfo = ossStoreService.getObjectInfo(OssObjectRequest.builder().objectKey(message.getContent()).build());
                yield AgentInputContent.builder()
                        .data(IoUtil.readBytes(objectInfo.getInputStream()))
                        .mimeType(objectInfo.getContentType())
                        .type(message.getType() == ChatMessageContentType.IMAGE ? AgentInputContentType.IMAGE : AgentInputContentType.AUDIO).build();
            }
        };
    }

    public UserMessage convertUserMessage(AgentRequest request) {
        return agentScopeMessageConverter.createUserMessage(request);
    }


}
