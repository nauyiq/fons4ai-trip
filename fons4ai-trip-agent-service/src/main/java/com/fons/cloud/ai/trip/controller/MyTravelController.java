package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.trip.application.business.TravelBusinessApplicationService;
import com.fons.cloud.ai.trip.application.itinerary.PublishedItineraryApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.response.PublishedItinerarySummary;
import com.fons.cloud.ai.trip.common.vo.TravelOrderView;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 普通用户「我的差旅」入口：聚合展示当前登录用户的出差申请、审批状态与预订记录。
 * @author hongqy
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/my-travel")
@RequiredArgsConstructor
public class MyTravelController {
    private final SaTokenAuthTemplate saTokenAuthTemplate;
    private final TravelBusinessApplicationService travelBusinessApplicationService;
    private final PublishedItineraryApplicationService publishedItineraryApplicationService;

    /**
     * 获取登录用的中我的差旅订单
     * @return
     */
    @GetMapping("/orders")
    public R<List<TravelOrderView>> orders() {
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        return travelBusinessApplicationService.getUserOrders(userId);
    }

    /** 获取当前用户差旅单的最新已发布规划和审核摘要。 */
    @GetMapping("/orders/{orderId}/plan")
    public R<PublishedItinerarySummary> plan(@PathVariable String orderId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!saTokenAuthTemplate.isLogin()) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        if (StringUtils.isBlank(orderId)) {
            return R.failed(ResultCode.PARAMS_ERROR);
        }
        return publishedItineraryApplicationService.getSummary(userId, orderId.trim());
    }

    /** 获取当前用户差旅单的最新已发布页面，内容放在统一响应的data中。 */
    @GetMapping("/plan-html/{orderId}")
    public R<String> planHtml(@PathVariable String orderId, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!saTokenAuthTemplate.isLogin()) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentResultCode.LOGIN_EXPIRED);
        }
        if (StringUtils.isBlank(orderId)) {
            return R.failed(ResultCode.PARAMS_ERROR);
        }
        return publishedItineraryApplicationService.getHtml(userId, orderId.trim());
    }

}
