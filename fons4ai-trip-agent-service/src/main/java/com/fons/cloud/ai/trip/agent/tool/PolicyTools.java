package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.application.business.TravelPolicyApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.TravelPolicy;
import com.fons.cloud.ai.trip.common.response.PolicyCheckResult;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 差旅政策工具集：查询政策标准 + 合规校验
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyTools implements BaseTool {
    public static final List<String> TOOLS = List.of("query_travel_policy", "check_travel_policy");

    private final TravelPolicyApplicationService travelPolicyApplicationService;

    /**
     * 按运行时可信用户身份查询目的城市的政策，不接受模型提供用户ID或职级。
     */
    @Tool(name = "query_travel_policy", description = "查询当前用户在指定目的城市的差旅政策，返回舱位、席别、酒店每晚上限及补贴等标准。"
            + "多城市分别查询，以 data.destinationCity 确认适用城市；查询成功不代表审批通过或允许直接预订。")
    public R<TravelPolicy> queryTravelPolicy(RuntimeContext context,
                                           @ToolParam(name = "city", description = "目的城市，必填，如上海；多城市分别调用") String city) {
        String userId = context.getUserId();
        String destinationCity = StringUtils.trimToEmpty(city);
        log.info("[TOOL][query_travel_policy] userId={}, city={}", userId, destinationCity);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        if (destinationCity.isEmpty()) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "city 不能为空，请提供目的城市");
        }

        try {
            TravelPolicy policy = travelPolicyApplicationService.getPolicy(userId, destinationCity);
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "差旅政策查询成功，请按目的城市使用对应标准。", policy);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][query_travel_policy] 政策查询未完成，userId={}, city={}, code={}", userId, destinationCity, e.getCode());
            return R.failed(resolveBusinessErrorCode(e).getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][query_travel_policy] 政策查询失败，userId={}, city={}", userId, destinationCity, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "差旅政策查询失败，不能据此判断没有政策限制，请稍后重试或联系管理员。");
        }
    }

    /**
     * 查询当前用户、目的城市对应的政策后校验单笔摘要，不信任模型传入的政策标准。
     * 工具执行成功与订单合规分别由外层 SUCCESS 和 data.compliant 表达。
     */
    @Tool(name = "check_travel_policy", description = "按当前用户和目的城市的政策校验单笔订单，仅检查机票舱位、酒店每晚金额或火车席别。"
            + "SUCCESS 表示校验已执行，是否符合标准看 data.compliant；为 false 时读取 violations、suggestions 修正或补充信息。"
            + "不检查酒店星级、补贴、提前预订天数及审批阈值，不代表审批通过或允许直接预订。")
    public R<PolicyCheckResult> checkTravelPolicy(RuntimeContext context,
                                                @ToolParam(name = "city", description = "订单对应的目的城市，必填；多城市分别校验") String city,
                                                @ToolParam(name = "order_summary", description = "单笔订单摘要JSON。type 为 FLIGHT/HOTEL/TRAIN，忽略大小写和首尾空格；"
                                                        + "FLIGHT 提供 flightClass（舱位），TRAIN 提供 seatClass（席别），HOTEL 提供 amount（每晚房费，元，非总额）；"
                                                        + "如 {\"type\":\"HOTEL\",\"amount\":450}") String orderSummary) {
        String userId = context.getUserId();
        String destinationCity = StringUtils.trimToEmpty(city);
        log.info("[TOOL][check_travel_policy] userId={}, city={}", userId, destinationCity);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        if (destinationCity.isEmpty()) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "city 不能为空，请提供目的城市");
        }
        if (StringUtils.isBlank(orderSummary)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "order_summary 不能为空，请提供单笔订单摘要JSON");
        }

        try {
            TravelPolicy policy = travelPolicyApplicationService.getPolicy(userId, destinationCity);
            PolicyCheckResult result = travelPolicyApplicationService.checkCompliance(orderSummary.trim(), policy);
            log.info("[TOOL][check_travel_policy] userId={}, city={}, compliant={}", userId, destinationCity, result.compliant());
            String message = result.compliant() ? "订单摘要符合本次检查的政策标准，不代表审批通过。"
                    : "订单摘要超出政策标准或信息不足，请根据 violations 和 suggestions 处理。";
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, result);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][check_travel_policy] 政策校验未完成，userId={}, city={}, code={}", userId, destinationCity, e.getCode());
            return R.failed(resolveBusinessErrorCode(e).getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][check_travel_policy] 政策校验失败，userId={}, city={}", userId, destinationCity, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "差旅政策校验失败，尚不能判断订单是否符合标准，请稍后重试或联系管理员。");
        }
    }

    /** 应用层业务码转换为工具契约码，系统或未识别错误不降级成参数错误。 */
    private TripAgentToolResultCode resolveBusinessErrorCode(BusinessRuntimeException e) {
        if (ResultCode.PARAMS_ERROR.getCode().equals(e.getCode())) {
            return TripAgentToolResultCode.INVALID_PARAM;
        }
        return TripAgentToolResultCode.INTERNAL_ERROR;
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }

}
