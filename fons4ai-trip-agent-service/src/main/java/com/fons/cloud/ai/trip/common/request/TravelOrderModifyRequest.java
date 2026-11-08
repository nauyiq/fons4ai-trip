package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.common.request.BaseRequest;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 修改差旅单请求：仅提供需变更的字段，省略或空白表示保留原值，不支持清空。
 * @author hongqy
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelOrderModifyRequest extends BaseRequest {

    /**
     * 用户id，由工具入口从 RuntimeContext（可信来源）填入，不接受模型声明的归属
     */
    private String userId;

    /**
     * 要修改的差旅单ID
     */
    private String orderId;

    /**
     * 目的地城市，省略或空白保留原值
     */
    private String destination;

    /**
     * 出发城市，省略或空白保留原值
     */
    private String departureCity;

    /**
     * 出发日期，YYYY-MM-DD；省略或空白保留原值
     */
    private String departureDate;

    /**
     * 返回日期，YYYY-MM-DD；省略或空白保留原值
     */
    private String returnDate;

    /**
     * 新出差事由，省略或空白保留原值
     */
    private String purpose;

    /**
     * 是否强制修改已审批通过的差旅单, 默认false
     */
    @Builder.Default
    private boolean force = false;

    /**
     * 校验仅依赖请求自身的字段：orderId 非空，以及可选日期的格式与真实日期。
     * 由工具入口在调用应用服务前显式调用，不引入 Bean Validation。
     *
     * @return 错误描述列表，按 orderId、userId、出发日期、返回日期的顺序汇集；
     *         orderId 为空时仅返回该项错误，保持既有校验顺序
     */
    public List<String> validateSelf() {
        if (StringUtils.isBlank(orderId)) {
            return List.of("order_id 不能为空");
        }
        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(userId)) {
            errors.add("userId不能为空");
        }
        addDateError(errors, departureDate, "departure_date（出发日期）");
        addDateError(errors, returnDate, "return_date（返回日期）");
        return errors;
    }

    /**
     * 校验可选日期字段：省略或空白保留原值、不做校验；非空时必须是长度 10 的真实 YYYY-MM-DD 日期。
     */
    private static void addDateError(List<String> errors, String date, String fieldName) {
        if (StringUtils.isBlank(date)) {
            return;
        }
        if (date.length() != 10) {
            errors.add(fieldName + " 格式错误，必须为 YYYY-MM-DD（如 2026-07-15），实际值为：" + date);
            return;
        }
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            errors.add(fieldName + " 不是有效的日期，必须为 YYYY-MM-DD，实际值为：" + date);
        }
    }
}
