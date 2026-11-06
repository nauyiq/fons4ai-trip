package com.fons.cloud.ai.trip.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fons.cloud.ai.trip.domain.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Result;
import com.fons.cloud.ai.trip.infrastructure.persistence.PreferenceListTypeHandler;

/**
 * @author hongqy
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
    /** 专用查询只返回偏好列，不把联系人等私有档案带入模型。 */
    @Select("SELECT user_id, travel_preferences, preference_version FROM user_profile WHERE user_id = #{userId}")
    @Results({
            @Result(column = "user_id", property = "userId"),
            @Result(column = "travel_preferences", property = "travelPreferences", typeHandler = PreferenceListTypeHandler.class),
            @Result(column = "preference_version", property = "preferenceVersion")
    })
    UserProfile selectPreferenceProfile(@Param("userId") String userId);

    /** 缺失档案才初始化；重复主键不覆盖已有联系人、城市或偏好。 */
    @Insert("INSERT INTO user_profile (user_id, preference_version) VALUES (#{userId}, 0) "
            + "ON DUPLICATE KEY UPDATE user_id = user_id")
    int ensurePreferenceProfile(@Param("userId") String userId);

    /** 列表和版本必须在同一条 SQL 中更新，禁止读后无条件覆盖。 */
    @Update("UPDATE user_profile SET travel_preferences = #{preferencesJson}, "
            + "preference_version = preference_version + 1 "
            + "WHERE user_id = #{userId} AND preference_version = #{expectedVersion}")
    int updatePreferencesWithVersion(@Param("userId") String userId,
                                     @Param("preferencesJson") String preferencesJson,
                                     @Param("expectedVersion") long expectedVersion);

}
