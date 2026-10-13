package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.trip.application.business.TravelBusinessApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.vo.TravelOrderView;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
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


}
