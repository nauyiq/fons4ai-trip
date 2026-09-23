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

    /**
     * 判断实际舱位或席别是否符合政策允许范围。
     * 政策支持中文逗号、英文逗号或斜杠分隔；无法识别的名称仅允许忽略大小写的精确匹配。
     */
    public static boolean isCompliant(BookingType bookingType, String actual, String allowedSpec) {
        if (bookingType == null || actual == null || actual.isBlank()
                || allowedSpec == null || allowedSpec.isBlank()) {
            return false;
        }
        String normalized = actual.trim();
        TravelCabinClass actualClass = of(bookingType, normalized);
        for (String item : allowedSpec.replace('，', ',').replace('/', ',').split(",")) {
            String allowed = item.trim();
            if (allowed.isEmpty()) {
                continue;
            }
            TravelCabinClass allowedClass = of(bookingType, allowed);
            if ((actualClass != null && actualClass.isNoHigherThan(allowedClass))
                    || normalized.equalsIgnoreCase(allowed)) {
                return true;
            }
        }
        return false;
    }

    /** 实际等级不高于政策允许等级时返回 true。 */
    public boolean isNoHigherThan(TravelCabinClass allowed) {
        return allowed != null && bookingType == allowed.bookingType && rank <= allowed.rank;
    }
}
