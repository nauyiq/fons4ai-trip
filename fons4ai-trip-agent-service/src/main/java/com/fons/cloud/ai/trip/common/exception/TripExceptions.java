package com.fons.cloud.ai.trip.common.exception;

import com.fons.cloud.common.exception.AuthenticationException;
import com.fons.cloud.common.exception.BusinessRuntimeException;
import com.fons.cloud.common.exception.ExceptionCode;
import com.fons.cloud.common.exception.ExceptionType;
import com.fons.cloud.common.exception.ExternalException;
import com.fons.cloud.common.exception.SystemIntervalException;
import com.fons.cloud.common.exception.ValidationException;
import com.fons.cloud.common.result.Result;
import com.fons.cloud.common.result.ResultCode;

/** 构造 Trip 使用的异常定义，保留框架错误码和明确的异常分类。 */
public final class TripExceptions {

    private static final String MODULE = "TRIP";

    private TripExceptions() {
    }

    /** 使用框架参数错误码及调用方说明。 */
    public static ValidationException invalidParameter(String message) {
        return invalidParameter(message, null);
    }

    /** 参数错误需要保留底层解析原因时使用。 */
    public static ValidationException invalidParameter(String message, Throwable cause) {
        return new ValidationException(ExceptionCode.of(ResultCode.PARAMS_ERROR.getCode(), message,
                ExceptionType.VALIDATION, ResultCode.PARAMS_ERROR.module()), cause);
    }

    /** 使用 Trip 结果码表达请求校验失败。 */
    public static ValidationException validation(Result result) {
        return validation(result, null);
    }

    /** 使用 Trip 结果码表达请求校验失败，并保留原因。 */
    public static ValidationException validation(Result result, Throwable cause) {
        return new ValidationException(ExceptionCode.of(result, ExceptionType.VALIDATION, MODULE), cause);
    }

    /** 使用现有工具结果码表达参数错误。 */
    public static ValidationException validation(String code, String message) {
        return new ValidationException(ExceptionCode.of(code, message, ExceptionType.VALIDATION, MODULE));
    }

    /** 保留框架非法请求错误码的分类和模块。 */
    public static ValidationException invalidRequest(String message) {
        return new ValidationException(ExceptionCode.of(ResultCode.ILLEGAL_REQUEST_LIMITED.getCode(), message,
                ResultCode.ILLEGAL_REQUEST_LIMITED.exceptionType(), ResultCode.ILLEGAL_REQUEST_LIMITED.module()));
    }

    /** 使用 Trip 结果码表达身份认证失败。 */
    public static AuthenticationException authentication(Result result) {
        return new AuthenticationException(ExceptionCode.of(result, ExceptionType.AUTHENTICATION, MODULE));
    }

    /** 使用 Trip 结果码表达业务状态或业务规则失败。 */
    public static BusinessRuntimeException business(Result result) {
        return business(result, null);
    }

    /** 使用 Trip 结果码表达业务失败，并保留底层原因。 */
    public static BusinessRuntimeException business(Result result, Throwable cause) {
        return new BusinessRuntimeException(ExceptionCode.of(result, ExceptionType.BUSINESS, MODULE), cause);
    }

    /** 保留现有对外错误码及说明。 */
    public static BusinessRuntimeException business(String code, String message) {
        return new BusinessRuntimeException(ExceptionCode.of(code, message, ExceptionType.BUSINESS, MODULE));
    }

    /** 保留框架无效数据错误码的分类和模块。 */
    public static BusinessRuntimeException invalidData(String message) {
        return new BusinessRuntimeException(ExceptionCode.of(ResultCode.INVALID_DATA.getCode(), message,
                ResultCode.INVALID_DATA.exceptionType(), ResultCode.INVALID_DATA.module()));
    }

    /** 内部状态、配置或持久化故障使用框架系统错误码。 */
    public static SystemIntervalException system(String message) {
        return system(message, null);
    }

    /** 内部故障需要保留底层原因时使用。 */
    public static SystemIntervalException system(String message, Throwable cause) {
        return new SystemIntervalException(ExceptionCode.of(ResultCode.SYSTEM_INTERVAL_ERROR.getCode(), message,
                ResultCode.SYSTEM_INTERVAL_ERROR.exceptionType(), ResultCode.SYSTEM_INTERVAL_ERROR.module()), cause);
    }

    /** 使用已有 Trip 结果码表达内部故障。 */
    public static SystemIntervalException system(Result result, Throwable cause) {
        return new SystemIntervalException(ExceptionCode.of(result, ExceptionType.SYSTEM, MODULE), cause);
    }

    /** 供应商调用或响应协议异常保留原有系统错误码。 */
    public static ExternalException external(String message) {
        return external(message, null);
    }

    /** 外部故障沿用历史系统错误码，按实际来源标记为 Trip 外部异常。 */
    public static ExternalException external(String message, Throwable cause) {
        return new ExternalException(ExceptionCode.of(ResultCode.SYSTEM_INTERVAL_ERROR.getCode(), message,
                ExceptionType.EXTERNAL, MODULE), cause);
    }
}
