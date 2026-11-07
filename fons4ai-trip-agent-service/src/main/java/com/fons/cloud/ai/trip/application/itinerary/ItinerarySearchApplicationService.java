package com.fons.cloud.ai.trip.application.itinerary;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.ItinerarySearchProvider;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.HotelCandidate;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateScope;
import com.fons.cloud.ai.trip.common.dto.TransportCandidate;
import com.fons.cloud.ai.trip.common.request.FlightSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelRoomSearchRequest;
import com.fons.cloud.ai.trip.common.request.TrainSearchRequest;
import com.fons.cloud.ai.trip.common.response.ItinerarySearchResult;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.ai.trip.infrastructure.client.api.ItinerarySearchClient;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryCandidateRepository;

import com.fons.cloud.ai.trip.common.exception.TripExceptions;
import com.fons.cloud.common.exception.ValidationException;
import com.fons.cloud.common.exception.BusinessRuntimeException;
import com.fons.cloud.common.exception.SystemIntervalException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 行程搜索应用服务，选择客户端策略；仅差旅单规划搜索保存Trip标准候选。
 * 供应商请求和响应转换由ItinerarySearchClient的具体实现负责，应用层不解析供应商协议。
 * @author hongqy
 */
@Slf4j
@Service
public class ItinerarySearchApplicationService {

    /** 与酒店搜索契约一致，第一阶段按单间住宿搜索。 */
    private static final int DEFAULT_ADULT_COUNT = 2;

    private final Map<ItinerarySearchProvider, ItinerarySearchClient> clients;
    private final ItineraryCandidateRepository candidateRepository;
    private final TravelOrderDomainService travelOrderDomainService;

    /**
     * 注册搜索客户端策略，并注入候选存储组件，不在初始化时调用供应商服务。
     *
     * @param searchClients Spring注入的搜索客户端集合，同一供应商只允许一个实现
     * @param candidateRepository 候选保存及批量读取组件
     * @param travelOrderDomainService 按可信用户读取差旅单的领域服务
     * @throws SystemIntervalException 客户端供应商标识为空或重复注册时抛出
     */
    public ItinerarySearchApplicationService(List<ItinerarySearchClient> searchClients,
                                             ItineraryCandidateRepository candidateRepository,
                                             TravelOrderDomainService travelOrderDomainService) {
        Map<ItinerarySearchProvider, ItinerarySearchClient> strategies = new EnumMap<>(ItinerarySearchProvider.class);
        for (ItinerarySearchClient client : searchClients) {
            Assert.isTrue(client.getProvider() != null && !strategies.containsKey(client.getProvider()),
                    () -> TripExceptions.system("行程搜索客户端供应商为空或重复注册"));
            strategies.put(client.getProvider(), client);
        }
        this.clients = Map.copyOf(strategies);
        this.candidateRepository = candidateRepository;
        this.travelOrderDomainService = travelOrderDomainService;
    }

    /**
     * 搜索国内单程机票当前页；指定差旅单时才保存规划候选。
     * 相同候选ID使用本次结果整条覆盖，保存失败时抛出异常。
     *
     * @param userId 从可信运行上下文取得的用户ID
     * @param travelOrderId 规划关联的差旅单ID；普通查询不传
     * @param provider 服务端选择的搜索供应商，必须存在对应客户端策略
     * @param request Trip机票搜索条件，翻页时保留原条件，由客户端校验并转换供应商参数
     * @return 本次搜索结果，候选为当前页的多个机票报价，不是累计候选池
     * @throws ValidationException 搜索参数或候选归属无效时抛出
     * @throws SystemIntervalException 客户端未配置、搜索结果协议异常或候选存储失败时抛出
     */
    public ItinerarySearchResult<TransportCandidate> searchFlights(String userId, String travelOrderId,
                                                                    ItinerarySearchProvider provider, FlightSearchRequest request) {
        Assert.notNull(request, () -> parameterError("机票搜索参数不能为空"));
        String orderId = StringUtils.trimToNull(travelOrderId);
        ItineraryCandidateScope scope = null;
        if (orderId != null) {
            validateTransportOrder(userId, orderId, request.origin(), request.destination(), request.departureDate());
            scope = ItineraryCandidateScope.transport(userId, orderId, BookingType.FLIGHT, request.origin(), request.destination(), request.departureDate());
        }
        ItinerarySearchClient client = client(provider);
        ItinerarySearchResult<TransportCandidate> result = client.searchFlights(request);
        validateSearchResult(provider, result);
        if (scope != null) {
            // 供应商查询可能耗时，保存前重新检查订单当前状态与行程条件。
            validateTransportOrder(userId, orderId, request.origin(), request.destination(), request.departureDate());
            candidateRepository.saveTransportCandidates(scope, result.candidates(), result.searchedAt());
        }
        return result;
    }

