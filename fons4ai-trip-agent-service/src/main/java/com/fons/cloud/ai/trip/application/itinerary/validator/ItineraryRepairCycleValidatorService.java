package com.fons.cloud.ai.trip.application.itinerary.validator;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.ItineraryRepairStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.ItineraryRepairCycle;
import com.fons.cloud.ai.trip.common.dto.ItineraryScope;
import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryRepairCycleRepository;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 行程规划的修复停止规则。只管理当前任务的次数和状态，不参与单次方案评分与审核。
 *
 * @author hongqy
 */
@Service
@RequiredArgsConstructor
public class ItineraryRepairCycleValidatorService {

    /**
     * 首次规划之后最多再生成两次修复方案。
     */
    private static final int MAX_REPLAN_ATTEMPTS = 2;

    /**
     * 审核执行不完整时，同一方案最多重试一次审核。
     */
    private static final int MAX_REVIEW_RETRIES = 1;

    private final ItineraryRepairCycleRepository repository;

    /**
     * 规划前检查当前任务是否允许继续。失败的规划不消耗修复次数。
     */
    public void beforePlan(CandidateOwner owner, ItineraryPlanRequest request, String runId) {
        requireRunId(runId);
        // 1. 新会话任务直接规划；已结束的任务只能在下一轮用户请求中重新开始
        ItineraryRepairCycle cycle = repository.find(owner);
        if (cycle == null) {
            return;
        }
        boolean sameScope = ItineraryScope.from(request).equals(cycle.scope());
        if (!sameScope || cycle.status() == ItineraryRepairStatus.CLOSED) {
            Assert.isTrue(!runId.equals(cycle.runId()), () -> invalidState(
                    "当前轮次的规划流程已结束，不能通过重新调用规划工具重置修复次数"));
            return;
        }
        // 2. 当前方案必须先完成审核，等待用户输入时不能在本轮自行补全
        Assert.isTrue(cycle.status() != ItineraryRepairStatus.AWAITING_REVIEW,
                () -> invalidState("当前方案尚未审核，请先审核已返回的planId"));
        Assert.isTrue(cycle.status() != ItineraryRepairStatus.RETRY_REVIEW,
                () -> invalidState("当前方案需要重试审核，不能直接重新规划"));
        if (cycle.status() == ItineraryRepairStatus.INPUT_REQUIRED) {
            Assert.isTrue(!runId.equals(cycle.runId()),
                    () -> invalidState("请等待用户补充信息后再继续规划"));
        }
        // 3. 同一行程已完成两次修复后，禁止继续生成第三个修复方案
        Assert.isTrue(cycle.replanCount() < MAX_REPLAN_ATTEMPTS,
                () -> invalidState("当前行程已达到两次修复上限，请停止重新规划并说明现有审核结果"));
    }

    /**
     * 仅在完整方案保存成功后记录规划次数，并等待审核该方案。
     */
    public void afterPlan(CandidateOwner owner, ItineraryPlanRequest request,
                          String runId, ItineraryPlanningResult result) {
        ItineraryRepairCycle previous = repository.find(owner);
        ItineraryScope scope = ItineraryScope.from(request);
        boolean repair = previous != null && scope.equals(previous.scope())
                && previous.status() != ItineraryRepairStatus.CLOSED;
        int replanCount = repair ? previous.replanCount() + 1 : 0;
        repository.save(owner, new ItineraryRepairCycle(scope, result.getPlanId(), runId,
                replanCount, 0, ItineraryRepairStatus.AWAITING_REVIEW));
    }

