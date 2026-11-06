package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.UserProfile;

/**
 * @author hongqy
 */
public interface UserProfileDomainService extends IService<UserProfile> {
    /** 只在业务偏好开启后读取新增列。 */
    UserProfile getPreferenceProfile(String userId);

    /** 当前用户缺少档案时初始化，不覆盖其他档案信息。 */
    void ensurePreferenceProfile(String userId);

    /** 使用整份偏好版本条件更新，只有一行成功写入才返回 true。 */
    boolean updatePreferencesWithVersion(String userId, String preferencesJson, long expectedVersion);
}
