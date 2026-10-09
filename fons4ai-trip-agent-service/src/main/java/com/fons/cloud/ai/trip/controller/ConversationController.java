package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.trip.application.conversation.ConversationApplicationService;
import com.fons.cloud.ai.trip.application.conversation.ConversationReadApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.request.ConversationInterruptRequest;
import com.fons.cloud.ai.trip.common.request.ConversationReplayRequest;
import com.fons.cloud.ai.trip.common.request.ConversationStreamRequest;
import com.fons.cloud.ai.trip.common.request.PageMessageRequest;
import com.fons.cloud.ai.trip.common.vo.ConversationInfo;
import com.fons.cloud.ai.trip.common.vo.MessageInfo;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.PageResult;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

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
    private final ConversationReadApplicationService conversationReadApplicationService;

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
     *     当前接口设计为 回复{@link HumanInTheLoopKind#APPROVAL} 的HITL请求。
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


    /**
     * 获取当前登录用户的所有历史会话
     * @return
     */
    @GetMapping("/list")
    public R<List<ConversationInfo>> list() {
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        return conversationReadApplicationService.getConversationList(userId);
    }

    /**
     * 分页查询会话聊天记录
     * @param conversationId
     * @param page
     * @param pageSize
     * @return
     */
    @GetMapping("/{conversationId}/messages")
    public R<PageResult<MessageInfo>> conversationMessages(@PathVariable String conversationId, Integer page, Integer pageSize) {
        if (page == null || pageSize == null || page < 1 || pageSize < 1) {
            return R.failed(ResultCode.PARAMS_ERROR);
        }
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        PageMessageRequest request = new PageMessageRequest(conversationId, userId, page, pageSize);
        return conversationReadApplicationService.pageQueryConversationMessages(request);
    }

    /**
     * 删除会话
     * @param conversationId
     * @return
     */
    @DeleteMapping("/{conversationId}")
    public R<Void> delete(@PathVariable String conversationId) {
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        return conversationApplicationService.delete(conversationId, userId);
    }

}
