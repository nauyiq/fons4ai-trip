package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.application.itinerary.ItinerarySearchApplicationService;
import com.fons.cloud.ai.trip.common.constants.FlightSearchMode;
import com.fons.cloud.ai.trip.common.constants.ItinerarySearchProvider;
import com.fons.cloud.ai.trip.common.constants.TrainSearchSort;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.HotelCandidate;
import com.fons.cloud.ai.trip.common.dto.SearchPriceRange;
import com.fons.cloud.ai.trip.common.dto.SearchTimeRange;
import com.fons.cloud.ai.trip.common.dto.TransportCandidate;
import com.fons.cloud.ai.trip.common.request.FlightSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelRoomSearchRequest;
import com.fons.cloud.ai.trip.common.request.SearchPageRequest;
import com.fons.cloud.ai.trip.common.request.TrainSearchRequest;
import com.fons.cloud.ai.trip.common.response.ItinerarySearchResult;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * 行程实时搜索工具集。模型只提供业务搜索条件，供应商、凭据和候选归属均由服务端确定。
 * 搜索结果由应用服务转换为Trip标准候选并保存到当前用户、当前会话的候选池。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItinerarySearchTools implements BaseTool {

    public static final List<String> TOOLS = List.of("search_flights", "search_trains", "search_hotels", "search_hotel_rooms");
    private static final ItinerarySearchProvider SEARCH_PROVIDER = ItinerarySearchProvider.TU_NIU;

    private final ItinerarySearchApplicationService itinerarySearchApplicationService;

    @Tool(name = "search_flights", description = "搜索国内单程机票并返回当前页的Trip标准候选，结果会自动保存到当前会话候选池。"
            + "SUCCESS表示本次搜索完成；候选看data.candidates，翻页看data.pagination。"
            + "翻页时必须保留原搜索条件，并传上一页返回的continuationToken（如有）。")
    public R<ItinerarySearchResult<TransportCandidate>> searchFlights(
            RuntimeContext context,
            @ToolParam(name = "origin", description = "出发城市，必填，如上海") String origin,
            @ToolParam(name = "destination", description = "到达城市，必填，不能与出发城市相同") String destination,
            @ToolParam(name = "departure_date", description = "出发日期，YYYY-MM-DD") String departureDate,
            @ToolParam(name = "mode", description = "搜索方式：LOWEST_PRICE低价、TIME_RANGE时间范围、PRICE_RANGE价格范围、NEAR_DEPARTURE附近出发机场、NEAR_ARRIVAL附近到达机场、TRANSFER中转；不传默认LOWEST_PRICE", required = false) String mode,
            @ToolParam(name = "departure_time_start", description = "出发时间范围起点，HH:mm；仅TIME_RANGE使用", required = false) String departureTimeStart,
            @ToolParam(name = "departure_time_end", description = "出发时间范围终点，HH:mm；仅TIME_RANGE使用", required = false) String departureTimeEnd,
            @ToolParam(name = "arrival_time_start", description = "到达时间范围起点，HH:mm；仅TIME_RANGE使用", required = false) String arrivalTimeStart,
            @ToolParam(name = "arrival_time_end", description = "到达时间范围终点，HH:mm；仅TIME_RANGE使用", required = false) String arrivalTimeEnd,
            @ToolParam(name = "minimum_price", description = "最低价格，人民币元；仅PRICE_RANGE使用，需与maximum_price同时提供", required = false) BigDecimal minimumPrice,
            @ToolParam(name = "maximum_price", description = "最高价格，人民币元；仅PRICE_RANGE使用，需与minimum_price同时提供", required = false) BigDecimal maximumPrice,
            @ToolParam(name = "page_number", description = "页码，从1开始；不传表示首页", required = false) Integer pageNumber,
            @ToolParam(name = "continuation_token", description = "上一页data.pagination.continuationToken；首页不要传", required = false) String continuationToken) {
        String userId = context.getUserId();
        String conversationId = context.getSessionId();
        log.info("[TOOL][search_flights] userId={}, conversationId={}, origin={}, destination={}, departureDate={}, pageNumber={}",
                userId, conversationId, origin, destination, departureDate, pageNumber);

        try {
            CandidateOwner owner = candidateOwner(userId, conversationId);
            FlightSearchRequest request = FlightSearchRequest.builder()
                    .origin(StringUtils.trimToNull(origin))
                    .destination(StringUtils.trimToNull(destination))
                    .departureDate(parseDate(departureDate, "departure_date"))
                    .mode(parseEnum(mode, FlightSearchMode.class, "mode"))
                    .departureTimeRange(parseTimeRange(departureTimeStart, departureTimeEnd, "departure_time"))
                    .arrivalTimeRange(parseTimeRange(arrivalTimeStart, arrivalTimeEnd, "arrival_time"))
                    .priceRange(priceRange(minimumPrice, maximumPrice))
                    .page(pageRequest(pageNumber, continuationToken))
                    .build();
            ItinerarySearchResult<TransportCandidate> result = itinerarySearchApplicationService.searchFlights(
                    owner, SEARCH_PROVIDER, request);
            return success("机票", result);
        } catch (BusinessRuntimeException e) {
            return businessFailure("search_flights", userId, e);
        } catch (Exception e) {
            log.error("[TOOL][search_flights] 机票搜索失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "机票搜索失败，不能据此判断没有可用航班，请稍后重试。");
        }
    }

    @Tool(name = "search_trains", description = "搜索单程火车票并返回当前页候选；同一车次的不同席别可能是不同候选，结果会自动保存。"
            + "SUCCESS表示本次搜索完成；候选看data.candidates，翻页看data.pagination。"
            + "翻页必须保留原条件并传上一页的continuationToken。")
    public R<ItinerarySearchResult<TransportCandidate>> searchTrains(
            RuntimeContext context,
            @ToolParam(name = "origin", description = "出发城市，必填，如上海") String origin,
            @ToolParam(name = "destination", description = "到达城市，必填，不能与出发城市相同") String destination,
            @ToolParam(name = "departure_date", description = "出发日期，YYYY-MM-DD") String departureDate,
            @ToolParam(name = "sort", description = "排序：DEPARTURE_ASC、DEPARTURE_DESC、DURATION_ASC、DURATION_DESC、PRICE_ASC、PRICE_DESC；不传默认PRICE_ASC", required = false) String sort,
            @ToolParam(name = "departure_time_start", description = "出发时间范围起点，HH:mm；与departure_time_end同时提供", required = false) String departureTimeStart,
            @ToolParam(name = "departure_time_end", description = "出发时间范围终点，HH:mm；与departure_time_start同时提供", required = false) String departureTimeEnd,
            @ToolParam(name = "arrival_time_start", description = "到达时间范围起点，HH:mm；与arrival_time_end同时提供", required = false) String arrivalTimeStart,
            @ToolParam(name = "arrival_time_end", description = "到达时间范围终点，HH:mm；与arrival_time_start同时提供", required = false) String arrivalTimeEnd,
            @ToolParam(name = "page_number", description = "页码，从1开始；不传表示首页", required = false) Integer pageNumber,
            @ToolParam(name = "continuation_token", description = "上一页data.pagination.continuationToken；首页不要传", required = false) String continuationToken) {
        String userId = context.getUserId();
        String conversationId = context.getSessionId();
        log.info("[TOOL][search_trains] userId={}, conversationId={}, origin={}, destination={}, departureDate={}, pageNumber={}",
                userId, conversationId, origin, destination, departureDate, pageNumber);

        try {
            CandidateOwner owner = candidateOwner(userId, conversationId);
            TrainSearchRequest request = TrainSearchRequest.builder()
                    .origin(StringUtils.trimToNull(origin))
                    .destination(StringUtils.trimToNull(destination))
                    .departureDate(parseDate(departureDate, "departure_date"))
                    .sort(parseEnum(sort, TrainSearchSort.class, "sort"))
                    .departureTimeRange(parseTimeRange(departureTimeStart, departureTimeEnd, "departure_time"))
                    .arrivalTimeRange(parseTimeRange(arrivalTimeStart, arrivalTimeEnd, "arrival_time"))
                    .page(pageRequest(pageNumber, continuationToken))
                    .build();
            ItinerarySearchResult<TransportCandidate> result = itinerarySearchApplicationService.searchTrains(
                    owner, SEARCH_PROVIDER, request);
            return success("火车票", result);
        } catch (BusinessRuntimeException e) {
            return businessFailure("search_trains", userId, e);
        } catch (Exception e) {
            log.error("[TOOL][search_trains] 火车票搜索失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "火车票搜索失败，不能据此判断没有可用车次，请稍后重试。");
        }
    }

    @Tool(name = "search_hotels", description = "搜索目的城市单间酒店并返回当前页Trip标准候选，结果会自动保存到当前会话候选池。"
            + "列表候选是起价，不能直接用于plan_itinerary；选定酒店后须用source.itemId调用search_hotel_rooms取得具体报价。"
            + "翻页必须保留原条件并传上一页的continuationToken。")
    public R<ItinerarySearchResult<HotelCandidate>> searchHotels(
            RuntimeContext context,
            @ToolParam(name = "city", description = "酒店所在城市，必填") String city,
            @ToolParam(name = "check_in_date", description = "入住日期，YYYY-MM-DD") String checkInDate,
            @ToolParam(name = "check_out_date", description = "离店日期，YYYY-MM-DD，必须晚于入住日期") String checkOutDate,
            @ToolParam(name = "keyword", description = "可选酒店名称、商圈或关键词", required = false) String keyword,
            @ToolParam(name = "destination_location", description = "可选到访地点，用于搜索附近酒店，不是另一个城市", required = false) String destinationLocation,
            @ToolParam(name = "minimum_price", description = "最低价格，人民币元；需与maximum_price同时提供", required = false) BigDecimal minimumPrice,
            @ToolParam(name = "maximum_price", description = "最高价格，人民币元；需与minimum_price同时提供", required = false) BigDecimal maximumPrice,
            @ToolParam(name = "adult_count", description = "成人数，大于0；不传默认2人", required = false) Integer adultCount,
            @ToolParam(name = "child_ages", description = "儿童年龄列表，如[5,8]；没有儿童时不传或传空列表", required = false) List<Integer> childAges,
            @ToolParam(name = "page_number", description = "页码，从1开始；不传表示首页", required = false) Integer pageNumber,
            @ToolParam(name = "continuation_token", description = "上一页data.pagination.continuationToken；首页不要传", required = false) String continuationToken) {
        String userId = context.getUserId();
        String conversationId = context.getSessionId();
        log.info("[TOOL][search_hotels] userId={}, conversationId={}, city={}, checkInDate={}, checkOutDate={}, pageNumber={}",
                userId, conversationId, city, checkInDate, checkOutDate, pageNumber);

        try {
            CandidateOwner owner = candidateOwner(userId, conversationId);
            HotelSearchRequest request = HotelSearchRequest.builder()
                    .city(StringUtils.trimToNull(city))
                    .checkInDate(parseDate(checkInDate, "check_in_date"))
                    .checkOutDate(parseDate(checkOutDate, "check_out_date"))
                    .keyword(StringUtils.trimToNull(keyword))
                    .destinationLocation(StringUtils.trimToNull(destinationLocation))
                    .priceRange(priceRange(minimumPrice, maximumPrice))
                    .adultCount(adultCount)
                    .childAges(childAges)
                    .page(pageRequest(pageNumber, continuationToken))
                    .build();
            ItinerarySearchResult<HotelCandidate> result = itinerarySearchApplicationService.searchHotels(
                    owner, SEARCH_PROVIDER, request);
            return success("酒店", result);
        } catch (BusinessRuntimeException e) {
            return businessFailure("search_hotels", userId, e);
        } catch (Exception e) {
            log.error("[TOOL][search_hotels] 酒店搜索失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "酒店搜索失败，不能据此判断没有可用酒店，请稍后重试。");
        }
    }

    @Tool(name = "search_hotel_rooms", description = "查询指定酒店的具体房型报价。必须先调用search_hotels，"
            + "再把所选酒店候选source.itemId作为hotel_id传入。每个房型报价方案会生成独立候选并自动保存，"
            + "成功返回的确定单间每晚报价可供plan_itinerary计算；此工具不返回可长期复用的预订令牌。")
    public R<ItinerarySearchResult<HotelCandidate>> searchHotelRooms(
            RuntimeContext context,
            @ToolParam(name = "hotel_id", description = "search_hotels候选source.itemId中的酒店标识，必填") String hotelId,
            @ToolParam(name = "city", description = "酒店所在城市，须与原酒店搜索条件一致") String city,
            @ToolParam(name = "check_in_date", description = "入住日期，YYYY-MM-DD，须与原酒店搜索条件一致") String checkInDate,
            @ToolParam(name = "check_out_date", description = "离店日期，YYYY-MM-DD，须与原酒店搜索条件一致") String checkOutDate,
            @ToolParam(name = "adult_count", description = "成人数，须与原酒店搜索条件一致；不传默认2人", required = false) Integer adultCount,
            @ToolParam(name = "child_ages", description = "儿童年龄列表，须与原酒店搜索条件一致；没有儿童时不传或传空列表", required = false) List<Integer> childAges) {
        String userId = context.getUserId();
        String conversationId = context.getSessionId();
        log.info("[TOOL][search_hotel_rooms] userId={}, conversationId={}, hotelId={}, city={}, checkInDate={}, checkOutDate={}",
                userId, conversationId, hotelId, city, checkInDate, checkOutDate);

        try {
            CandidateOwner owner = candidateOwner(userId, conversationId);
            HotelRoomSearchRequest request = HotelRoomSearchRequest.builder()
                    .hotelItemId(StringUtils.trimToNull(hotelId))
                    .city(StringUtils.trimToNull(city))
                    .checkInDate(parseDate(checkInDate, "check_in_date"))
                    .checkOutDate(parseDate(checkOutDate, "check_out_date"))
                    .adultCount(adultCount)
                    .childAges(childAges)
                    .build();
            ItinerarySearchResult<HotelCandidate> result = itinerarySearchApplicationService.searchHotelRooms(
                    owner, SEARCH_PROVIDER, request);
            int count = result.candidates().size();
            String message = count == 0
                    ? "酒店房型报价查询完成，当前没有可用的具体房型报价。"
                    : "酒店房型报价查询完成，返回" + count + "个具体报价并已保存，可继续规划。";
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, result);
        } catch (BusinessRuntimeException e) {
            return businessFailure("search_hotel_rooms", userId, e);
        } catch (Exception e) {
            log.error("[TOOL][search_hotel_rooms] 酒店房型报价查询失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(),
                    "酒店房型报价查询失败，不能据此判断没有可用房型，请稍后重试。");
        }
    }

    private LocalDate parseDate(String value, String parameterName) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            throw parameterError(parameterName + " 不能为空");
        }
        try {
            return LocalDate.parse(normalized);
        } catch (DateTimeParseException e) {
            throw parameterError(parameterName + " 必须为YYYY-MM-DD格式的有效日期");
        }
    }

    private SearchTimeRange parseTimeRange(String start, String end, String parameterName) {
        String normalizedStart = StringUtils.trimToNull(start);
        String normalizedEnd = StringUtils.trimToNull(end);
        if (normalizedStart == null && normalizedEnd == null) {
            return null;
        }
        if (normalizedStart == null || normalizedEnd == null) {
            throw parameterError(parameterName + "_start 和 " + parameterName + "_end 必须同时提供");
        }
        try {
            return new SearchTimeRange(LocalTime.parse(normalizedStart), LocalTime.parse(normalizedEnd));
        } catch (DateTimeParseException e) {
            throw parameterError(parameterName + " 必须使用HH:mm格式的有效时间");
        }
    }

    private SearchPriceRange priceRange(BigDecimal minimum, BigDecimal maximum) {
        if (minimum == null && maximum == null) {
            return null;
        }
        if (minimum == null || maximum == null) {
            throw parameterError("minimum_price 和 maximum_price 必须同时提供");
        }
        return new SearchPriceRange(minimum, maximum);
    }

    private SearchPageRequest pageRequest(Integer pageNumber, String continuationToken) {
        String normalizedToken = StringUtils.trimToNull(continuationToken);
        if (pageNumber == null && normalizedToken == null) {
            return null;
        }
        return new SearchPageRequest(pageNumber, normalizedToken);
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType, String parameterName) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw parameterError(parameterName + " 不支持值：" + normalized);
        }
    }

    private <T> R<ItinerarySearchResult<T>> success(String label, ItinerarySearchResult<T> result) {
        int count = result.candidates().size();
        String message = count == 0
                ? label + "搜索完成，本页没有匹配候选，不代表搜索服务失败。"
                : label + "搜索完成，本页返回" + count + "个候选并已保存；是否继续翻页请查看data.pagination。";
        return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, result);
    }

    private <T> R<T> businessFailure(String toolName, String userId, BusinessRuntimeException e) {
        log.warn("[TOOL][{}] 搜索未完成，userId={}, code={}", toolName, userId, e.getCode());
        TripAgentToolResultCode code = ResultCode.PARAMS_ERROR.getCode().equals(e.getCode())
                ? TripAgentToolResultCode.INVALID_PARAM : TripAgentToolResultCode.INTERNAL_ERROR;
        return R.failed(code.getCode(), e.getMessage());
    }

    private CandidateOwner candidateOwner(String userId, String conversationId) {
        if (StringUtils.isBlank(userId)) {
            throw parameterError("user_id 不能为空");
        }
        if (StringUtils.isBlank(conversationId)) {
            throw parameterError("conversation_id 不能为空");
        }
        return new CandidateOwner(userId, conversationId);
    }

    private BusinessRuntimeException parameterError(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }
}
