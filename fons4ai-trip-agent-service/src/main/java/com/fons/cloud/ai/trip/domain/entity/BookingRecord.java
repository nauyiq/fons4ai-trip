package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.BizType;
import com.fons.cloud.ai.trip.common.constants.BookingStatus;
import com.fons.cloud.ai.trip.common.constants.PaymentStatus;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

import java.math.BigDecimal;
import java.util.Date;

/**
 * Agent预订记录（机票/酒店/火车票等，兼容多平台）
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@TableName("booking_record")
public class BookingRecord extends BaseEntity {

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 内部预订单号（系统生成，全局唯一）
     */
    private String bookingId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 关联会话ID（chat_conversation.conversation_id）
     */
    private String conversationId;

    /**
     * 关联差旅单ID（travel_order.order_id，可为空）
     */
    private String travelOrderId;

    /**
     * 预订业务类型: FLIGHT/HOTEL/TRAIN/TICKET/CRUISE/VACATION
     */
    private BizType bizType;

    /**
     * 预订平台: tuniu / rolling-go-hotel / flight-manager 等
     */
    private String platform;

    /**
     * 外部平台主订单号
     */
    private String externalOrderNo;

    /**
     * 统一预订状态: CREATED/PENDING_PAYMENT/PAID/CONFIRMED/COMPLETED/CANCELLED/REFUNDED/FAILED
     */
    private BookingStatus status;

    /**
     * 外部平台原始状态码/文案（保留平台原值）
     */
    private String externalStatus;

    /**
     * 支付状态: UNPAID/PAID/REFUNDED
     */
    private PaymentStatus paymentStatus;

    /**
     * 预订标题（如"北京→上海 MU5101"或酒店名）
     */
    private String title;

    /**
     * 订单总金额（元）
     */
    private BigDecimal totalAmount;

    /**
     * 币种
     */
    private String currency;

    /**
     * 联系人姓名
     */
    private String contactName;

    /**
     * 联系人手机号
     */
    private String contactPhone;

    /**
     * 服务开始时间（出发/入住时间）
     */
    private Date startTime;

    /**
     * 服务结束时间（到达/离店时间）
     */
    private Date endTime;

    /**
     * 平台原始/明细信息JSON（航班号、房型、乘客、行程等）
     */
    private String detail;

    /**
     * 备注
     */
    private String remark;

    /**
     * 下单时间
     */
    private Date bookedAt;

    /**
     * 逻辑删除标志
     */
    @TableLogic
    private Integer deleted;

}