    /**
     * 搜索单程火车票当前页；指定差旅单时才保存规划候选。
     * 客户端将同一车次的不同席别报价拆成独立候选，本方法按稳定候选ID保存，不重新解析供应商数据。
     *
     * @param userId 从可信运行上下文取得的用户ID
     * @param travelOrderId 规划关联的差旅单ID；普通查询不传
     * @param provider 服务端选择的搜索供应商，必须存在对应客户端策略
     * @param request Trip火车票搜索条件，翻页时保留原条件和客户端返回的续查凭据
     * @return 本次搜索结果，候选为当前页的多个车次席别报价；候选数可能超过车次数
     * @throws ValidationException 搜索参数或候选归属无效时抛出
     * @throws SystemIntervalException 客户端未配置、搜索结果协议异常或候选存储失败时抛出
     */
    public ItinerarySearchResult<TransportCandidate> searchTrains(String userId, String travelOrderId,
                                                                   ItinerarySearchProvider provider, TrainSearchRequest request) {
        Assert.notNull(request, () -> parameterError("火车票搜索参数不能为空"));
        String orderId = StringUtils.trimToNull(travelOrderId);
        ItineraryCandidateScope scope = null;
        if (orderId != null) {
            validateTransportOrder(userId, orderId, request.origin(), request.destination(), request.departureDate());
            scope = ItineraryCandidateScope.transport(userId, orderId, BookingType.TRAIN,
                    request.origin(), request.destination(), request.departureDate());
        }
        ItinerarySearchClient client = client(provider);
        ItinerarySearchResult<TransportCandidate> result = client.searchTrains(request);
        validateSearchResult(provider, result);
        if (scope != null) {
            validateTransportOrder(userId, orderId, request.origin(), request.destination(), request.departureDate());
            candidateRepository.saveTransportCandidates(scope, result.candidates(), result.searchedAt());
        }
        return result;
    }

    /**
     * 搜索单间酒店当前页；指定差旅单时才保存规划候选。
     * 关键词、价格范围及分页不改变候选分组；报价保留客户端提供的起价标记和真实计价口径。
     *
     * @param userId 从可信运行上下文取得的用户ID
     * @param travelOrderId 规划关联的差旅单ID；普通查询不传
     * @param provider 服务端选择的搜索供应商，必须存在对应客户端策略
     * @param request Trip酒店搜索条件，成人数为空时按2人处理，儿童年龄为空时表示没有儿童；
     *                翻页时保留原条件和客户端返回的续查凭据
     * @return 本次搜索结果，候选为当前页的多个酒店报价，不代表累计候选列表、可预订报价或完整住宿总价
     * @throws ValidationException 搜索参数、入住条件或候选归属无效时抛出
     * @throws SystemIntervalException 客户端未配置、搜索结果协议异常或候选存储失败时抛出
     */
    public ItinerarySearchResult<HotelCandidate> searchHotels(String userId, String travelOrderId,
                                                               ItinerarySearchProvider provider, HotelSearchRequest request) {
        Assert.notNull(request, () -> parameterError("酒店搜索参数不能为空"));
        String orderId = StringUtils.trimToNull(travelOrderId);
        ItineraryCandidateScope scope = null;
        if (orderId != null) {
            validateHotelOrder(userId, orderId, request.city(), request.checkInDate(), request.checkOutDate());
            int adultCount = request.adultCount() == null ? DEFAULT_ADULT_COUNT : request.adultCount();
            scope = ItineraryCandidateScope.hotel(userId, orderId, request.city(), request.checkInDate(),
                    request.checkOutDate(), adultCount, request.childAges());
        }
        ItinerarySearchClient client = client(provider);
        ItinerarySearchResult<HotelCandidate> result = client.searchHotels(request);
        validateSearchResult(provider, result);
        if (scope != null) {
            validateHotelOrder(userId, orderId, request.city(), request.checkInDate(), request.checkOutDate());
            candidateRepository.saveHotelCandidates(scope, result.candidates(), result.searchedAt());
        }
        return result;
    }

