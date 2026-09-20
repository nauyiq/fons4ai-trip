package com.fons.cloud.ai.trip.infrastructure.client;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.FlightSearchMode;
import com.fons.cloud.ai.trip.common.constants.ItinerarySearchProvider;
import com.fons.cloud.ai.trip.common.constants.TrainSearchSort;
import com.fons.cloud.ai.trip.common.dto.HotelCandidate;
import com.fons.cloud.ai.trip.common.dto.SearchPagination;
import com.fons.cloud.ai.trip.common.dto.SearchPriceRange;
import com.fons.cloud.ai.trip.common.dto.SearchTimeRange;
import com.fons.cloud.ai.trip.common.dto.TransportCandidate;
import com.fons.cloud.ai.trip.common.request.FlightSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelRoomSearchRequest;
import com.fons.cloud.ai.trip.common.request.SearchPageRequest;
import com.fons.cloud.ai.trip.common.request.TrainSearchRequest;
import com.fons.cloud.ai.trip.common.response.ItinerarySearchResult;
import com.fons.cloud.ai.trip.infrastructure.client.api.ItinerarySearchClient;
import com.fons.cloud.ai.trip.infrastructure.client.model.tuniu.*;
import com.fons.cloud.ai.trip.infrastructure.config.properties.TripMcpConfigProperties;
import com.fons.cloud.ai.trip.infrastructure.config.properties.TripMcpConfigProperties.TuniuMcpConfig;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 行程搜索策略的途牛实现，公共接口只接收Trip请求并返回Trip标准候选。
 * 内部封装国内机票、火车票及酒店的MCP协议、供应商对象转换及分页续查。
 * 从企业服务配置获取API Key，每次调用独立创建并关闭MCP连接，不共享搜索会话状态。
 * 不负责凭据查询、候选存储、R封装或Agent工具注册；同步调用应在允许阻塞的工作线程执行。
 *
 * @author hongqy
 */
@Slf4j
@Component
public class TuniuSearchClient implements ItinerarySearchClient {

    private static final String API_KEY_HEADER = "apiKey";
    private static final int FIRST_PAGE = 1;
    private static final int MIN_NEXT_PAGE = 2;
    private static final int DEFAULT_ADULT_COUNT = 2;
    private static final int DEFAULT_ROOM_COUNT = 1;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final SearchService flightService;
    private final SearchService trainService;
    private final SearchService hotelService;
    private final SearchService hotelDetailService;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration initialTimeout;
    private final String apiKey;
    private final TuniuSearchConverter converter = new TuniuSearchConverter();

    /** 固化企业服务配置，不在启动阶段访问供应商，凭据不进入Agent上下文。 */
    public TuniuSearchClient(TripMcpConfigProperties properties) {
        TuniuMcpConfig config = properties.getTuniu();
        Assert.notNull(config, () -> SystemIntervalException.of("途牛MCP配置不能为空"));
        this.apiKey = StringUtils.trimToEmpty(config.getApiKey());
        Assert.isTrue(apiKey.chars().noneMatch(c -> Character.isWhitespace(c)
                        || Character.isSpaceChar(c) || Character.isISOControl(c)),
                () -> SystemIntervalException.of("企业途牛API Key不能包含内部空白或控制字符"));
        this.flightService = createService(config.getFlightEndpoint(), config.getFlightSearchTool(), "机票", "successCode", "data");
        this.trainService = createService(config.getTrainEndpoint(), config.getTrainSearchTool(), "火车票", "successCode", "data");
        this.hotelService = createService(config.getHotelEndpoint(), config.getHotelSearchTool(), "酒店", "success", "hotels");
        this.hotelDetailService = createService(config.getHotelEndpoint(), config.getHotelDetailTool(),
                "酒店房型报价", null, "roomTypes");
        this.connectTimeout = toTimeout(config.getConnectTimeoutSeconds(), "连接");
        this.requestTimeout = toTimeout(config.getRequestTimeoutSeconds(), "工具请求");
        this.initialTimeout = toTimeout(config.getInitialTimeoutSeconds(), "初始化");
    }

