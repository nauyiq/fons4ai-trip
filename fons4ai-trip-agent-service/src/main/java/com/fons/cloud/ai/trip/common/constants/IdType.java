package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 乘机人证件类型，与原差旅业务的证件类型编码一致。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum IdType {
    ID_CARD(0, "身份证"),
    PASSPORT(1, "护照"),
    OTHER(2, "其他"),
    HOME_RETURN_PERMIT(3, "回乡证"),
    MILITARY_OFFICER_CARD(4, "军官证"),
    POLICE_OFFICER_CARD(5, "警官证"),
    HONG_KONG_MACAO_TRAVEL_PERMIT(6, "港澳通行证"),
    TAIWAN_COMPATRIOT_PERMIT(7, "台胞证"),
    TAIWAN_TRAVEL_PERMIT(8, "台湾通行证"),
    FOREIGN_PERMANENT_RESIDENT_ID_CARD(9, "外国人永久居留身份证");

    /** 存入数据库的证件类型编码。 */
    @EnumValue
    private final Integer code;

    /** 中文证件类型名称。 */
    private final String label;

    public static IdType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (IdType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
