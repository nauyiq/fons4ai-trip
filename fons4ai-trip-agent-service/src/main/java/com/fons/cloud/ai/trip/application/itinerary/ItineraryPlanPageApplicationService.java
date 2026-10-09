package com.fons.cloud.ai.trip.application.itinerary;

import com.fons.cloud.ai.trip.application.itinerary.validator.ItineraryPublicationEligibilityValidatorService;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.ItineraryPublicationSource;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanPageArtifact;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import com.fons.cloud.ai.trip.infrastructure.render.ItineraryPlanHtmlRenderer;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanPageStore;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对审核后可展示的行程方案生成独立HTML，并保存、读取页面对象。
 * 本服务不负责发送前端事件；页面读取时重新校验当前审核状态。
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryPlanPageApplicationService {

    private final ItineraryPublicationEligibilityValidatorService publicationEligibilityService;
    private final ItineraryPlanHtmlRenderer htmlRenderer;
    private final ItineraryPlanPageStore pageStore;
    private final ChatConversationDomainService conversationDomainService;

    /**
     * 根据当前用户、会话和规划标识，生成并保存已审核方案的HTML页面。
     *
     * @param owner 运行时提供的可信用户和会话归属
     * @param planId 已保存的真实规划标识
     * @return 页面对应的规划、审核、推荐方案和OSS对象路径
     */
    public ItineraryPlanPageArtifact create(CandidateOwner owner, String planId) {
        // 1. 重新读取当前规划、最新审核和修复状态，确认页面允许展示
        ItineraryPublicationSource source = publicationEligibilityService.requirePublishable(owner, planId);
        // 2. 使用同一份已校验快照渲染完整HTML，避免页面数据与审核结论脱节
        String html = htmlRenderer.render(source);
        // 3. 按可信归属和规划标识保存页面，重新审核时覆盖同一方案的旧页面
        String objectKey = pageStore.save(owner, source.planningResult().getPlanId(),
                source.reviewResult().getReviewId(), html);
        log.info("[ItineraryPlanPageApplicationService] 行程页面已保存，userId={}, conversationId={}, planId={}, reviewId={}, objectKey={}",
                owner.userId(), owner.conversationId(), source.planningResult().getPlanId(),
                source.reviewResult().getReviewId(), objectKey);
        return new ItineraryPlanPageArtifact(source.planningResult().getPlanId(),
                source.reviewResult().getReviewId(), source.recommendedProposal().proposalId(), objectKey);
    }

    /**
     * 读取当前已审核方案的页面。页面尚未生成或与最新审核不一致时返回null。
     *
     * @param owner 当前登录用户和已验证的会话归属
     * @param planId 规划标识
     */
    public String read(CandidateOwner owner, String planId) {
        // 1. 核实会话确实属于当前登录用户
        if (conversationDomainService.findByUseIdAndConversationId(owner.userId(), owner.conversationId()) == null) {
            throw BusinessRuntimeException.of(ResultCode.ILLEGAL_REQUEST_LIMITED.getCode(), "当前用户没有访问该会话的权限");
        }
        // 2. 重新确认方案、最新审核及修复状态仍允许展示
        ItineraryPublicationSource source = publicationEligibilityService.requirePublishable(owner, planId);
        // 3. 只读取与当前审核结果一致的页面
        return pageStore.read(owner, source.planningResult().getPlanId(), source.reviewResult().getReviewId());
    }
}
