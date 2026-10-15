package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageHitl;
import java.util.List;

/**
 * 对话消息HITL扩展领域服务
 *
 * @author hongqy
 */
public interface ChatMessageHitlDomainService extends IService<ChatMessageHitl> {

    /** 检查本会话是否有未解决或恢复失败的审批，普通聊天不能绕过它们。 */
    boolean hasUnresolvedApproval(String conversationId);

    /** 读取本Pipeline准备的完整审批批次，不能按展示来源推断恢复路由。 */
    List<ChatMessageHitl> findBatch(String conversationId, String pauseId, Long pauseVersion, String pipelineRunId);

    /**
     * 原Pipeline释放执行权后整批开放审批；调用方须在事务内核对影响行数。
     * 返回数量不足时，整批回滚，不能发布部分卡片。
     */
    int markBatchPending(String conversationId, String pauseId, Long pauseVersion, String pipelineRunId);

    /** 读取同一会话的完整批次，版本和原Pipeline由可信票证核对。 */
    List<ChatMessageHitl> findBatch(String conversationId, String pauseId);

    /** 检查稳定提交标识是否已经占用过本会话的任一批次。 */
    boolean hasResumeRequest(String conversationId, String requestId);

    /**
     * 条件占用整批票证，并登记同一个请求及恢复Run。调用方必须在事务中核对完整影响行数。
     * 后续完成、失败和未启动释放均按相同持有者更新，防止迟到请求改写新Run。
     */
    int claimBatch(String conversationId, String pauseId, Long pauseVersion, List<String> messageIds,
                   String requestId, String resumeRunId);

    /** 按精确持有者转换整批状态；仅在Run尚未启动时允许恢复PENDING并清除占用字段。 */
    int finishResume(String conversationId, String pauseId, String requestId, String resumeRunId,
                     ChatMessageHitlStatus status);

    /**
     * 按旧状态进行条件更新，保证同一审批消息只会被一次恢复请求占用。
     *
     * @param messageId 消息ID
     * @param expectedStatus 预期的原状态
     * @param newStatus 需要更新的新状态
     * @return 是否更新成功
     */
    boolean changeStatus(String messageId, ChatMessageHitlStatus expectedStatus, ChatMessageHitlStatus newStatus);
}