    /**
     * 查询指定酒店的具体房型报价；指定差旅单时保存到住宿候选分组。
     * 每个房型报价方案是独立候选，可覆盖同一ID的旧价格和库存；酒店列表起价候选仍保留用于展示。
     *
     * @param userId 从可信运行上下文取得的用户ID
     * @param travelOrderId 规划关联的差旅单ID；普通查询不传
     * @param provider 服务端选择的搜索供应商
     * @param request Trip酒店房型报价条件，酒店标识来自酒店列表候选的source.itemId
     * @return 本次具体房型报价结果，不包含累计候选池
     * @throws ValidationException 搜索参数、入住条件或候选归属无效时抛出
     * @throws SystemIntervalException 客户端未配置、响应协议异常或候选保存失败时抛出
     */
    public ItinerarySearchResult<HotelCandidate> searchHotelRooms(String userId, String travelOrderId,
                                                                   ItinerarySearchProvider provider,
                                                                   HotelRoomSearchRequest request) {
        Assert.notNull(request, () -> parameterError("酒店房型报价搜索参数不能为空"));
        String orderId = StringUtils.trimToNull(travelOrderId);
        ItineraryCandidateScope scope = null;
        if (orderId != null) {
            validateHotelOrder(userId, orderId, request.city(), request.checkInDate(), request.checkOutDate());
            int adultCount = request.adultCount() == null ? DEFAULT_ADULT_COUNT : request.adultCount();
            scope = ItineraryCandidateScope.hotel(userId, orderId, request.city(), request.checkInDate(),
                    request.checkOutDate(), adultCount, request.childAges());
        }
        ItinerarySearchClient client = client(provider);
        ItinerarySearchResult<HotelCandidate> result = client.searchHotelRooms(request);
        validateSearchResult(provider, result);
        if (scope != null) {
            validateHotelOrder(userId, orderId, request.city(), request.checkInDate(), request.checkOutDate());
            candidateRepository.saveHotelCandidates(scope, result.candidates(), result.searchedAt());
        }
        return result;
    }

    /** 校验搜索条件与当前用户的差旅单去程或返程一致。 */
    private void validateTransportOrder(String userId, String orderId,
                                        String origin, String destination, LocalDate departureDate) {
        TravelOrder order = requirePlanningOrder(userId, orderId);
        LocalDate orderDeparture = parseOrderDate(order.getDepartureDate());
        LocalDate orderReturn = parseOrderDate(order.getReturnDate());
        boolean outbound = sameCity(origin, order.getDepartureCity())
                && sameCity(destination, order.getDestination()) && Objects.equals(departureDate, orderDeparture);
        boolean inbound = sameCity(origin, order.getDestination())
                && sameCity(destination, order.getDepartureCity()) && Objects.equals(departureDate, orderReturn);
        Assert.isTrue(outbound || inbound, () -> parameterError("搜索路线和日期必须与差旅单的去程或返程一致"));
    }

    /** 酒店规划沿用差旅单目的地和往返日期，避免保存到不匹配的候选组。 */
    private void validateHotelOrder(String userId, String orderId,
                                    String city, LocalDate checkInDate, LocalDate checkOutDate) {
        TravelOrder order = requirePlanningOrder(userId, orderId);
        Assert.isTrue(sameCity(city, order.getDestination())
                        && Objects.equals(checkInDate, parseOrderDate(order.getDepartureDate()))
                        && Objects.equals(checkOutDate, parseOrderDate(order.getReturnDate())),
                () -> parameterError("酒店城市、入住和离店日期必须与差旅单行程一致"));
    }

