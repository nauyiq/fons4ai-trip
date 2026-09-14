package com.fons.cloud.ai.trip.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fons.cloud.ai.trip.domain.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author hongqy
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