    @Override
    public ItinerarySearchProvider getProvider() {
        return ItinerarySearchProvider.TU_NIU;
    }

    /** 搜索国内机票，公共接口只接收Trip条件；供应商翻页仍携带原始搜索条件。 */
    @Override
    public ItinerarySearchResult<TransportCandidate> searchFlights(FlightSearchRequest request) {
        Assert.notNull(request, () -> parameterError("机票搜索参数不能为空"));
        validateRoute(request.origin(), request.destination(), request.departureDate());
        FlightSearchMode mode = request.mode() == null ? FlightSearchMode.LOWEST_PRICE : request.mode();
        String departureTime = toTimeRange(request.departureTimeRange(), "出发");
        String arrivalTime = toTimeRange(request.arrivalTimeRange(), "到达");
        String priceRange = toPriceRange(request.priceRange());
        Assert.isTrue((departureTime == null && arrivalTime == null || mode == FlightSearchMode.TIME_RANGE)
                        && (priceRange == null || mode == FlightSearchMode.PRICE_RANGE),
                () -> parameterError("时间范围仅适用于指定时间搜索，价格范围仅适用于指定价格搜索"));
        Assert.isTrue(mode != FlightSearchMode.TIME_RANGE || departureTime != null || arrivalTime != null,
                () -> parameterError("指定时间搜索至少需要一个时间范围"));
        Assert.isTrue(mode != FlightSearchMode.PRICE_RANGE || priceRange != null,
                () -> parameterError("指定价格搜索需要价格范围"));
        TuniuFlightSearchRequest supplierRequest = new TuniuFlightSearchRequest(request.origin().trim(), request.destination().trim(),
                request.departureDate(), toFlightSearchType(mode), departureTime, arrivalTime, priceRange, null);
        JSONObject arguments = toArguments(supplierRequest);
        String criteriaId = criteriaId(arguments);
        int pageNumber = pageNumber(request.page());
        validateCursor(request.page(), BookingType.FLIGHT, criteriaId, false);
        if (pageNumber >= MIN_NEXT_PAGE) {
            arguments.put("pageNum", pageNumber);
        }
        TuniuFlightSearchResponse response = search(flightService, arguments, TuniuFlightSearchResponse.class);
        return converter.convertFlights(response, request, toPagination(pageNumber, response.totalPageNum(),
                response.queryId(), BookingType.FLIGHT, criteriaId));
    }

    /** 火车首页与翻页使用同一Trip请求；后续页向途牛仅发送查询ID和页码。 */
    @Override
    public ItinerarySearchResult<TransportCandidate> searchTrains(TrainSearchRequest request) {
        Assert.notNull(request, () -> parameterError("火车票搜索参数不能为空"));
        validateRoute(request.origin(), request.destination(), request.departureDate());
        TrainSearchSort sort = request.sort() == null ? TrainSearchSort.PRICE_ASC : request.sort();
        TuniuTrainSearchRequest supplierRequest = new TuniuTrainSearchRequest(request.origin().trim(), request.destination().trim(),
                request.departureDate(), TuniuTrainSearchRequest.SearchType.valueOf(sort.name()),
                toTimeRange(request.departureTimeRange(), "出发"), toTimeRange(request.arrivalTimeRange(), "到达"));
        JSONObject criteria = toArguments(supplierRequest);
        String criteriaId = criteriaId(criteria);
        int pageNumber = pageNumber(request.page());
        String queryId = validateCursor(request.page(), BookingType.TRAIN, criteriaId, true);
        JSONObject arguments = pageNumber == FIRST_PAGE ? criteria : toArguments(new TuniuSearchPageRequest(queryId, pageNumber));
        TuniuTrainSearchResponse response = search(trainService, arguments, TuniuTrainSearchResponse.class);
        return converter.convertTrains(response, request, toPagination(pageNumber, response.totalPageNum(),
                StringUtils.defaultIfBlank(response.queryId(), queryId), BookingType.TRAIN, criteriaId));
    }

