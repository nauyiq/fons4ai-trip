package com.fons.cloud.ai.trip.infrastructure.client.model.tuniu;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.FlightSearchMode;
import com.fons.cloud.ai.trip.common.constants.ItinerarySearchProvider;
import com.fons.cloud.ai.trip.common.constants.SearchPriceBasis;
import com.fons.cloud.ai.trip.common.dto.CandidatePrice;
import com.fons.cloud.ai.trip.common.dto.CandidateSource;
import com.fons.cloud.ai.trip.common.dto.HotelCandidate;
import com.fons.cloud.ai.trip.common.dto.SearchPagination;
import com.fons.cloud.ai.trip.common.dto.TransportCandidate;
import com.fons.cloud.ai.trip.common.request.FlightSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelRoomSearchRequest;
import com.fons.cloud.ai.trip.common.request.TrainSearchRequest;
import com.fons.cloud.ai.trip.common.response.ItinerarySearchResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 途牛协议到Trip候选的纯转换器，由途牛客户端内部使用，不参与业务存储。
 * 可选信息缺失保留null并提醒；身份字段无效舍弃该原始条目，全部舍弃则报协议异常。
 *
 * @author hongqy
 */
public final class TuniuSearchConverter {

    /** 当前接入的是国内搜索端点，其当地时间和金额分别按中国时区及人民币解释。 */
    private static final ZoneId DOMESTIC_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DOMESTIC_CURRENCY = "CNY";
    private static final List<DateTimeFormatter> LOCAL_TIME_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT));

    public ItinerarySearchResult<TransportCandidate> convertFlights(TuniuFlightSearchResponse response,
                                                             FlightSearchRequest request, SearchPagination pagination) {
        List<TransportCandidate> candidates = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        int discarded = 0;
        for (TuniuFlightSearchResponse.Flight flight : response.data()) {
            if (flight == null || StringUtils.isBlank(flight.flightNumber())) {
                discarded++;
                continue;
            }
            OffsetDateTime departure = time(flight.departureTime(), warnings);
            OffsetDateTime arrival = time(flight.arrivalTime(), warnings);
            String origin = request.mode() == FlightSearchMode.NEAR_DEPARTURE ? null : text(request.origin());
            String destination = request.mode() == FlightSearchMode.NEAR_ARRIVAL ? null : text(request.destination());
            if (origin == null || destination == null) {
                warnings.add("附近机场候选未提供实际城市，不能直接按请求城市计算市内接驳");
            }
            String code = text(flight.flightNumber());
            String cabin = text(flight.cabinClass());
            if (cabin == null) {
                warnings.add("部分机票未提供舱位，进入规划前需要补齐");
            }
            String id = identity(BookingType.FLIGHT, code, request.departureDate(), text(flight.departureAirport()),
                    text(flight.arrivalAirport()), departure == null ? text(flight.departureTime()) : departure, cabin);
            String stockDescription = text(flight.remainingSeats());
            Integer stock = stock(stockDescription, warnings);
            candidates.add(new TransportCandidate(id, source(null), BookingType.FLIGHT,
                    text(flight.airlineCompany()), code, origin, destination,
                    text(flight.departureAirport()), text(flight.arrivalAirport()),
                    text(flight.departureTerminal()), text(flight.arrivalTerminal()), departure, arrival,
                    duration(departure, arrival, warnings), flightPrice(flight, warnings), cabin, stock,
                    stock == null ? stockDescription : null, direct(flight.type()), text(flight.type()),
                    text(flight.craftType()), text(flight.shareFlightNo()), null));
        }
        return result(candidates, pagination, response.data().size(), discarded, warnings);
    }

    public ItinerarySearchResult<TransportCandidate> convertTrains(TuniuTrainSearchResponse response,
                                                            TrainSearchRequest request, SearchPagination pagination) {
        List<TransportCandidate> candidates = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        int discarded = 0;
        for (TuniuTrainSearchResponse.Train train : response.data()) {
            if (train == null || StringUtils.isBlank(train.trainNum())) {
                discarded++;
                continue;
            }
            OffsetDateTime departure = time(train.departureTime(), warnings);
            OffsetDateTime arrival = time(train.arrivalTime(), warnings);
            Long minutes = duration(departure, arrival, warnings);
            boolean hasQuote = false;
            for (TuniuTrainSeat seat : TuniuTrainSeat.values()) {
                String rawPrice = train.price() == null ? null : seat.getPriceReader().apply(train.price());
                if (StringUtils.isBlank(rawPrice)) {
                    continue;
                }
                hasQuote = true;
                Integer stock = train.seatAvailable() == null ? null : seat.getStockReader().apply(train.seatAvailable());
                if (stock != null && stock < 0) {
                    stock = null;
                    warnings.add("部分余票数量无效，按未知库存处理");
                }
                CandidatePrice price = new CandidatePrice(money(rawPrice, warnings), DOMESTIC_CURRENCY,
                        SearchPriceBasis.PER_PERSON_ONE_WAY, null, null, false);
                candidates.add(trainCandidate(train, request, departure, arrival, minutes, seat.getLabel(), price, stock));
            }
            if (!hasQuote) {
                warnings.add("部分车次未提供席别报价，候选仅可展示，不能直接参与费用计算");
                candidates.add(trainCandidate(train, request, departure, arrival, minutes, null,
                        new CandidatePrice(null, DOMESTIC_CURRENCY, SearchPriceBasis.PER_PERSON_ONE_WAY, null, null, null), null));
            }
        }
        return result(candidates, pagination, response.data().size(), discarded, warnings);
    }

    public ItinerarySearchResult<HotelCandidate> convertHotels(TuniuHotelSearchResponse response,
                                                        HotelSearchRequest request, int adultCount, SearchPagination pagination) {
        List<HotelCandidate> candidates = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        int discarded = 0;
        int nights = Math.toIntExact(ChronoUnit.DAYS.between(request.checkInDate(), request.checkOutDate()));
        List<Integer> childAges = request.childAges() == null ? List.of() : request.childAges().stream().sorted().toList();
        for (TuniuHotelSearchResponse.Hotel hotel : response.hotels()) {
            if (hotel == null || hotel.hotelId() == null || hotel.hotelId() <= 0 || StringUtils.isBlank(hotel.hotelName())) {
                discarded++;
                continue;
            }
            String id = identity(BookingType.HOTEL, hotel.hotelId(), request.checkInDate(), request.checkOutDate(),
                    adultCount, childAges, text(hotel.roomName()), text(hotel.meal()), text(hotel.refund()));
            BigDecimal amount = nonNegative(hotel.lowestPrice(), warnings);
            warnings.add("酒店最低价仅为起价，计价口径未明确，不能直接乘入住晚数或当作总价");
            String supplierPoi = response.poiInfo() == null ? null : text(response.poiInfo().poiName());
            // 默认地标不能冒充用户目的地；未确认匹配时只保留距离原文。
            String reference = supplierPoi != null && StringUtils.isNotBlank(hotel.distance())
                    && supplierPoi.equalsIgnoreCase(StringUtils.trimToEmpty(request.destinationLocation())) ? supplierPoi : null;
            if (StringUtils.isNotBlank(hotel.distance())) {
                warnings.add("酒店距离保留供应商原文，未确认单位和参照地点前不能用于数值评分");
            }
            candidates.add(new HotelCandidate(id, source(hotel.hotelId().toString()), text(hotel.hotelName()),
                    StringUtils.defaultIfBlank(text(hotel.cityName()), text(request.city())), text(hotel.address()),
                    text(hotel.business()), text(hotel.brandName()), star(hotel.starName()), text(hotel.starName()),
                    nonNegative(hotel.commentScore(), warnings), text(hotel.commentDigest()), text(hotel.firstPic()),
                    null, text(hotel.roomName()), null, null, null,
                    text(hotel.roomArea()), text(hotel.roomWindow()), null,
                    request.checkInDate(), request.checkOutDate(), nights, adultCount, childAges.size(),
                    new CandidatePrice(amount, DOMESTIC_CURRENCY, SearchPriceBasis.UNKNOWN, null, null, true),
                    reference, null, text(hotel.distance()), breakfast(hotel.meal()), text(hotel.meal()),
                    text(hotel.refund()), null));
        }
        return result(candidates, pagination, response.hotels().size(), discarded, warnings);
    }

    /**
     * 将酒店详情中的“房型 × 报价方案”拆成可计算候选。
     * 详情接口在一间房条件下返回具体报价，按单间每晚口径接入；列表最低价仍保持起价候选。
     * 短期preBookParam不写入候选，实际预订前必须重新查询详情并验价。
     */
    public ItinerarySearchResult<HotelCandidate> convertHotelRooms(TuniuHotelDetailResponse response,
                                                                    HotelRoomSearchRequest request,
                                                                    int adultCount,
                                                                    SearchPagination pagination) {
        List<HotelCandidate> candidates = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        int discarded = 0;
        int originalCount = 0;
        int nights = Math.toIntExact(ChronoUnit.DAYS.between(request.checkInDate(), request.checkOutDate()));
        List<Integer> childAges = request.childAges() == null
                ? List.of() : request.childAges().stream().sorted().toList();
        int guestCount = adultCount + childAges.size();
        List<TuniuHotelDetailResponse.RoomType> roomTypes = response.roomTypes() == null
                ? List.of() : response.roomTypes();

        for (TuniuHotelDetailResponse.RoomType room : roomTypes) {
            List<TuniuHotelDetailResponse.RatePlan> ratePlans = room == null || room.ratePlans() == null
                    ? List.of() : room.ratePlans();
            originalCount += ratePlans.size();
            if (room == null || StringUtils.isAnyBlank(room.roomTypeId(), room.roomTypeName())) {
                discarded += Math.max(1, ratePlans.size());
                continue;
            }
            if (room.maxOccupancy() != null && room.maxOccupancy() > 0 && guestCount > room.maxOccupancy()) {
                warnings.add("部分房型最大入住人数不足，规划时将过滤对应报价");
            }
            if (ratePlans.isEmpty()) {
                warnings.add("部分房型当前未返回报价方案，不能参与规划");
                continue;
            }
            for (TuniuHotelDetailResponse.RatePlan ratePlan : ratePlans) {
                if (ratePlan == null || StringUtils.isBlank(ratePlan.vendorRatePlanId())) {
                    discarded++;
                    continue;
                }
                BigDecimal price = money(ratePlan.rmbPrices(), warnings);
                if (price == null || price.signum() <= 0) {
                    discarded++;
                    warnings.add("部分房型报价金额缺失或无效，已舍弃对应报价");
                    continue;
                }
                Integer remainingRooms = ratePlan.count();
                if (remainingRooms != null && remainingRooms < 0) {
                    remainingRooms = null;
                    warnings.add("部分房型库存数量无效，按未知库存处理");
                } else if (remainingRooms != null && remainingRooms == 0) {
                    warnings.add("部分房型报价当前已无库存，规划时将过滤对应候选");
                }

                String roomTypeId = text(room.roomTypeId());
                String offerId = text(ratePlan.vendorRatePlanId());
                String id = identity(BookingType.HOTEL, response.hotelId(), request.checkInDate(),
                        request.checkOutDate(), adultCount, childAges, roomTypeId, offerId);
                String imageUrl = room.images() == null ? null : room.images().stream()
                        .map(this::text).filter(Objects::nonNull).findFirst().orElse(null);
                if (imageUrl == null) {
                    imageUrl = text(response.firstPic());
                }
                BigDecimal reviewScore = response.commentScore() != null
                        ? nonNegative(response.commentScore(), warnings)
                        : response.reviews() == null ? null : nonNegative(response.reviews().score(), warnings);
                String cancelPolicy = StringUtils.defaultIfBlank(text(ratePlan.cancelDesc()),
                        response.policies() == null ? null : text(response.policies().cancelPolicy()));
                candidates.add(new HotelCandidate(id, source(response.hotelId().toString(), offerId),
                        text(response.hotelName()), StringUtils.defaultIfBlank(text(response.cityName()), text(request.city())),
                        text(response.address()), text(response.business()), text(response.brandName()),
                        star(response.starName()), text(response.starName()), reviewScore,
                        text(response.commentDigest()), imageUrl, roomTypeId, text(room.roomTypeName()),
                        text(ratePlan.ratePlanName()), text(room.bedType()), room.maxOccupancy(),
                        roomArea(room.roomSize()), null, text(room.floor()), request.checkInDate(),
                        request.checkOutDate(), nights, adultCount, childAges.size(),
                        new CandidatePrice(price, DOMESTIC_CURRENCY, SearchPriceBasis.PER_ROOM_PER_NIGHT,
                                null, null, false),
                        null, null, null, breakfast(ratePlan.mealText()), text(ratePlan.mealText()),
                        cancelPolicy, remainingRooms));
            }
        }
        return result(candidates, pagination, originalCount, discarded, warnings);
    }

    private TransportCandidate trainCandidate(TuniuTrainSearchResponse.Train train, TrainSearchRequest request,
                                               OffsetDateTime departure, OffsetDateTime arrival, Long minutes,
                                               String cabin, CandidatePrice price, Integer stock) {
        String code = text(train.trainNum());
        String id = identity(BookingType.TRAIN, code, request.departureDate(), text(train.departStationName()),
                text(train.destStationName()), departure == null ? text(train.departureTime()) : departure, cabin);
        // 单一车次无需换乘；车次类型“直达”与是否中转不是同一概念。
        return new TransportCandidate(id, source(null), BookingType.TRAIN, null, code,
                text(request.origin()), text(request.destination()), text(train.departStationName()), text(train.destStationName()),
                null, null, departure, arrival, minutes, price, cabin, stock, null, true, text(train.trainType()), null, null, null);
    }

    private CandidatePrice flightPrice(TuniuFlightSearchResponse.Flight flight, Set<String> warnings) {
        BigDecimal base = money(flight.basePrice(), warnings);
        BigDecimal tax = money(flight.totalTax(), warnings);
        if (base != null && tax != null) {
            return new CandidatePrice(base.add(tax), DOMESTIC_CURRENCY, SearchPriceBasis.PER_PERSON_ONE_WAY, true, tax, false);
        }
        warnings.add("部分机票票价或税费缺失，费用计算前需要确认完整报价");
        return new CandidatePrice(base, DOMESTIC_CURRENCY, SearchPriceBasis.PER_PERSON_ONE_WAY,
                base == null ? null : false, tax, false);
    }

    /** 只接受包含日期的时间，缺失跨日信息时不凭请求日期猜测到达日期。 */
    private OffsetDateTime time(String raw, Set<String> warnings) {
        String value = text(raw);
        if (value != null) {
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException ignored) {
                for (DateTimeFormatter format : LOCAL_TIME_FORMATS) {
                    try {
                        return LocalDateTime.parse(value, format).atZone(DOMESTIC_ZONE).toOffsetDateTime();
                    } catch (DateTimeParseException ignoredFormat) {
                        // 尝试供应商支持的下一种完整日期时间格式。
                    }
                }
            }
        }
        warnings.add("部分交通时间缺失或格式无效，进入规划前需要确认完整出发及到达日期时间");
        return null;
    }

    private Long duration(OffsetDateTime departure, OffsetDateTime arrival, Set<String> warnings) {
        if (departure == null || arrival == null) {
            return null;
        }
        if (!arrival.isAfter(departure)) {
            warnings.add("部分交通到达时间不晚于出发时间，不能据此计算交通耗时");
            return null;
        }
        return Duration.between(departure, arrival).toMinutes();
    }

    private BigDecimal money(String raw, Set<String> warnings) {
        if (StringUtils.isBlank(raw)) {
            warnings.add("部分报价金额未提供，不能用零价格参与规划");
            return null;
        }
        try {
            return nonNegative(new BigDecimal(raw.trim()), warnings);
        } catch (NumberFormatException e) {
            warnings.add("部分报价金额格式无效，按未知价格处理");
            return null;
        }
    }

    private BigDecimal nonNegative(BigDecimal value, Set<String> warnings) {
        if (value != null && value.signum() < 0) {
            warnings.add("部分金额或评分为负数，按未知数据处理");
            return null;
        }
        return value;
    }

    private Integer stock(String raw, Set<String> warnings) {
        if (raw == null) {
            return null;
        }
        try {
            int count = Integer.parseInt(raw);
            if (count >= 0) {
                return count;
            }
            warnings.add("部分余票数量无效，按未知库存处理");
        } catch (NumberFormatException ignored) {
            // “有票”等非数值说明保留原文，不推断库存数量。
        }
        return null;
    }

    private Boolean direct(String raw) {
        return switch (StringUtils.trimToEmpty(raw)) {
            case "直飞", "经停" -> true;
            case "中转", "转机" -> false;
            default -> null;
        };
    }

    /** 档次分类保留原文，仅转换明确的星级说明。 */
    private Integer star(String raw) {
        return switch (StringUtils.trimToEmpty(raw)) {
            case "一星", "一星级", "1星", "1星级" -> 1;
            case "二星", "二星级", "2星", "2星级" -> 2;
            case "三星", "三星级", "3星", "3星级" -> 3;
            case "四星", "四星级", "4星", "4星级" -> 4;
            case "五星", "五星级", "5星", "5星级" -> 5;
            default -> null;
        };
    }

    private Boolean breakfast(String raw) {
        return switch (StringUtils.trimToEmpty(raw)) {
            case "无早餐", "不含早餐", "无早" -> false;
            case "含早餐", "单早", "双早", "含单早", "含双早" -> true;
            default -> null;
        };
    }

    private CandidateSource source(String itemId) {
        return new CandidateSource(ItinerarySearchProvider.TU_NIU, itemId, null);
    }

    private CandidateSource source(String itemId, String offerId) {
        return new CandidateSource(ItinerarySearchProvider.TU_NIU, itemId, offerId);
    }

    private String roomArea(BigDecimal roomSize) {
        return roomSize == null || roomSize.signum() <= 0
                ? null : roomSize.stripTrailingZeros().toPlainString() + "㎡";
    }

    /** 身份由供应商、行程和报价选项确定，不包含价格、库存、筛选条件及页码。 */
    private String identity(BookingType type, Object... parts) {
        return UUID.nameUUIDFromBytes(JSON.toJSONBytes(new CandidateIdentity(ItinerarySearchProvider.TU_NIU, type, parts))).toString();
    }

    private String text(String value) {
        return StringUtils.trimToNull(value);
    }

    private <T> ItinerarySearchResult<T> result(List<T> candidates, SearchPagination pagination,
                                               int originalCount, int discarded, Set<String> warnings) {
        Assert.isTrue(originalCount == 0 || !candidates.isEmpty(),
                () -> SystemIntervalException.of("途牛搜索结果全部缺少必要身份字段，无法生成标准候选"));
        Assert.isTrue(pagination.totalPages() == null || pagination.totalPages() != 0 || originalCount == 0,
                () -> SystemIntervalException.of("途牛搜索结果与总页数不一致"));
        if (discarded > 0) {
            warnings.add("部分搜索条目缺少必要身份字段，已舍弃，请结合舍弃数量判断结果完整性");
        }
        return new ItinerarySearchResult<>(ItinerarySearchProvider.TU_NIU, OffsetDateTime.now(ZoneOffset.UTC),
                List.copyOf(candidates), pagination, discarded, List.copyOf(warnings));
    }

    private record CandidateIdentity(ItinerarySearchProvider provider, BookingType type, Object[] parts) {
    }
}
