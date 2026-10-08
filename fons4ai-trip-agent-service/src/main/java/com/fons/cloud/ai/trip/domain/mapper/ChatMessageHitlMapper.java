package com.fons.cloud.ai.trip.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageHitl;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息HITL扩展Mapper
 *
 * @author hongqy
 */
@Mapper
public interface ChatMessageHitlMapper extends BaseMapper<ChatMessageHitl> {
}
