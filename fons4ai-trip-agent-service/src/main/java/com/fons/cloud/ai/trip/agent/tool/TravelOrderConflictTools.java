package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.common.constants.ConflictSeverity;
import com.fons.cloud.ai.trip.common.constants.ConflictType;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.response.CheckTravelOrderConflictsResult;
import com.fons.cloud.ai.trip.common.response.CheckTravelOrderConflictsResult.ConflictItem;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.ai.trip.infrastructure.util.CityTransitTimeTools;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 行程冲突检测工具集。
 * 日期级检查沿用原业务规则：衔接时以前段目的地作为结束城市。
 * 未建模真实返回城市、到达时刻及交通班次，报告仅用于提示风险。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TravelOrderConflictTools implements BaseTool {
    public static final List<String> TOOLS = List.of("check_travel_order_conflicts");

    private final TravelOrderDomainService travelOrderDomainService;
    private final CityTransitTimeTools cityTransitTimeTools;

    /**
     * 同日跨城衔接：所需分钟数超过该值即视为“时间紧张”
     */
    private static final int SAME_DAY_TIGHT_MINUTES = 8 * 60;

    /**
     * 相邻日衔接：跨城交通不超过该分钟数即认为 1 天足够，不再告警（与同日阈值语义独立，可单独调整）
     */
    private static final int ADJACENT_DAY_MIN_TRANSIT_MINUTES = 8 * 60;

    /**
     * 同日衔接估算时长超过一天时，提示 HIGH 风险。
     */
    private static final int SAME_DAY_IMPOSSIBLE_MINUTES = 24 * 60;

    @Tool(name = "check_travel_order_conflicts", description = "提交申请前或修改城市、日期时，检查当前用户与生效申请（DRAFT/SUBMITTED/APPROVED）的日期重叠及同日、次日交通衔接风险。"
            + "工具成功不代表无冲突，需读取 data.hasConflict 和 conflicts；报告仅提供建议，不执行提交或自动阻断，交通时长为估算值。")
    public R<CheckTravelOrderConflictsResult> checkTravelOrderConflicts(RuntimeContext context,
            @ToolParam(name = "departure_city", description = "候选申请的出发城市，如北京，不使用机场或区县名称。") String departureCity,
            @ToolParam(name = "destination", description = "候选申请的目的地城市，如上海。") String destination,
            @ToolParam(name = "departure_date", description = "候选申请的有效出发日期，YYYY-MM-DD，不得晚于返回日期。") String departureDate,
            @ToolParam(name = "return_date", description = "候选申请的有效返回日期，YYYY-MM-DD。") String returnDate,
            @ToolParam(name = "exclude_order_id", description = "修改时排除目标差旅申请单号，避免与自身冲突；新申请不传。", required = false) String excludeOrderId) {
        String userId = context.getUserId();
        log.info("[TOOL][check_travel_order_conflicts] userId={}, departureCity={}, destination={}, departureDate={}, returnDate={}, excludeOrderId={}",
                userId, departureCity, destination, departureDate, returnDate, excludeOrderId);

        // 参数校验
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        if (StringUtils.isBlank(departureCity) || StringUtils.isBlank(destination)
                || StringUtils.isBlank(departureDate) || StringUtils.isBlank(returnDate)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "出发城市、目的地、出发日期和返回日期不能为空");
        }
        LocalDate candidateDep;
        LocalDate candidateRet;
        try {
            candidateDep = parseDate(departureDate);
            candidateRet = parseDate(returnDate);
        } catch (DateTimeParseException e) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), "出发日期和返回日期必须是有效日历日期，格式为 YYYY-MM-DD");
        }
        if (candidateDep.isAfter(candidateRet)) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), "出发日期不能晚于返回日期");
        }

        try {
            // 窗口两端各扩展一天，兼顾重叠及次日衔接；与普通列表的出发日期筛选不同
            List<TravelOrder> candidates = travelOrderDomainService.findActiveByUserIdAndDateRange(userId,
                    List.of(TravelOrderStatus.DRAFT, TravelOrderStatus.SUBMITTED, TravelOrderStatus.APPROVED),
                    candidateDep.minusDays(1).toString(), candidateRet.plusDays(1).toString());
            List<ConflictItem> conflicts = new ArrayList<>();
            for (TravelOrder existing : candidates) {
                if (StringUtils.isNotBlank(excludeOrderId) && excludeOrderId.equals(existing.getOrderId())) {
                    continue;
                }
                LocalDate existingDep;
                LocalDate existingRet;
                try {
                    existingDep = parseDate(existing.getDepartureDate());
                    existingRet = parseDate(existing.getReturnDate());
                } catch (DateTimeParseException e) {
                    log.warn("[TOOL][check_travel_order_conflicts] 已有差旅单日期无效，orderId={}", existing.getOrderId(), e);
                    return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "已有生效差旅单的日期无效，无法完成冲突检查，请核实单据数据。");
                }
                if (existingDep.isAfter(existingRet) || StringUtils.isBlank(existing.getDepartureCity())
                        || StringUtils.isBlank(existing.getDestination())) {
                    log.warn("[TOOL][check_travel_order_conflicts] 已有差旅单数据异常，orderId={}", existing.getOrderId());
                    return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "已有生效差旅单的城市或日期范围异常，无法完成冲突检查，请核实单据数据。");
                }
                detectConflicts(existing, existingDep, existingRet, departureCity, destination, candidateDep, candidateRet, conflicts);
            }
            conflicts.sort(Comparator.comparingInt((ConflictItem item) -> item.severity().getRank())
                    .thenComparing(item -> StringUtils.defaultString(item.orderId())));
            String summary = buildSummary(conflicts, departureCity, destination, departureDate, returnDate);
            CheckTravelOrderConflictsResult result = new CheckTravelOrderConflictsResult(
                    !conflicts.isEmpty(), conflicts.size(), List.copyOf(conflicts), summary);
            log.info("[TOOL][check_travel_order_conflicts] userId={}, conflicts={}", userId, conflicts.size());
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), TripAgentToolResultCode.SUCCESS.getMessage(), result);
        } catch (Exception e) {
            log.error("[TOOL][check_travel_order_conflicts] 冲突检查失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "差旅冲突检查失败，请稍后重试，不能据此判断没有冲突。");
        }
    }

    private void detectConflicts(TravelOrder existing, LocalDate existingDep, LocalDate existingRet,
            String candidateDepCity, String candidateDest, LocalDate candidateDep, LocalDate candidateRet,
            List<ConflictItem> conflicts) {
        // 先处理端点相接，避免将可行的同日衔接直接判为跨城重叠
        // 两张单日申请同一天时，两端同时相等，仍按重叠检测
        if (existingRet.equals(candidateDep) && !existingDep.equals(candidateRet)) {
            addSameDayConflict(existing, existing.getDestination(), candidateDepCity, conflicts);
            return;
        }
        if (existingDep.equals(candidateRet) && !existingRet.equals(candidateDep)) {
            addSameDayConflict(existing, candidateDest, existing.getDepartureCity(), conflicts);
            return;
        }
        if (!existingRet.isBefore(candidateDep) && !existingDep.isAfter(candidateRet)) {
            addOverlapConflict(existing, candidateDepCity, candidateDest, candidateDep, candidateRet, conflicts);
            return;
        }
        if (existingRet.plusDays(1).equals(candidateDep)) {
            addAdjacentDayConflict(existing, existing.getDestination(), candidateDepCity,
                    String.format("已有差旅在 %s 结束，本次差旅在次日 %s 出发", existingRet, candidateDep), conflicts);
            return;
        }
        if (candidateRet.plusDays(1).equals(existingDep)) {
            addAdjacentDayConflict(existing, candidateDest, existing.getDepartureCity(),
                    String.format("本次差旅在 %s 结束，已有差旅在次日 %s 出发", candidateRet, existingDep), conflicts);
        }
    }

    private void addOverlapConflict(TravelOrder existing, String candidateDepCity, String candidateDest,
            LocalDate candidateDep, LocalDate candidateRet, List<ConflictItem> conflicts) {
        boolean sameRoute = isSameCity(existing.getDepartureCity(), candidateDepCity)
                && isSameCity(existing.getDestination(), candidateDest);
        if (sameRoute) {
            conflicts.add(new ConflictItem(ConflictType.TIME_OVERLAP_SAME_CITY, ConflictSeverity.LOW,
                    existing.getOrderId(), buildOrderSummary(existing),
                    "已有差旅与本次申请的日期范围重叠，且出发城市、目的地相同，存在重复申请风险。",
                    "请核实是否为同一申请；需要继续时，应避免重复审批。"));
            return;
        }
        String description = String.format("已有差旅与本次 %s → %s（%s ~ %s）的日期范围重叠且路线不同，请核实是否影响同时出行。",
                candidateDepCity, candidateDest, candidateDep, candidateRet);
        if (isSameCity(existing.getDepartureCity(), candidateDest) && isSameCity(existing.getDestination(), candidateDepCity)) {
            description += "两张申请方向相反，可能为同一趟出行的往返拆单，请确认是否重复申请。";
        }
        conflicts.add(new ConflictItem(ConflictType.TIME_OVERLAP_DIFF_CITY, ConflictSeverity.HIGH,
                existing.getOrderId(), buildOrderSummary(existing), description,
                "建议调整本次申请的日期，或先修改、取消已有申请；不得直接忽略冲突。"));
    }

    private void addSameDayConflict(TravelOrder existing, String endCity, String startCity, List<ConflictItem> conflicts) {
        if (isSameCity(endCity, startCity)) {
            return;
        }
        int minutes = cityTransitTimeTools.estimateMinutes(endCity, startCity);
        ConflictSeverity severity;
        String suggestion;
        if (minutes > SAME_DAY_IMPOSSIBLE_MINUTES) {
            severity = ConflictSeverity.HIGH;
            suggestion = "估算时长超过一天，建议调整日期或城市，并核实实际交通方案。";
        } else if (minutes > SAME_DAY_TIGHT_MINUTES) {
            severity = ConflictSeverity.MEDIUM;
            suggestion = "同日跨城衔接时间非常紧张，建议改为次日出发或核实可行班次。";
        } else {
            severity = ConflictSeverity.MEDIUM;
            suggestion = "同日跨城仍需预留交通及缓冲时间，请核实实际出发、到达时刻。";
        }
        conflicts.add(new ConflictItem(ConflictType.TRANSIT_TOO_TIGHT, severity,
                existing.getOrderId(), buildOrderSummary(existing),
                String.format("同日需要从 %s 前往 %s，估算衔接时间约 %d 分钟；未查询真实班次。", endCity, startCity, minutes), suggestion));
    }

    private void addAdjacentDayConflict(TravelOrder existing, String endCity, String startCity,
            String relation, List<ConflictItem> conflicts) {
        if (isSameCity(endCity, startCity)) {
            return;
        }
        int minutes = cityTransitTimeTools.estimateMinutes(endCity, startCity);
        if (minutes <= ADJACENT_DAY_MIN_TRANSIT_MINUTES) {
            return;
        }
        conflicts.add(new ConflictItem(ConflictType.DISCONNECTED_ROUTE, ConflictSeverity.MEDIUM,
                existing.getOrderId(), buildOrderSummary(existing),
                String.format("%s，需要从 %s 前往 %s，估算衔接时间约 %d 分钟，次日衔接存在时间风险。", relation, endCity, startCity, minutes),
                "建议预留更多交通缓冲时间，并核实实际交通方案。"));
    }

    private String buildOrderSummary(TravelOrder order) {
        return String.format("%s → %s，%s ~ %s（%s）", order.getDepartureCity(), order.getDestination(),
                order.getDepartureDate(), order.getReturnDate(), order.getStatus() == null ? "-" : order.getStatus().getCode());
    }

    private String buildSummary(List<ConflictItem> conflicts, String departureCity, String destination,
            String departureDate, String returnDate) {
        if (conflicts.isEmpty()) {
            return String.format("按日期和交通时长估算规则，未发现与 %s → %s（%s ~ %s）的冲突；不代表真实交通班次可达。",
                    departureCity, destination, departureDate, returnDate);
        }
        long high = conflicts.stream().filter(item -> item.severity() == ConflictSeverity.HIGH).count();
        long medium = conflicts.stream().filter(item -> item.severity() == ConflictSeverity.MEDIUM).count();
        long low = conflicts.stream().filter(item -> item.severity() == ConflictSeverity.LOW).count();
        return String.format("检测到 %d 条冲突（HIGH=%d、MEDIUM=%d、LOW=%d），请结合原因和建议处理。", conflicts.size(), high, medium, low);
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.length() != 10) {
            throw new DateTimeParseException("日期必须使用 YYYY-MM-DD 格式", StringUtils.defaultString(date), 0);
        }
        return LocalDate.parse(date);
    }

    private boolean isSameCity(String first, String second) {
        return StringUtils.isNotBlank(first) && StringUtils.isNotBlank(second)
                && normalizeCity(first).equals(normalizeCity(second));
    }

    private String normalizeCity(String city) {
        String value = city.trim();
        if (value.endsWith("市") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }
}
