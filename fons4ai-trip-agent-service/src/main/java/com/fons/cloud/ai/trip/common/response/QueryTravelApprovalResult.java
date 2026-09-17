package com.fons.cloud.ai.trip.common.response;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;

/**
 * 查询审批单状态
 *
 * @author hongqy
 */
public record QueryTravelApprovalResult(
        String queryMode,
        String processInstanceId,
        String orderId,
        String userId,
        String title,
        String status,
        String submitTime,
        String updateTime,
        String remark) {

    public static QueryTravelApprovalResult of(String mode) {
        return new QueryTravelApprovalResult(mode, null, null, null, null, null, null, null, null);
    }

    public static QueryTravelApprovalResult of(String mode, ApprovalRecord record) {
        return new QueryTravelApprovalResult(mode, record.getProcessInstanceId(), record.getOrderId(), record.getUserId(), record.getTitle(), record.getStatus().getCode(),
                DateUtil.format(record.getCreated(), DatePattern.NORM_DATE_PATTERN), DateUtil.format(record.getUpdated(), DatePattern.NORM_DATE_PATTERN),
                record.getRemark());
    }

}