    /** 搜索酒店并转换为标准候选；最低价保留起价及未知计价口径，不直接当成每晚房价。 */
    @Override
    public ItinerarySearchResult<HotelCandidate> searchHotels(HotelSearchRequest request) {
        Assert.notNull(request, () -> parameterError("酒店搜索参数不能为空"));
        Assert.notBlank(request.city(), () -> parameterError("酒店搜索城市不能为空"));
        Assert.isTrue(request.checkInDate() != null && request.checkOutDate() != null
                        && request.checkOutDate().isAfter(request.checkInDate())
                        && ChronoUnit.DAYS.between(request.checkInDate(), request.checkOutDate()) <= Integer.MAX_VALUE,
                () -> parameterError("酒店入住、离店日期必填，且离店日期必须晚于入住日期"));
        int adultCount = request.adultCount() == null ? DEFAULT_ADULT_COUNT : request.adultCount();
        Assert.isTrue(adultCount > 0,
                () -> parameterError("酒店入住成人数必须大于0"));
        List<Integer> childAges = request.childAges() == null ? List.of() : request.childAges();
        Assert.isTrue(childAges.stream().allMatch(age -> age != null && age >= 0),
                () -> parameterError("儿童年龄不能为空或负数"));
        TuniuHotelSearchRequest supplierRequest = new TuniuHotelSearchRequest(request.city().trim(), request.checkInDate(), request.checkOutDate(),
                request.keyword(), request.destinationLocation(), toPriceRange(request.priceRange()), adultCount,
                childAges.size(), childAges.stream().sorted().toList());
        JSONObject criteria = toArguments(supplierRequest);
        String criteriaId = criteriaId(criteria);
        int pageNumber = pageNumber(request.page());
        String queryId = validateCursor(request.page(), BookingType.HOTEL, criteriaId, true);
        JSONObject arguments = pageNumber == FIRST_PAGE ? criteria : toArguments(new TuniuSearchPageRequest(queryId, pageNumber));
        TuniuHotelSearchResponse response = search(hotelService, arguments, TuniuHotelSearchResponse.class);
        Assert.isTrue(response.currentPageNum() == null || response.currentPageNum() == pageNumber,
                () -> SystemIntervalException.of("途牛酒店返回页码与请求不一致"));
        return converter.convertHotels(response, request, adultCount, toPagination(pageNumber, response.totalPageNum(),
                StringUtils.defaultIfBlank(response.queryId(), queryId), BookingType.HOTEL, criteriaId));
    }

    /** 查询指定酒店的一间房型报价；每个房型报价方案转换成独立Trip候选。 */
    @Override
    public ItinerarySearchResult<HotelCandidate> searchHotelRooms(HotelRoomSearchRequest request) {
        Assert.notNull(request, () -> parameterError("酒店房型报价搜索参数不能为空"));
        Assert.notBlank(request.hotelItemId(), () -> parameterError("酒店标识不能为空"));
        Assert.notBlank(request.city(), () -> parameterError("酒店所在城市不能为空"));
        Assert.isTrue(request.checkInDate() != null && request.checkOutDate() != null
                        && request.checkOutDate().isAfter(request.checkInDate())
                        && ChronoUnit.DAYS.between(request.checkInDate(), request.checkOutDate()) <= Integer.MAX_VALUE,
                () -> parameterError("酒店入住、离店日期必填，且离店日期必须晚于入住日期"));
        int adultCount = request.adultCount() == null ? DEFAULT_ADULT_COUNT : request.adultCount();
        Assert.isTrue(adultCount > 0, () -> parameterError("酒店入住成人数必须大于0"));
        List<Integer> childAges = request.childAges() == null
                ? List.of() : request.childAges().stream().sorted().toList();
        Assert.isTrue(childAges.stream().allMatch(age -> age != null && age >= 0),
                () -> parameterError("儿童年龄不能为空或负数"));

        long hotelId;
        try {
            hotelId = Long.parseLong(request.hotelItemId().trim());
        } catch (NumberFormatException e) {
            throw parameterError("当前供应商酒店标识必须为正整数");
        }
        Assert.isTrue(hotelId > 0, () -> parameterError("当前供应商酒店标识必须为正整数"));
        TuniuHotelDetailRequest supplierRequest = new TuniuHotelDetailRequest(hotelId,
                request.checkInDate(), request.checkOutDate(), DEFAULT_ROOM_COUNT, adultCount,
                childAges.size(), childAges);
        TuniuHotelDetailResponse response = search(hotelDetailService, toArguments(supplierRequest),
                TuniuHotelDetailResponse.class);
        Assert.isTrue(response.hotelId() != null && response.hotelId() == hotelId
                        && StringUtils.isNotBlank(response.hotelName()),
                () -> SystemIntervalException.of("途牛酒店详情返回的酒店标识或名称无效"));
        return converter.convertHotelRooms(response, request, adultCount,
                new SearchPagination(FIRST_PAGE, FIRST_PAGE, false, null));
    }

