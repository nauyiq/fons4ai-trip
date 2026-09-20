package com.fons.cloud.ai.trip.common.dto;

/**
 * 搜索分页结果，页码始终按供应商原始页计算，不因候选拆分或过滤而改变。
 *
 * @param pageNumber 本次实际查询页码，必填且从1开始
 * @param totalPages 供应商明确的总页数，未知为null；成功空结果可为0
 * @param hasNext 是否还有下一页，未知为null，不以本页候选数推断
 * @param continuationToken 后续查询凭据，不含API Key；供应商无需续查凭据时可为null。
 *                          后续请求仍携带原搜索条件，应用服务负责关联及隔离
 * @author hongqy
 */
public record SearchPagination(int pageNumber, Integer totalPages, Boolean hasNext,
                               String continuationToken) {
}
