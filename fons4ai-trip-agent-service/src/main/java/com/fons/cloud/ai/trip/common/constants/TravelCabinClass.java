package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 差旅政策中的机票舱位和火车席别。
 * 等级只在同一业务类型内比较，数字越大代表等级越高。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum TravelCabinClass {

    FLIGHT_ECONOMY(BookingType.FLIGHT, "经济舱", 1, List.of("经济", "economy")),
    FLIGHT_BUSINESS(BookingType.FLIGHT, "商务舱", 2, List.of("商务", "business")),
    FLIGHT_FIRST(BookingType.FLIGHT, "头等舱", 3, List.of("头等", "first")),
    TRAIN_SECOND(BookingType.TRAIN, "二等座", 1, List.of("second")),
    TRAIN_FIRST(BookingType.TRAIN, "一等座", 2, List.of("first")),
    TRAIN_BUSINESS(BookingType.TRAIN, "商务座", 3, List.of("business"));

    /** 所属业务类型，避免机票舱位与火车席别混用。 */
    private final BookingType bookingType;

    /** 标准中文名称。 */
    private final String label;

    /** 同一业务类型内的舱位或席别等级。 */
    private final int rank;

    /** 支持的其他名称。 */
    private final List<String> aliases;

    /** 根据业务类型和名称匹配，忽略大小写及首尾空格；未知名称返回 null。 */
    public static TravelCabinClass of(BookingType bookingType, String name) {
        if (bookingType == null || name == null || name.isBlank()) {
            return null;
        }
        String value = name.trim();
        for (TravelCabinClass cabinClass : values()) {
            if (cabinClass.bookingType == bookingType
                    && (cabinClass.label.equalsIgnoreCase(value)
                    || cabinClass.aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(value)))) {
                return cabinClass;
            }
        }
        return null;
    }

    /** 实际等级不高于政策允许等级时返回 true。 */
    public boolean isNoHigherThan(TravelCabinClass allowed) {
        return allowed != null && bookingType == allowed.bookingType && rank <= allowed.rank;
    }
}
