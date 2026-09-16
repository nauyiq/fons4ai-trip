package com.fons.cloud.ai.trip.domain.entity;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.ApprovalStatus;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;

/**
 * 差旅审批记录
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("approval_record")
public class ApprovalRecord extends BaseEntity {

    /**
     * 审批流程实例ID
     */
    @TableId(value = "process_instance_id", type = IdType.INPUT)
    private String processInstanceId;

    /**
     * 申请人ID
     */
    private String userId;

    /**
     * 审批标题
     */
    private String title;

    /**
     * 状态: PENDING/APPROVED/REJECTED/CANCELLED
     */
    private ApprovalStatus status;

    /**
     * 审批表单JSON
     */
    private String approvalForm;

    /**
     * 备注
     */
    private String remark;

    /**
     * 关联差旅单ID
     */
    private String orderId;

    public void cancel(String reason) {
        setStatus(ApprovalStatus.CANCELLED);
        setUpdated(new Date());
        if (StringUtils.isNotBlank(reason)) {
            setRemark(StrUtil.maxLength(reason, 255));
        }
    }
}