    /** 单号来自工具参数，必须以运行时用户查单并校验状态，不能信任模型传入的归属。 */
    private TravelOrder requirePlanningOrder(String userId, String orderId) {
        Assert.isTrue(StringUtils.isNotBlank(userId), () -> parameterError("规划搜索缺少可信用户"));
        TravelOrder order = travelOrderDomainService.findByOrderIdAndUserId(orderId, userId);
        // 单号是搜索参数；未找到时允许用户更正单号后重新查询。
        Assert.notNull(order, () -> parameterError("差旅单不存在或不属于当前用户"));
        TravelOrderStatus status = order.getStatus();
        Assert.isTrue(status == TravelOrderStatus.DRAFT || status == TravelOrderStatus.SUBMITTED
                        || status == TravelOrderStatus.APPROVED,
                () -> TripExceptions.business(TripAgentToolResultCode.INVALID_STATE.getCode(),
                        "当前差旅单状态不支持行程规划"));
        Assert.isTrue(StringUtils.isNoneBlank(order.getDepartureCity(), order.getDestination(),
                        order.getDepartureDate(), order.getReturnDate()),
                () -> TripExceptions.business(TripAgentToolResultCode.INVALID_STATE.getCode(),
                        "差旅单缺少城市或日期，无法进行行程规划"));
        Assert.isTrue(!parseOrderDate(order.getReturnDate()).isBefore(parseOrderDate(order.getDepartureDate())),
                () -> TripExceptions.business(TripAgentToolResultCode.INVALID_STATE.getCode(),
                        "差旅单返回日期早于出发日期，无法进行行程规划"));
        return order;
    }

    /** 与现有候选分组口径一致，仅去除城市两端空格，不将不同城市标识视为同一个Key。 */
    private boolean sameCity(String actual, String expected) {
        return StringUtils.equals(StringUtils.trimToNull(actual), StringUtils.trimToNull(expected));
    }

    private LocalDate parseOrderDate(String date) {
        try {
            return LocalDate.parse(StringUtils.trim(date));
        } catch (DateTimeParseException e) {
            throw TripExceptions.business(TripAgentToolResultCode.INVALID_STATE.getCode(),
                    "差旅单日期无效，无法进行行程规划");
        }
    }

    /**
     * 根据供应商标识取得已注册客户端，不自动切换供应商或执行降级。
     *
     * @param provider 搜索供应商，不能为空
     * @return 对应的搜索客户端策略
     * @throws ValidationException 供应商为空时抛出
     * @throws SystemIntervalException 未注册对应客户端时抛出
     */
    private ItinerarySearchClient client(ItinerarySearchProvider provider) {
        Assert.notNull(provider, () -> parameterError("行程搜索供应商不能为空"));
        ItinerarySearchClient client = clients.get(provider);
        Assert.notNull(client, () -> TripExceptions.system("当前行程搜索供应商未配置客户端"));
        return client;
    }

    /**
     * 校验客户端结果的供应商与所选策略一致，并检查构造存储摘要需要的必要字段。
     *
     * @param provider 当前选择的搜索供应商
     * @param result 客户端返回的标准搜索结果
     * @throws SystemIntervalException 结果为空、供应商与策略不一致或必要字段缺失时抛出
     */
    private void validateSearchResult(ItinerarySearchProvider provider, ItinerarySearchResult<?> result) {
        Assert.isTrue(result != null && result.provider() == provider,
                () -> TripExceptions.system("搜索客户端返回的供应商与当前策略不一致"));
        Assert.isTrue(result.searchedAt() != null && result.candidates() != null && result.pagination() != null
                        && result.warnings() != null && result.warnings().stream().allMatch(item -> item != null)
                        && result.discardedItemCount() >= 0,
                () -> TripExceptions.system("标准搜索结果缺少必要信息，无法保存候选"));
    }

    /**
     * 构造使用框架参数错误码的业务异常，供参数断言及校验使用。
     *
     * @param message 具体参数错误说明
     * @return 待抛出的业务异常，本方法不直接抛出
     */
    private ValidationException parameterError(String message) {
        return TripExceptions.invalidParameter(message);
    }
}