    private <T> T search(SearchService service, JSONObject arguments, Class<T> responseType) {
        Assert.notBlank(apiKey, () -> SystemIntervalException.of("企业途牛搜索服务未配置凭据，请联系管理员"));
        McpSchema.CallToolResult result;
        try {
            URI endpoint = service.endpoint();
            // 显式拆分服务地址与端点，避免SDK默认/mcp路径覆盖供应商的/mcp/flight等路径。
            var transport = HttpClientStreamableHttpTransport.builder(endpoint.getScheme() + "://" + endpoint.getRawAuthority())
                    .endpoint(endpoint.getRawPath()).connectTimeout(connectTimeout)
                    .openConnectionOnStartup(false)
                    .customizeRequest(builder -> builder.header(API_KEY_HEADER, apiKey)).build();
            try (McpSyncClient client = McpClient.sync(transport).requestTimeout(requestTimeout)
                    .initializationTimeout(initialTimeout).enableCallToolSchemaCaching(false).build()) {
                client.initialize();
                result = client.callTool(new McpSchema.CallToolRequest(service.toolName(), arguments));
            }
        } catch (RuntimeException e) {
            // 不记录凭据、远端正文及异常堆栈，避免供应商回显认证信息。
            log.warn("[TuniuSearchClient] 途牛{}MCP调用失败，exceptionType={}", service.label(), e.getClass().getSimpleName());
            throw SystemIntervalException.of("途牛" + service.label() + "搜索服务调用失败，请联系管理员检查服务配置或状态");
        }
        Assert.isTrue(result != null && !Boolean.TRUE.equals(result.isError()),
                () -> SystemIntervalException.of("途牛" + service.label() + "搜索工具执行失败"));
        JSONObject payload = extractPayload(result, service);
        // 先判定供应商业务状态，再解析具体列表；错误正文可能与正常列表结构不同。
        Assert.isTrue(service.successField() == null || Boolean.TRUE.equals(payload.get(service.successField())),
                () -> SystemIntervalException.of("途牛" + service.label() + "搜索未返回成功状态"));
        Assert.isTrue(payload.get(service.itemsField()) instanceof List<?>,
                () -> SystemIntervalException.of("途牛" + service.label() + "搜索响应缺失结果数组"));
        Assert.isTrue(((List<?>) payload.get(service.itemsField())).stream().allMatch(item -> item instanceof JSONObject),
                () -> SystemIntervalException.of("途牛" + service.label() + "搜索结果条目格式无效"));
        try {
            T response = payload.toJavaObject(responseType);
            log.info("[TuniuSearchClient] 途牛{}搜索完成，count={}", service.label(), ((List<?>) payload.get(service.itemsField())).size());
            return response;
        } catch (JSONException e) {
            throw SystemIntervalException.of("途牛" + service.label() + "搜索响应格式无效");
        }
    }

