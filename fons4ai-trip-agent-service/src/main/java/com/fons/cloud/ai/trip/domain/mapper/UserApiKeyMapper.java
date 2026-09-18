package com.fons.cloud.ai.trip.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fons.cloud.ai.trip.domain.entity.UserApiKey;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * @author hongqy
 */
@Mapper
public interface UserApiKeyMapper extends BaseMapper<UserApiKey> {

    /** 按用户及提供商唯一键原子写入或覆盖密文，避免先删后插丢失凭据。 */
    @Insert("INSERT INTO user_api_key (user_id, provider, api_key_enc) "
            + "VALUES (#{userId}, #{provider}, #{encryptedKey}) "
            + "ON DUPLICATE KEY UPDATE api_key_enc = #{encryptedKey}, updated = CURRENT_TIMESTAMP")
    int upsertEncryptedKey(@Param("userId") String userId, @Param("provider") String provider,
                           @Param("encryptedKey") String encryptedKey);
}
