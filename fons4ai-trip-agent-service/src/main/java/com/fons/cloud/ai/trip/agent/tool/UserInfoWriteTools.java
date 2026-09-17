package com.fons.cloud.ai.trip.agent.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户信息写工具集：更新联系/乘机人信息与常驻城市。
 * 读操作见 {@link UserInfoReadTools}，与差旅单/预订工具的 Read/Write 分离惯例保持一致。
 *
 * <p>更新入口对证件类型/证件号/手机号/性别做格式校验，拦截 LLM 传入的非法值并提示其向用户重新确认；
 * 用户档案不存在时自动创建，避免"查询提示追问、更新却无法写入"的死锁。
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInfoWriteTools {
}
