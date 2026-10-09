package com.fons.cloud.ai.trip.infrastructure.render;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.dto.ItineraryPlanPageView;
import com.fons.cloud.ai.trip.common.dto.ItineraryProposalPageView;
import com.fons.cloud.ai.trip.common.dto.ItineraryPublicationSource;
import com.fons.cloud.ai.trip.common.dto.ItineraryTravelItemView;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.HotelOption;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Metrics;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.ProposalTag;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TransportOption;
import com.fons.cloud.ai.trip.common.response.ItineraryProposalReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewIssue;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 将已通过发布资格校验的规划和审核快照投影为页面数据。
 * 只格式化已有事实，不推断价格、房型、偏好或修复历史。
 *
 * @author hongqy
 */
@Component
public class ItineraryPlanPageViewAssembler {

    private static final String MISSING = "未提供";
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX");

    /**
     * 组装推荐方案及其他代表方案；审核方案缺失视为存储数据不一致。
     */
    public ItineraryPlanPageView assemble(ItineraryPublicationSource source) {
        if (source == null || source.planningResult() == null || source.reviewResult() == null
                || source.recommendedProposal() == null) {
            throw SystemIntervalException.of("行程页面缺少规划或审核数据");
        }
        ItineraryPlanningResult plan = source.planningResult();
        ItineraryReviewResult review = source.reviewResult();
        if (plan.getUserRequest() == null || plan.getProposals() == null || !StringUtils.equals(plan.getPlanId(), review.getPlanId())) {
            throw SystemIntervalException.of("行程页面的规划与审核数据不一致");
        }
        Map<String, ItineraryProposalReview> proposalReviews = review.getProposalReviews() == null
                ? Map.of() : review.getProposalReviews().stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.proposalId()))
                .collect(Collectors.toMap(ItineraryProposalReview::proposalId, Function.identity(), (first, ignored) -> first));
        Map<String, ItineraryReviewIssue> issues = review.getIssues() == null ? Map.of()
                : review.getIssues().stream()
                .filter(item -> item != null && StringUtils.isNotBlank(item.issueId()))
                .collect(Collectors.toMap(ItineraryReviewIssue::issueId, Function.identity(), (first, ignored) -> first));

        String recommendedId = source.recommendedProposal().proposalId();
        List<ItineraryProposalPageView> alternatives = new ArrayList<>();
        ItineraryProposalPageView recommended = null;
        for (Proposal proposal : plan.getProposals()) {
            if (proposal == null || StringUtils.isBlank(proposal.proposalId())) {
                throw SystemIntervalException.of("行程页面包含无效的代表方案");
            }
            ItineraryProposalReview proposalReview = proposalReviews.get(proposal.proposalId());
            if (proposalReview == null || proposalReview.verdict() == null) {
                throw SystemIntervalException.of("代表方案缺少审核结论：" + proposal.proposalId());
            }
            ItineraryProposalPageView view = toProposalView(plan, proposal, proposalReview, issues);
            if (proposal.proposalId().equals(recommendedId)) {
                recommended = view;
            } else {
                alternatives.add(view);
            }
        }
        if (recommended == null || !recommended.eligibleForRecommendation()) {
            throw SystemIntervalException.of("行程页面的推荐方案与审核结果不一致");
        }

        ItineraryPlanningResult.TripRequest request = plan.getUserRequest();
        String orderId = plan.getSourceTravelOrder() == null ? null
                : StringUtils.trimToNull(plan.getSourceTravelOrder().orderId());
        return new ItineraryPlanPageView(plan.getPlanId(), review.getReviewId(),
                display(request.origin()) + " → " + display(request.destination()),
                formatDate(request.departureDate()) + " 去 · " + formatDate(request.returnDate()) + " 返",
                orderId, display(review.getSummary()), StringUtils.trimToNull(plan.getWeatherSummary()),
                formatDateTime(plan.getGeneratedAt()), formatDateTime(review.getReviewedAt()),
                recommended, List.copyOf(alternatives));
    }

    private ItineraryProposalPageView toProposalView(ItineraryPlanningResult plan, Proposal proposal,
                                                     ItineraryProposalReview review,
                                                     Map<String, ItineraryReviewIssue> issues) {
        Metrics metrics = proposal.metrics();
        String currency = plan.getCurrency();
        List<String> issueMessages = review.issueIds().stream()
                .map(issues::get)
                .map(issue -> issue == null ? "审核问题详情未提供" : display(issue.message()))
                .distinct().toList();
        List<String> tags = proposal.tags() == null ? List.of() : proposal.tags().stream()
                .filter(tag -> tag != null && (tag != ProposalTag.PREFERENCE_BEST
                        || StringUtils.isNotBlank(plan.getPreferences())))
                .map(ProposalTag::getLabel).distinct().toList();
        String score = proposal.scores() == null ? MISSING : formatNumber(proposal.scores().overall());
        return new ItineraryProposalPageView(proposal.proposalId(), review.verdict().getLabel(),
                verdictStyle(review.verdict()), review.eligibleForRecommendation(), tags,
                formatMoney(metrics == null ? null : metrics.totalPrice(), currency), score,
                metrics == null ? MISSING : formatDuration(metrics.totalTransitMinutes()),
                List.of(transportView("去程交通", proposal.outbound(), currency),
                        hotelView(proposal.hotel(), metrics, currency),
                        transportView("返程交通", proposal.inbound(), currency)),
                issueMessages, notes(proposal.policyViolations()), notes(proposal.warnings()),
                notes(proposal.experienceFlags()));
    }

    private ItineraryTravelItemView transportView(String title, TransportOption option, String currency) {
        if (option == null) {
            return new ItineraryTravelItemView(title, MISSING, MISSING, MISSING, MISSING, MISSING);
        }
        String type = option.type() == null ? "交通" : option.type().getLabel();
        String headline = type + " · " + display(option.code());
        String location = display(option.origin()) + " → " + display(option.destination());
        String schedule = formatDateTime(option.departureTime()) + " → " + formatDateTime(option.arrivalTime());
        String details = "承运方：" + display(option.carrier()) + " · 舱位/席别：" + display(option.cabinClass())
                + " · 退改：" + display(option.refundPolicy());
        return new ItineraryTravelItemView(title, headline, location, schedule, details,
                formatMoney(option.price(), currency));
    }

    private ItineraryTravelItemView hotelView(HotelOption hotel, Metrics metrics, String currency) {
        if (hotel == null) {
            return new ItineraryTravelItemView("住宿", MISSING, MISSING, MISSING, MISSING, MISSING);
        }
        String location = display(hotel.city()) + " · " + display(hotel.roomType());
        String schedule = formatDate(hotel.checkInDate()) + " 入住 → " + formatDate(hotel.checkOutDate())
                + " 离店 · " + hotel.nights() + "晚";
        String breakfast = hotel.breakfastIncluded() == null ? MISSING
                : hotel.breakfastIncluded() ? "含早餐" : "不含早餐";
        String details = "每晚：" + formatMoney(hotel.pricePerNight(), currency)
                + " · 早餐：" + breakfast + " · 取消：" + display(hotel.cancelPolicy());
        return new ItineraryTravelItemView("住宿", display(hotel.name()), location, schedule, details,
                formatMoney(metrics == null ? null : metrics.hotelTotalPrice(), currency));
    }

    private String verdictStyle(ItineraryReviewVerdict verdict) {
        return switch (verdict) {
            case PASS -> "pass";
            case WARNING -> "warning";
            case BLOCKED -> "blocked";
        };
    }

    private List<String> notes(List<String> values) {
        return values == null ? List.of() : values.stream().map(StringUtils::trimToNull).filter(Objects::nonNull).distinct().toList();
    }

    private String display(String value) {
        return StringUtils.isBlank(value) ? MISSING : value.trim();
    }

    private String formatDate(LocalDate date) {
        return date == null ? MISSING : date.toString();
    }

    private String formatDateTime(OffsetDateTime dateTime) {
        return dateTime == null ? MISSING : DATE_TIME_FORMAT.format(dateTime);
    }

    private String formatMoney(BigDecimal amount, String currency) {
        if (amount == null) {
            return MISSING;
        }
        String value = formatNumber(amount);
        if ("CNY".equalsIgnoreCase(currency)) {
            return "¥" + value;
        }
        return StringUtils.isBlank(currency) ? value + "（币种未提供）" : currency + " " + value;
    }

    private String formatNumber(BigDecimal value) {
        return value == null ? MISSING : value.stripTrailingZeros().toPlainString();
    }

    private String formatDuration(long minutes) {
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return hours == 0 ? remainder + "分钟" : hours + "小时" + (remainder == 0 ? "" : remainder + "分钟");
    }
}
