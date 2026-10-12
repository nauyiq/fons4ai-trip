package com.fons.cloud.ai.trip.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 普通用户「我的差旅」入口：聚合展示当前登录用户的出差申请、审批状态与预订记录。
 * @author hongqy
 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class MyTravelController {




}
