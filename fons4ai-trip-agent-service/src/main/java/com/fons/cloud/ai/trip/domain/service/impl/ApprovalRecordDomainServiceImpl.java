package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;
import com.fons.cloud.ai.trip.domain.mapper.ApprovalRecordMapper;
import com.fons.cloud.ai.trip.domain.service.ApprovalRecordDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class ApprovalRecordDomainServiceImpl extends ServiceImpl<ApprovalRecordMapper, ApprovalRecord> implements ApprovalRecordDomainService {
}
