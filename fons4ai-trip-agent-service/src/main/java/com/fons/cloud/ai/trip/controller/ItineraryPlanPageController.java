package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.trip.application.itinerary.ItineraryPlanPageApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向当前登录用户提供已审核行程方案页面，不暴露OSS对象路径。
 *
 * @author hongqy
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/itinerary/plans")
@RequiredArgsConstructor
public class ItineraryPlanPageController {

    private final SaTokenAuthTemplate saTokenAuthTemplate;
    private final ItineraryPlanPageApplicationService pageApplicationService;

    /**
     * 读取当前会话中指定规划的最新审核页面，HTML放在统一响应的data中。
     *
     * @param planId 规划标识
     * @param conversationId 会话标识
     * @param response HTTP响应，用于禁止缓存页面内容
     * @return 已审核方案的HTML内容
     */
    @GetMapping("/{planId}/page")
    public R<String> read(@PathVariable String planId, @RequestParam String conversationId,
                          HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!saTokenAuthTemplate.isLogin()) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        if (StringUtils.isBlank(planId) || StringUtils.isBlank(conversationId)) {
            return R.failed(ResultCode.PARAMS_ERROR);
        }

        String html = pageApplicationService.read(new CandidateOwner(userId, conversationId), planId);
        if (html == null) {
            return R.failed(TripAgentResultCode.ITINERARY_PLAN_PAGE_NOT_EXIST);
        }
        return R.ok(html);
    }
}
