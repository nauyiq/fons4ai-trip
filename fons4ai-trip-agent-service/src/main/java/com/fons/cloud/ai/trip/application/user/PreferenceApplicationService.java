package com.fons.cloud.ai.trip.application.user;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.fons.cloud.ai.trip.common.constants.PreferenceStatus;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.preference.PreferenceCatalog;
import com.fons.cloud.ai.trip.common.dto.preference.PreferenceData.*;
import com.fons.cloud.ai.trip.domain.entity.UserProfile;
import com.fons.cloud.ai.trip.domain.service.UserProfileDomainService;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** 偏好 CRUD 的统一入口；页面和工具共用目录校验、整份版本控制与保存结果。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceApplicationService {
    private final UserProfileDomainService profiles;
    private final TransactionTemplate transactions;

    /** 按服务端确定的当前用户读取业务偏好，不查询原生记忆或历史消息。 */
    public Snapshot query(String userId) {
        requireUser(userId);
        UserProfile profile = read(userId);
        return snapshot(version(profile), storedItems(profile));
    }

    /** 返回固定目录，保留原页面目录接口。 */
    public List<PreferenceCatalog.Category> options(String userId) {
        requireUser(userId);
        return PreferenceCatalog.categories();
    }

    /** 页面与工具修改同一份偏好，未提交字段不变。 */
    public Snapshot save(String userId, SaveRequest request) {
        return save(userId, request, () -> {});
    }

    /** 工具沿用已有取消检查，在实际写入前执行；不引入额外来源或恢复协议。 */
    public Snapshot save(String userId, SaveRequest request, Runnable beforeWrite) {
        requireUser(userId);
        if (request == null) {
            throw failure(TripAgentResultCode.PREFERENCE_INVALID);
        }
        long expected = parseVersion(request.expectedVersion());
        List<Change> changes = normalizeChanges(request.changes());
        try {
            return transactions.execute(transaction -> {
                UserProfile profile = read(userId);
                long currentVersion = version(profile);
                if (expected != currentVersion) {
                    throw failure(TripAgentResultCode.PREFERENCE_VERSION_CONFLICT);
                }
                Map<String, Item> current = storedItems(profile);
                Instant updatedAt = Instant.now();
                boolean modified = false;
                for (Change change : changes) {
                    Item old = current.get(change.key());
                    PreferenceStatus status = change.operation().getResultStatus();
                    if (old != null && old.status() == status && Objects.equals(old.value(), change.value())) {
                        continue;
                    }
                    current.put(change.key(), new Item(change.key(), change.value(), status, updatedAt));
                    modified = true;
                }
                if (!modified) {
                    return snapshot(currentVersion, current);
                }
                if (currentVersion == Long.MAX_VALUE) {
                    throw failure(TripAgentResultCode.PREFERENCE_WRITE_FAILED);
                }
                // 取消检查靠近真实写入，页面使用空操作；不再将用户消息来源带入 CRUD。
                beforeWrite.run();
                if (profile == null) {
                    profiles.ensurePreferenceProfile(userId);
                }
                List<Item> persisted = PreferenceCatalog.items().stream().map(option -> current.get(option.key()))
                        .filter(Objects::nonNull).toList();
                // 同一条 SQL 更新 JSON 和版本；联系人、城市等其他档案字段不受影响。
                if (!profiles.updatePreferencesWithVersion(userId,
                        JSON.toJSONString(persisted, JSONWriter.Feature.WriteNulls), expected)) {
                    throw failure(TripAgentResultCode.PREFERENCE_VERSION_CONFLICT);
                }
                return snapshot(currentVersion + 1, current);
            });
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (BusinessRuntimeException error) {
            throw error;
        } catch (Exception error) {
            log.error("业务偏好保存失败，userId={}", userId, error);
            throw BusinessRuntimeException.of(TripAgentResultCode.PREFERENCE_WRITE_FAILED.getCode(),
                    TripAgentResultCode.PREFERENCE_WRITE_FAILED.getMessage(), error);
        }
    }

    /** 全批次校验通过后才进入事务，避免非法选项导致部分字段保存。 */
    private List<Change> normalizeChanges(List<Change> changes) {
        if (changes == null || changes.size() > PreferenceCatalog.items().size()) {
            throw failure(TripAgentResultCode.PREFERENCE_INVALID);
        }
        Map<String, Change> normalized = new LinkedHashMap<>();
        for (Change change : changes) {
            if (change == null || normalized.containsKey(change.key()) || change.operation() == null) {
                throw failure(TripAgentResultCode.PREFERENCE_INVALID);
            }
            PreferenceCatalog.require(change.key());
            Object value = null;
            switch (change.operation()) {
                case SET -> value = PreferenceCatalog.normalize(change.key(), change.value());
                case CLEAR -> {
                    if (change.value() != null) {
                        throw failure(TripAgentResultCode.PREFERENCE_INVALID);
                    }
                }
                default -> throw failure(TripAgentResultCode.PREFERENCE_INVALID);
            }
            normalized.put(change.key(), new Change(change.key(), change.operation(), value));
        }
        return List.copyOf(normalized.values());
    }

    private UserProfile read(String userId) {
        try {
            return profiles.getPreferenceProfile(userId);
        } catch (Exception error) {
            log.error("业务偏好读取失败，userId={}", userId, error);
            throw BusinessRuntimeException.of(TripAgentResultCode.PREFERENCE_READ_FAILED.getCode(),
                    TripAgentResultCode.PREFERENCE_READ_FAILED.getMessage(), error);
        }
    }

    /** 损坏的存储不能当成未设置偏好；这里只校验当前 CRUD 使用的字段。 */
    private Map<String, Item> storedItems(UserProfile profile) {
        Map<String, Item> current = new LinkedHashMap<>();
        if (profile == null || profile.getTravelPreferences() == null) {
            return current;
        }
        try {
            for (Item item : profile.getTravelPreferences()) {
                if (item == null || current.containsKey(item.key()) || item.updatedAt() == null
                        || (item.status() != PreferenceStatus.ACTIVE && item.status() != PreferenceStatus.CLEARED)) {
                    throw failure(TripAgentResultCode.PREFERENCE_READ_FAILED);
                }
                PreferenceCatalog.require(item.key());
                if (item.status() == PreferenceStatus.ACTIVE) {
                    PreferenceCatalog.normalize(item.key(), item.value());
                } else if (item.value() != null) {
                    throw failure(TripAgentResultCode.PREFERENCE_READ_FAILED);
                }
                current.put(item.key(), item);
            }
        } catch (RuntimeException error) {
            throw BusinessRuntimeException.of(TripAgentResultCode.PREFERENCE_READ_FAILED.getCode(),
                    TripAgentResultCode.PREFERENCE_READ_FAILED.getMessage(), error);
        }
        return current;
    }

    private long version(UserProfile profile) {
        if (profile == null) {
            return 0;
        }
        if (profile.getPreferenceVersion() == null || profile.getPreferenceVersion() < 0) {
            throw failure(TripAgentResultCode.PREFERENCE_READ_FAILED);
        }
        return profile.getPreferenceVersion();
    }

    private Snapshot snapshot(long version, Map<String, Item> current) {
        List<Item> items = PreferenceCatalog.items().stream()
                .map(option -> current.getOrDefault(option.key(),
                        new Item(option.key(), null, PreferenceStatus.UNSET, null))).toList();
        return new Snapshot(Long.toString(version), items, PreferenceCatalog.categories());
    }

    private long parseVersion(String version) {
        if (version == null || !version.matches("0|[1-9][0-9]{0,18}")) {
            throw failure(TripAgentResultCode.PREFERENCE_INVALID);
        }
        try {
            return Long.parseLong(version);
        } catch (NumberFormatException error) {
            throw BusinessRuntimeException.of(TripAgentResultCode.PREFERENCE_INVALID.getCode(),
                    TripAgentResultCode.PREFERENCE_INVALID.getMessage(), error);
        }
    }

    private void requireUser(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw failure(TripAgentResultCode.LOGIN_EXPIRED);
        }
    }

    private BusinessRuntimeException failure(TripAgentResultCode code) {
        return BusinessRuntimeException.of(code);
    }
}
