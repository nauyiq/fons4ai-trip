package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewerType;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;

/**
 * 单个行程审核维度的统一契约。
 * 实现类可以是 Java 确定性规则或 LLM 评估适配器，但输出必须使用统一结构。
 *
 * @author hongqy
 */
public interface ItineraryDimensionReviewer {

    /**
     * 当前审核器负责的业务维度。
     */
    ItineraryReviewDimension dimension();

    /**
     * 当前审核器的实现方式，异常降级时仍用于说明审核来源。
     */
    default ItineraryReviewerType reviewerType() {
        return ItineraryReviewerType.DETERMINISTIC_RULE;
    }

    /**
     * 当前审核器版本，默认使用类名；需要稳定版本标识的实现应覆盖该方法。
     */
    default String reviewerVersion() {
        return getClass().getSimpleName();
    }

    /**
     * 审核不可变规划快照。
     *
     * @param planningResult 已保存的完整规划结果
     * @param context 本轮统一审核时间及应用层加载的可信业务上下文
     * @return 本维度结构化审核结果
     */
    ItineraryDimensionReviewResult review(ItineraryPlanningResult planningResult,
                                          ItineraryReviewContext context);
}
