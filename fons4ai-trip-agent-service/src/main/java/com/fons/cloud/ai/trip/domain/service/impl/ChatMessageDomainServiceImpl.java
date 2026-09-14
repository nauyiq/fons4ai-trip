package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageMapper;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class ChatMessageDomainServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageDomainService {
}
