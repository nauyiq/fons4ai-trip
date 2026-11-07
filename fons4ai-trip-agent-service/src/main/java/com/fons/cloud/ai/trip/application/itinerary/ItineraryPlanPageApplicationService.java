package com.fons.cloud.ai.trip.application.itinerary;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fons.cloud.ai.trip.application.itinerary.validator.ItineraryPublicationEligibilityValidatorService;
import com.fons.cloud.ai.trip.common.dto.ItineraryPublicationSource;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanPageArtifact;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.entity.TravelOrderItineraryPlan;
import com.fons.cloud.ai.trip.domain.mapper.TravelOrderItineraryPlanMapper;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.ai.trip.infrastructure.render.ItineraryPlanHtmlRenderer;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanPageStore;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanRepository;

import com.fons.cloud.ai.trip.common.exception.TripExceptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

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
    private final ItineraryPlanRepository planRepository;
    private final ChatConversationDomainService conversationDomainService;
    private final TravelOrderDomainService travelOrderDomainService;
    private final TravelOrderItineraryPlanMapper itineraryPlanMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 根据当前用户、会话和规划标识，生成并保存已审核方案的HTML页面。
     *
     * @param userId 当前用户
     * @param orderId 已确认属于当前用户的差旅单
     * @param conversationId 当前业务会话
     * @param planId 已保存的真实规划标识
     * @return 页面对应的规划、审核、推荐方案和OSS对象路径
     */
    public ItineraryPlanPageArtifact create(String userId, String orderId, String conversationId, String planId) {
        requireConversation(userId, conversationId);
        String validOrderId = requireOrder(userId, orderId).getOrderId();
        // 1. 重新读取当前规划、最新审核和修复状态，确认页面允许展示
        ItineraryPublicationSource source = publicationEligibilityService.requirePublishable(userId, validOrderId, conversationId, planId);
        Assert.isTrue(StringUtils.isNotBlank(source.reviewResult().getReviewId())
                        && StringUtils.equals(source.planningResult().getPlanId(), source.reviewResult().getPlanId()),
                () -> TripExceptions.system("规划与审核标识不一致，不能发布"));
        // 读取既有页面指针；上传前不改变数据库，以免失败影响旧方案。
        TravelOrderItineraryPlan previous = itineraryPlanMapper.selectById(validOrderId);
        String previousKey = previous == null ? null : previous.getPlanHtmlUrl();
        // 2. 使用同一份已校验快照渲染完整HTML，避免页面数据与审核结论脱节
        String html = htmlRenderer.render(source);
        // 新对象使用唯一标识，不能覆盖仍被数据库引用的旧页面。
        String objectKey = pageStore.savePublished(userId, validOrderId, source.planningResult().getPlanId(),
                source.reviewResult().getReviewId(), html);

        Boolean committed = transactionTemplate.execute(status -> {
            // 所有订单修改、取消与发布先锁主单，提交前重新核实当前条件和用户归属。
            TravelOrder order = travelOrderDomainService.findByOrderIdAndUserIdForUpdate(validOrderId, userId);
            ItineraryPlanApplicationService.requireCurrentOrderMatchesPlan(order, source.planningResult());
            // 上传期间若同一会话已有新审核或新规划，不把原审核结果发布为最新。
            ItineraryPublicationSource latest = publicationEligibilityService.requirePublishable(userId, validOrderId, conversationId, planId);
            Assert.isTrue(StringUtils.equals(source.reviewResult().getReviewId(), latest.reviewResult().getReviewId())
                            && StringUtils.equals(source.recommendedProposal().proposalId(), latest.recommendedProposal().proposalId()),
                    () -> TripExceptions.invalidRequest("方案审核结果已变化，请重新查看后发布"));
            TravelOrderItineraryPlan current = itineraryPlanMapper.selectOne(Wrappers.lambdaQuery(TravelOrderItineraryPlan.class)
                    .eq(TravelOrderItineraryPlan::getOrderId, validOrderId).last("FOR UPDATE"));
            String currentKey = current == null ? null : current.getPlanHtmlUrl();
            if (!Objects.equals(previousKey, currentKey)) {
                throw TripExceptions.invalidRequest("该差旅单的方案已发生变化，请重新查看后发布");
            }
            String planJson = JSON.toJSONString(source.planningResult());
            String reviewJson = JSON.toJSONString(source.reviewResult());
            if (current == null) {
                TravelOrderItineraryPlan published = new TravelOrderItineraryPlan();
                published.setOrderId(validOrderId);
                published.setPlanSnapshot(planJson);
                published.setReviewSnapshot(reviewJson);
                published.setPlanHtmlUrl(objectKey);
                Assert.isTrue(itineraryPlanMapper.insert(published) == 1, () -> TripExceptions.system("保存行程方案失败"));
            } else {
                // 不写创建时间，更新时间显式交给数据库维护。
                int updated = itineraryPlanMapper.update(null, Wrappers.lambdaUpdate(TravelOrderItineraryPlan.class)
                        .eq(TravelOrderItineraryPlan::getOrderId, validOrderId)
                        .set(TravelOrderItineraryPlan::getPlanSnapshot, planJson)
                        .set(TravelOrderItineraryPlan::getReviewSnapshot, reviewJson)
                        .set(TravelOrderItineraryPlan::getPlanHtmlUrl, objectKey)
                        .setSql("updated = CURRENT_TIMESTAMP"));
                Assert.isTrue(updated == 1, () -> TripExceptions.system("更新行程方案失败"));
            }
            return true;
        });
        Assert.isTrue(Boolean.TRUE.equals(committed), () -> TripExceptions.system("行程方案发布事务未完成"));
        log.info("[ItineraryPlanPageApplicationService] 行程方案已发布，userId={}, orderId={}, conversationId={}, planId={}, reviewId={}",
                userId, validOrderId, conversationId, source.planningResult().getPlanId(),
                source.reviewResult().getReviewId());
        return new ItineraryPlanPageArtifact(source.planningResult().getPlanId(),
                source.reviewResult().getReviewId(), source.recommendedProposal().proposalId(), objectKey);
    }

    /**
     * 读取当前已审核方案的页面。页面尚未生成或与最新审核不一致时返回null。
     *
     * @param userId 当前登录用户
     * @param orderId 差旅单标识；历史页面链接没有此参数，只按原会话归属只读
     * @param conversationId 业务会话标识
     * @param planId 规划标识
     */
    public String read(String userId, String orderId, String conversationId, String planId) {
        // 1. 核实会话确实属于当前登录用户
        requireConversation(userId, conversationId);
        ItineraryPublicationSource source;
        if (StringUtils.isBlank(orderId)) {
            // 先确认旧方案归属；订单方案以 MySQL 最新发布记录为准，不受新的会话审核状态影响。
            ItineraryPlanningResult legacy = planRepository.findLegacyById(userId, conversationId, planId);
            if (legacy == null) {
                return null;
            }
            if (legacy.getSourceTravelOrder() != null) {
                return readPublished(requireOrder(userId, legacy.getSourceTravelOrder().orderId()), planId);
            }
            // 无订单的独立历史方案仍使用旧会话的审核门禁。
            source = publicationEligibilityService.requireLegacyPublishable(userId, conversationId, planId);
        } else {
            // 当前发布快照来自MySQL；后续尚未发布的新审核不能使旧方案提前不可读。
            return readPublished(requireOrder(userId, orderId), planId);
        }
        // 旧独立方案保留原会话只读入口。
        return pageStore.read(userId, conversationId, source.planningResult().getPlanId(), source.reviewResult().getReviewId());
    }

    private String readPublished(TravelOrder order, String planId) {
        TravelOrderItineraryPlan published = itineraryPlanMapper.selectById(order.getOrderId());
        if (published == null) {
            return null;
        }
        ItineraryPlanningResult plan;
        ItineraryReviewResult review;
        try {
            plan = JSON.parseObject(published.getPlanSnapshot(), ItineraryPlanningResult.class);
            review = JSON.parseObject(published.getReviewSnapshot(), ItineraryReviewResult.class);
        } catch (JSONException e) {
            throw TripExceptions.system("已发布行程方案快照格式无效", e);
        }
        Assert.isTrue(plan != null && review != null && StringUtils.isNoneBlank(plan.getPlanId(), review.getReviewId())
                        && StringUtils.equals(plan.getPlanId(), review.getPlanId())
                        && review.getExecutionStatus() == ItineraryReviewExecutionStatus.COMPLETE
                        && review.getNextAction() == ItineraryReviewNextAction.PROCEED,
                () -> TripExceptions.system("已发布的规划与审核快照无效"));
        String recommendedId = review.getRecommendedProposalId();
        Assert.isTrue(StringUtils.isNotBlank(recommendedId) && plan.getProposals() != null
                        && plan.getProposals().stream().anyMatch(proposal -> proposal != null
                                && recommendedId.equals(proposal.proposalId()))
                        && review.getProposalReviews() != null
                        && review.getProposalReviews().stream().anyMatch(item -> item != null
                                && recommendedId.equals(item.proposalId()) && item.eligibleForRecommendation()),
                () -> TripExceptions.system("已发布方案的推荐结果与审核不一致"));
        if (!StringUtils.equals(planId, plan.getPlanId())) {
            return null;
        }
        ItineraryPlanApplicationService.requireOrderItineraryMatchesPlan(order, plan);
        return pageStore.readPublished(published.getPlanHtmlUrl(), review.getReviewId());
    }

    private void requireConversation(String userId, String conversationId) {
        if (conversationDomainService.findByUseIdAndConversationId(userId, conversationId) == null) {
            throw TripExceptions.invalidRequest("当前用户没有访问该会话的权限");
        }
    }

    private TravelOrder requireOrder(String userId, String orderId) {
        TravelOrder order = StringUtils.isBlank(orderId) ? null : travelOrderDomainService.findByOrderIdAndUserId(orderId.trim(), userId);
        if (order == null) {
            throw TripExceptions.invalidRequest("当前用户没有访问该差旅单的权限");
        }
        return order;
    }
}
