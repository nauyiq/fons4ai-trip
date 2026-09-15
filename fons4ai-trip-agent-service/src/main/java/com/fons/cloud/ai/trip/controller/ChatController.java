package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.trip.application.ChatApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.request.ChatRequest;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 聊天控制器， 核心逻辑入口
 * @author hongqy
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {
    private final SaTokenAuthTemplate saTokenAuthTemplate;
    private final ChatApplicationService chatApplicationService;

    /**
     * 发起聊天请求, 这里支持多模态消息
     * @param request
     * @return
     */
    @PostMapping()
    public Flux<String> chat(@Valid ChatRequest request) {
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return Flux.error(BusinessRuntimeException.of(TripAgentResultCode.LOGIN_EXPIRED));
        }
        if (CollectionUtils.isEmpty(request.getMessages())) {
            return Flux.error(BusinessRuntimeException.of(TripAgentResultCode.CHAT_MESSAGE_IS_EMPTY));
        }
        request.setUserId(userId);
        return chatApplicationService.chat(request);
    }

}
