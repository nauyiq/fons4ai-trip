package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.trip.application.conversation.ConversationApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.request.ConversationInterruptRequest;
import com.fons.cloud.ai.trip.common.request.ConversationReplayRequest;
import com.fons.cloud.ai.trip.common.request.ConversationStreamRequest;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 聊天控制器， 核心逻辑入口
 * @author hongqy
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {
    private final SaTokenAuthTemplate saTokenAuthTemplate;
    private final ConversationApplicationService conversationApplicationService;

    /**
     * 发起聊天请求, 这里支持多模态消息
     * @param request
     * @return
     */
    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@Valid @RequestBody ConversationStreamRequest request) {
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return Flux.error(BusinessRuntimeException.of(TripAgentResultCode.LOGIN_EXPIRED));
        }
        if (CollectionUtils.isEmpty(request.getMessages())) {
            return Flux.error(BusinessRuntimeException.of(TripAgentResultCode.CHAT_MESSAGE_IS_EMPTY));
        }
        request.setUserId(userId);
        return conversationApplicationService.stream(request);
    }

    /**
     * 打断当前会话正在执行的Agent回复
     * @param conversationId
     * @return
     */
    @PostMapping(value = "/interrupt/{conversationId}")
    public R<Void> interrupt(@PathVariable String conversationId) {
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        return conversationApplicationService.interrupt(new ConversationInterruptRequest(userId, conversationId));
    }

    /**
     * 回复对话， 用户对于HITL消息类型的回复
     * <p>
     *     1. 当前接口设计为 回复{@link HumanInTheLoopKind#APPROVAL} 的HITL请求。
     *     2. 如果用户回复的是{@link HumanInTheLoopKind#INPUT_REQUIRED}， 内部会降级发起普通消息的请求，
     *   并把{@link ConversationReplayRequest#getAction()} 原值传给LLM
     * </p>
     * @param request
     * @return
     */
    @PostMapping(value = "/replay", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> replay(@Valid @RequestBody ConversationReplayRequest request) {
        if (request == null) {
            return Flux.error(BusinessRuntimeException.of(ResultCode.PARAM_UNDEFINED));
        }
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return Flux.error(BusinessRuntimeException.of(TripAgentResultCode.LOGIN_EXPIRED));
        }
        request.setUserId(userId);
        return conversationApplicationService.replay(request);
    }


}
