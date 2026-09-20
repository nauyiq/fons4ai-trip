package com.fons.cloud.ai.trip.application.itinerary;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.ItinerarySearchProvider;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.HotelCandidate;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateGroups;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateScope;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateSnapshot;
import com.fons.cloud.ai.trip.common.dto.TransportCandidate;
import com.fons.cloud.ai.trip.common.request.FlightSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelSearchRequest;
import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;
import com.fons.cloud.ai.trip.common.request.TrainSearchRequest;
import com.fons.cloud.ai.trip.common.response.ItinerarySearchResult;
import com.fons.cloud.ai.trip.infrastructure.client.api.ItinerarySearchClient;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryCandidateRepository;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 行程搜索应用服务，选择客户端策略，按可信身份关联并保存Trip标准候选。
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

    /**
     * 注册搜索客户端策略，并注入候选存储组件，不在初始化时调用供应商服务。
     *
     * @param searchClients Spring注入的搜索客户端集合，同一供应商只允许一个实现
     * @param candidateRepository 候选保存及批量读取组件
     * @throws SystemIntervalException 客户端供应商标识为空或重复注册时抛出
     */
    public ItinerarySearchApplicationService(List<ItinerarySearchClient> searchClients, ItineraryCandidateRepository candidateRepository) {
        Map<ItinerarySearchProvider, ItinerarySearchClient> strategies = new EnumMap<>(ItinerarySearchProvider.class);
        for (ItinerarySearchClient client : searchClients) {
            Assert.isTrue(client.getProvider() != null && !strategies.containsKey(client.getProvider()),
                    () -> SystemIntervalException.of("行程搜索客户端供应商为空或重复注册"));
            strategies.put(client.getProvider(), client);
        }
        this.clients = Map.copyOf(strategies);
        this.candidateRepository = candidateRepository;
    }

    /**
     * 搜索国内单程机票当前页，并按可信归属、路线和出发日期增量保存候选。
     * 相同候选ID使用本次结果整条覆盖，保存失败时抛出异常。
     *
     * @param owner 从可信运行上下文取得的用户和根业务会话，不允许模型指定
     * @param provider 服务端选择的搜索供应商，必须存在对应客户端策略
     * @param request Trip机票搜索条件，翻页时保留原条件，由客户端校验并转换供应商参数
     * @return 本次搜索结果，候选为当前页的多个机票报价，不是累计候选池
     * @throws BusinessRuntimeException 搜索参数或候选归属无效时抛出
     * @throws SystemIntervalException 客户端未配置、搜索结果协议异常或候选存储失败时抛出
     */
    public ItinerarySearchResult<TransportCandidate> searchFlights(CandidateOwner owner,
                                                                    ItinerarySearchProvider provider, FlightSearchRequest request) {
        Assert.notNull(request, () -> parameterError("机票搜索参数不能为空"));
        ItineraryCandidateScope scope = ItineraryCandidateScope.transport(owner, BookingType.FLIGHT, request.origin(), request.destination(), request.departureDate());
        ItinerarySearchClient client = client(provider);
        ItinerarySearchResult<TransportCandidate> result = client.searchFlights(request);
        validateSearchResult(provider, result);
        candidateRepository.saveTransportCandidates(scope, result.candidates());
        return result;
    }

    /**
     * 搜索单程火车票当前页，并按可信归属、路线和出发日期增量保存候选。
     * 客户端将同一车次的不同席别报价拆成独立候选，本方法按稳定候选ID保存，不重新解析供应商数据。
     *
     * @param owner 从可信运行上下文取得的用户和根业务会话，不允许模型指定
     * @param provider 服务端选择的搜索供应商，必须存在对应客户端策略
     * @param request Trip火车票搜索条件，翻页时保留原条件和客户端返回的续查凭据
     * @return 本次搜索结果，候选为当前页的多个车次席别报价；候选数可能超过车次数
     * @throws BusinessRuntimeException 搜索参数或候选归属无效时抛出
     * @throws SystemIntervalException 客户端未配置、搜索结果协议异常或候选存储失败时抛出
     */
    public ItinerarySearchResult<TransportCandidate> searchTrains(CandidateOwner owner,
                                                                   ItinerarySearchProvider provider, TrainSearchRequest request) {
        Assert.notNull(request, () -> parameterError("火车票搜索参数不能为空"));
        ItineraryCandidateScope scope = ItineraryCandidateScope.transport(owner, BookingType.TRAIN,
                request.origin(), request.destination(), request.departureDate());
        ItinerarySearchClient client = client(provider);
        ItinerarySearchResult<TransportCandidate> result = client.searchTrains(request);
        validateSearchResult(provider, result);
        candidateRepository.saveTransportCandidates(scope, result.candidates());
        return result;
    }

    /**
     * 搜索单间酒店当前页，并按可信归属、城市、入住日期、成人数和儿童年龄增量保存候选。
     * 关键词、价格范围及分页不改变候选分组；报价保留客户端提供的起价标记和真实计价口径。
     *
     * @param owner 从可信运行上下文取得的用户和根业务会话，不允许模型指定
     * @param provider 服务端选择的搜索供应商，必须存在对应客户端策略
     * @param request Trip酒店搜索条件，成人数为空时按2人处理，儿童年龄为空时表示没有儿童；
     *                翻页时保留原条件和客户端返回的续查凭据
     * @return 本次搜索结果，候选为当前页的多个酒店报价，不代表累计候选列表、可预订报价或完整住宿总价
     * @throws BusinessRuntimeException 搜索参数、入住条件或候选归属无效时抛出
     * @throws SystemIntervalException 客户端未配置、搜索结果协议异常或候选存储失败时抛出
     */
    public ItinerarySearchResult<HotelCandidate> searchHotels(CandidateOwner owner,
                                                               ItinerarySearchProvider provider, HotelSearchRequest request) {
        Assert.notNull(request, () -> parameterError("酒店搜索参数不能为空"));
        int adultCount = request.adultCount() == null ? DEFAULT_ADULT_COUNT : request.adultCount();
        ItineraryCandidateScope scope = ItineraryCandidateScope.hotel(owner, request.city(), request.checkInDate(),
                request.checkOutDate(), adultCount, request.childAges());
        ItinerarySearchClient client = client(provider);
        ItinerarySearchResult<HotelCandidate> result = client.searchHotels(request);
        validateSearchResult(provider, result);
        candidateRepository.saveHotelCandidates(scope, result.candidates());
        return result;
    }

    /**
     * 供规划应用服务读取候选；身份从可信请求字段取得，不接收模型编造的候选数据。
     * 当前单目的地方案按去程日至返程日读取酒店；完整性、库存及最新约束由计算入口另行校验。
     *
     * @param request 往返规划请求，用户和会话必须由可信入口填入；路线、日期及入住人数用于精确匹配候选，
     *                成人数为空时按2人处理，儿童年龄为空时表示没有儿童
     * @return 一次批量读取取得的去程机票、去程火车、返程机票、返程火车和酒店快照；
     *         空分组统一表示当前没有可用候选，不在本方法中打分或过滤
     * @throws BusinessRuntimeException 请求、候选归属、路线、日期或入住条件无效时抛出
     * @throws SystemIntervalException 候选存储访问或快照解析失败时抛出
     */
    public ItineraryCandidateSnapshot loadCandidates(ItineraryPlanRequest request) {
        Assert.notNull(request, () -> parameterError("行程规划请求不能为空"));
        CandidateOwner owner = new CandidateOwner(request.getUserId(), request.getConversationId());
        int adultCount = request.getAdultCount() == null ? DEFAULT_ADULT_COUNT : request.getAdultCount();
        ItineraryCandidateGroups groups = new ItineraryCandidateGroups(
                ItineraryCandidateScope.transport(owner, BookingType.FLIGHT, request.getOrigin(), request.getDestination(), request.getDepartureDate()),
                ItineraryCandidateScope.transport(owner, BookingType.TRAIN, request.getOrigin(), request.getDestination(), request.getDepartureDate()),
                ItineraryCandidateScope.transport(owner, BookingType.FLIGHT, request.getDestination(), request.getOrigin(), request.getReturnDate()),
                ItineraryCandidateScope.transport(owner, BookingType.TRAIN, request.getDestination(), request.getOrigin(), request.getReturnDate()),
                ItineraryCandidateScope.hotel(owner, request.getDestination(), request.getDepartureDate(), request.getReturnDate(), adultCount, request.getChildAges()));
        return candidateRepository.loadSnapshot(groups);
    }

    /**
     * 根据供应商标识取得已注册客户端，不自动切换供应商或执行降级。
     *
     * @param provider 搜索供应商，不能为空
     * @return 对应的搜索客户端策略
     * @throws BusinessRuntimeException 供应商为空时抛出
     * @throws SystemIntervalException 未注册对应客户端时抛出
     */
    private ItinerarySearchClient client(ItinerarySearchProvider provider) {
        Assert.notNull(provider, () -> parameterError("行程搜索供应商不能为空"));
        ItinerarySearchClient client = clients.get(provider);
        Assert.notNull(client, () -> SystemIntervalException.of("当前行程搜索供应商未配置客户端"));
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
                () -> SystemIntervalException.of("搜索客户端返回的供应商与当前策略不一致"));
        Assert.isTrue(result.searchedAt() != null && result.candidates() != null && result.pagination() != null
                        && result.warnings() != null && result.warnings().stream().allMatch(item -> item != null)
                        && result.discardedItemCount() >= 0,
                () -> SystemIntervalException.of("标准搜索结果缺少必要信息，无法保存候选"));
    }

    /**
     * 构造使用框架参数错误码的业务异常，供参数断言及校验使用。
     *
     * @param message 具体参数错误说明
     * @return 待抛出的业务异常，本方法不直接抛出
     */
    private BusinessRuntimeException parameterError(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }
}
