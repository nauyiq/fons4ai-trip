package com.fons.cloud.ai.trip.common.constants;

/**
 * 第三方业务提供商标识，具体名称、获取地址和Key前缀由 trip.business.providers 配置。
 * @deprecated 企业统一管理供应商凭据，个人API Key能力仅保留兼容，新业务不再使用。
 * @author hongqy
 */
@Deprecated
public enum BusinessProvider {

    /** 航班助手。 */
    FLIGHT_MANAGER,

    /** 途牛旅行服务。 */
    TU_NIU

    ;

}