    /**
     * 只审核当前任务的方案，避免旧方案结果改变正在执行的修复流程。
     */
    public void beforeReview(CandidateOwner owner, String planId, String runId) {
        requireRunId(runId);
        ItineraryRepairCycle cycle = repository.find(owner);
        if (cycle == null) {
            return;
        }
        Assert.isTrue(planId.equals(cycle.planId()),
                () -> invalidState("请审核当前规划工具返回的planId，不能审核旧方案"));
        boolean pending = cycle.status() == ItineraryRepairStatus.AWAITING_REVIEW
                || cycle.status() == ItineraryRepairStatus.RETRY_REVIEW;
        boolean afterUserInput = cycle.status() == ItineraryRepairStatus.INPUT_REQUIRED
                && !runId.equals(cycle.runId());
        Assert.isTrue(pending || afterUserInput,
                () -> invalidState("当前方案不处于待审核状态，请按上次审核的nextAction继续"));
    }

    /**
     * 在审核动作上应用次数上限，计算待保存状态，不提前推进流程。
     * 警告且有推荐方案的 PROCEED 不触发修复。
     *
     * @return 审核报告保存成功后应写入的修复状态
     */
    public ItineraryRepairCycle resolveAfterReview(CandidateOwner owner, ItineraryPlanningResult planningResult,
                                                   String runId, ItineraryReviewResult result) {
        // 1. 加载当前任务；旧规划首次审核时补建最小修复状态
        ItineraryRepairCycle cycle = repository.find(owner);
        if (cycle == null) {
            // 兼容启用修复状态前已保存的规划，按首次规划承接其审核。
            Assert.isTrue(planningResult.getUserRequest() != null,
                    () -> SystemIntervalException.of("旧规划缺少行程范围，无法建立修复状态"));
            cycle = new ItineraryRepairCycle(ItineraryScope.from(planningResult),
                    planningResult.getPlanId(), runId, 0, 0, ItineraryRepairStatus.AWAITING_REVIEW);
        }

        // 2. 对重规划和审核重试分别应用停止上限，不改变单次审核的事实与问题清单
        ItineraryReviewNextAction action = result.getNextAction();
        int reviewRetryCount = cycle.reviewRetryCount();
        if (action == ItineraryReviewNextAction.REPLAN && cycle.replanCount() >= MAX_REPLAN_ATTEMPTS) {
            action = ItineraryReviewNextAction.STOP_NO_FEASIBLE_PROPOSAL;
            result.setNextAction(action);
            result.setSummary(result.getSummary() + "；已完成两次修复规划，仍无可推荐方案，停止自动重规划");
        } else if (action == ItineraryReviewNextAction.RETRY_REVIEW) {
            if (reviewRetryCount >= MAX_REVIEW_RETRIES) {
                action = ItineraryReviewNextAction.STOP_REVIEW_INCOMPLETE;
                result.setNextAction(action);
                result.setSummary(result.getSummary() + "；同一方案审核重试后仍未完成，停止自动重试");
            } else {
                reviewRetryCount++;
            }
        }

        // 3. 计算有效下一步动作对应的状态，等待审核报告保存成功后再提交
        ItineraryRepairStatus status = switch (action) {
            case REPLAN -> ItineraryRepairStatus.REPLAN_ALLOWED;
            case RETRY_REVIEW -> ItineraryRepairStatus.RETRY_REVIEW;
            case REQUEST_USER_INPUT -> ItineraryRepairStatus.INPUT_REQUIRED;
            default -> ItineraryRepairStatus.CLOSED;
        };
        return new ItineraryRepairCycle(cycle.scope(), cycle.planId(), runId,
                cycle.replanCount(), reviewRetryCount, status);
    }

    /**
     * 审核报告保存成功后提交修复状态。
     */
    public void saveResolvedReview(CandidateOwner owner, ItineraryRepairCycle cycle) {
        repository.save(owner, cycle);
    }

    private void requireRunId(String runId) {
        Assert.isTrue(StringUtils.isNotBlank(runId),
                () -> SystemIntervalException.of("运行时上下文缺少可信runId，无法限制规划修复次数"));
    }

    private BusinessRuntimeException invalidState(String message) {
        return BusinessRuntimeException.of(ResultCode.ILLEGAL_REQUEST_LIMITED.getCode(), message);
    }
}