    /** 优先使用MCP结构化结果；兼容旧服务在一个文本块中返回JSON，其他展示说明不作为业务结果。 */
    private JSONObject extractPayload(McpSchema.CallToolResult result, SearchService service) {
        if (result.structuredContent() != null) {
            return parsePayload(result.structuredContent() instanceof String text ? text : JSON.toJSONString(result.structuredContent()), service);
        }
        JSONObject payload = null;
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text && StringUtils.trimToEmpty(text.text()).startsWith("{")) {
                    JSONObject candidate;
                    try {
                        candidate = JSON.parseObject(text.text());
                    } catch (JSONException e) {
                        continue;
                    }
                    if (candidate != null && candidate.containsKey(service.itemsField())) {
                        Assert.isTrue(payload == null, () -> SystemIntervalException.of("途牛搜索返回多个业务结果，无法确定响应"));
                        payload = candidate;
                    }
                }
            }
        }
        Assert.notNull(payload, () -> SystemIntervalException.of("途牛" + service.label() + "搜索缺失JSON业务结果"));
        return payload;
    }

    private JSONObject parsePayload(String text, SearchService service) {
        try {
            JSONObject payload = JSON.parseObject(text);
            Assert.notNull(payload, () -> SystemIntervalException.of("途牛" + service.label() + "搜索返回空业务结果"));
            return payload;
        } catch (JSONException e) {
            throw SystemIntervalException.of("途牛" + service.label() + "搜索响应不是有效JSON对象");
        }
    }

    /** 请求对象统一序列化，日期及枚举由对象注解承接，仅在SDK参数边界转换为Map。 */
    private JSONObject toArguments(Object request) {
        JSONObject arguments = JSON.parseObject(JSON.toJSONString(request));
        arguments.replaceAll((name, value) -> value instanceof String text ? StringUtils.trimToNull(text) : value);
        arguments.values().removeIf(value -> value == null);
        return arguments;
    }

    private void validateRoute(String departureCity, String arrivalCity, LocalDate departureDate) {
        Assert.isTrue(StringUtils.isNoneBlank(departureCity, arrivalCity) && departureDate != null,
                () -> parameterError("出发城市、到达城市和出发日期不能为空"));
        Assert.isTrue(!departureCity.trim().equalsIgnoreCase(arrivalCity.trim()),
                () -> parameterError("出发城市与到达城市不能相同"));
    }

    private int pageNumber(SearchPageRequest page) {
        int pageNumber = page == null || page.pageNumber() == null ? FIRST_PAGE : page.pageNumber();
        Assert.isTrue(pageNumber >= FIRST_PAGE, () -> parameterError("搜索页码必须大于等于1"));
        return pageNumber;
    }

    private String toTimeRange(SearchTimeRange range, String label) {
        if (range == null) {
            return null;
        }
        Assert.isTrue(range.start() != null && range.end() != null && !range.end().isBefore(range.start()),
                () -> parameterError(label + "时间范围必填两端，且结束时间不能早于开始时间"));
        Assert.isTrue(range.start().getSecond() == 0 && range.end().getSecond() == 0
                        && range.start().getNano() == 0 && range.end().getNano() == 0,
                () -> parameterError(label + "时间范围只支持分钟精度"));
        return TIME_FORMAT.format(range.start()) + "-" + TIME_FORMAT.format(range.end());
    }

    private String toPriceRange(SearchPriceRange range) {
        if (range == null) {
            return null;
        }
        Assert.isTrue(range.minimum() != null && range.maximum() != null
                        && range.minimum().signum() >= 0 && range.maximum().compareTo(range.minimum()) >= 0,
                () -> parameterError("价格范围必填两端，不能为负数，最高价不能低于最低价"));
        return range.minimum().stripTrailingZeros().toPlainString() + "-" + range.maximum().stripTrailingZeros().toPlainString();
    }

    private TuniuFlightSearchRequest.SearchType toFlightSearchType(FlightSearchMode mode) {
        return switch (mode) {
            case LOWEST_PRICE -> null;
            case TIME_RANGE -> TuniuFlightSearchRequest.SearchType.TIME;
            case PRICE_RANGE -> TuniuFlightSearchRequest.SearchType.PRICE;
            case NEAR_DEPARTURE -> TuniuFlightSearchRequest.SearchType.NEAR_GO;
            case NEAR_ARRIVAL -> TuniuFlightSearchRequest.SearchType.NEAR_BACK;
            case TRANSFER -> TuniuFlightSearchRequest.SearchType.TRANSFER;
        };
    }

    /** 续查凭据绑定供应商、业务类型和原条件；业务用户归属仍由应用服务校验。 */
    private String validateCursor(SearchPageRequest page, BookingType type, String criteriaId, boolean required) {
        String token = page == null ? null : StringUtils.trimToNull(page.continuationToken());
        if (pageNumber(page) == FIRST_PAGE) {
            Assert.isTrue(token == null, () -> parameterError("首页不能携带续查凭据"));
            return null;
        }
        if (token == null) {
            Assert.isTrue(!required, () -> parameterError("翻页需要原搜索返回的续查凭据"));
            return null;
        }
        TuniuSearchCursor cursor;
        try {
            cursor = JSON.parseObject(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8), TuniuSearchCursor.class);
        } catch (IllegalArgumentException | JSONException e) {
            throw parameterError("搜索续查凭据格式无效");
        }
        Assert.isTrue(cursor != null && cursor.provider() == getProvider() && cursor.type() == type
                        && criteriaId.equals(cursor.criteriaId()) && StringUtils.isNotBlank(cursor.queryId()),
                () -> parameterError("续查凭据与当前供应商、搜索类型或条件不一致，请重新搜索"));
        return cursor.queryId();
    }

    private SearchPagination toPagination(int pageNumber, Integer totalPages, String queryId, BookingType type, String criteriaId) {
        Assert.isTrue(totalPages == null || totalPages >= 0 && (totalPages == 0 && pageNumber == FIRST_PAGE || totalPages >= pageNumber),
                () -> SystemIntervalException.of("途牛搜索返回无效总页数"));
        Boolean hasNext = totalPages == null ? null : pageNumber < totalPages;
        Assert.isTrue(type == BookingType.FLIGHT || !Boolean.TRUE.equals(hasNext) || StringUtils.isNotBlank(queryId),
                () -> SystemIntervalException.of("途牛搜索缺失后续页的查询标识"));
        String token = StringUtils.isBlank(queryId) ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
                JSON.toJSONBytes(new TuniuSearchCursor(getProvider(), type, queryId.trim(), criteriaId)));
        return new SearchPagination(pageNumber, totalPages, hasNext, token);
    }

    private String criteriaId(JSONObject criteria) {
        return UUID.nameUUIDFromBytes(JSON.toJSONBytes(criteria)).toString();
    }

    private SearchService createService(String address, String toolName, String label, String successField, String itemsField) {
        Assert.notBlank(address, () -> SystemIntervalException.of("途牛" + label + "服务地址不能为空"));
        Assert.notBlank(toolName, () -> SystemIntervalException.of("途牛" + label + "搜索工具名不能为空"));
        URI endpoint;
        try {
            endpoint = URI.create(address.trim());
        } catch (IllegalArgumentException e) {
            throw SystemIntervalException.of("途牛" + label + "服务地址格式无效");
        }
        Assert.isTrue(("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                        && endpoint.getHost() != null && StringUtils.isNotBlank(endpoint.getRawPath())
                        && endpoint.getRawUserInfo() == null && endpoint.getRawQuery() == null && endpoint.getRawFragment() == null,
                () -> SystemIntervalException.of("途牛" + label + "服务地址必须为包含端点路径的HTTP或HTTPS地址，不能包含凭据、查询参数或片段"));
        return new SearchService(endpoint, toolName.trim(), label, successField, itemsField);
    }

    private Duration toTimeout(Integer seconds, String label) {
        Assert.isTrue(seconds != null && seconds > 0, () -> SystemIntervalException.of("途牛MCP" + label + "超时必须大于0"));
        return Duration.ofSeconds(seconds);
    }

    private BusinessRuntimeException parameterError(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }

    /** 只保存服务协议元数据，不含搜索会话状态。 */
    private record SearchService(URI endpoint, String toolName, String label, String successField, String itemsField) {
    }

    /** 无凭据的供应商续查上下文，不是用户授权凭据，不替代应用层的数据隔离。 */
    private record TuniuSearchCursor(ItinerarySearchProvider provider, BookingType type, String queryId, String criteriaId) {
    }
}
