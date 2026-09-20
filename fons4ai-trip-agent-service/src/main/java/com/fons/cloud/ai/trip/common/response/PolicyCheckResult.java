package com.fons.cloud.ai.trip.common.response;

import java.util.List;

/**
 * 单笔订单摘要的差旅政策校验结果，不代表审批通过或允许直接预订。
 * 信息不足时 compliant 为 false，原因和补充建议分别写入 violations、suggestions。
 *
 * @author hongqy
 */
public record PolicyCheckResult(
        boolean compliant,
        List<String> violations,
        List<String> suggestions) {
}
