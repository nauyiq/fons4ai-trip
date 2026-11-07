package com.fons.cloud.ai.trip.common.dto.preference;

import com.fons.cloud.ai.trip.common.constants.PreferenceChoice;
import com.fons.cloud.ai.trip.common.constants.PreferenceGroup;
import com.fons.cloud.ai.trip.common.constants.PreferenceKey;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.exception.TripExceptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从固定偏好枚举生成页面与工具共用目录，并校验字段范围内的选项值。 */
public final class PreferenceCatalog {

    private PreferenceCatalog() {
    }

    /**
     * 页面分组及其固定选项目录。
     *
     * @param category 分组标识，JSON 沿用原有 transport / hotel code
     * @param label 页面展示的分组中文名称
     * @param icon 页面展示的分组图标
     * @param items 该分组内按固定字段顺序排列的选项目录
     */
    public record Category(
            PreferenceGroup category,
            String label,
            String icon,
            List<PreferenceData.Option> items) {
    }

    /** 分组及字段顺序均来自枚举声明，不另行维护 code、标签或选择方式。 */
    private static final List<Category> CATEGORIES = Arrays.stream(PreferenceGroup.values())
            .map(group -> new Category(group, group.getLabel(), group.getIcon(),
                    Arrays.stream(PreferenceKey.values())
                            .filter(key -> key.getGroup() == group)
                            .map(PreferenceCatalog::option)
                            .toList()))
            .toList();

    /**
     * 根据字段元数据构造兼容原 JSON 格式的目录项。
     *
     * @param key 已限定选项范围的偏好字段
     * @return 选项 code 列表与中文标签分离的目录项
     */
    private static PreferenceData.Option option(PreferenceKey key) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (PreferenceChoice choice : key.getChoices()) {
            labels.put(choice.getCode(), choice.getLabel());
        }
        return new PreferenceData.Option(key.getCode(), key.getLabel(), key.getSelectionType(),
                List.copyOf(labels.keySet()), Map.copyOf(labels));
    }

    /** 返回不可变分组目录，顺序与现有偏好页面一致。 */
    public static List<Category> categories() {
        return CATEGORIES;
    }

    /** 返回按分组与字段声明顺序排列的全部固定目录项。 */
    public static List<PreferenceData.Option> items() {
        return CATEGORIES.stream().flatMap(category -> category.items().stream()).toList();
    }

    /**
     * 取得固定字段目录；缺失字段属于业务参数错误。
     *
     * @param key 必须提供的偏好字段协议 code，使用字符串以兼容 SDK 工具参数 Schema
     * @return 与该字段对应的目录项
     */
    public static PreferenceData.Option require(String key) {
        if (key == null) {
            throw TripExceptions.validation(TripAgentResultCode.PREFERENCE_INVALID);
        }
        PreferenceKey definition = PreferenceKey.fromCode(key);
        return items().stream().filter(item -> item.key().equals(definition.getCode())).findFirst()
                .orElseThrow(() -> TripExceptions.validation(TripAgentResultCode.PREFERENCE_INVALID));
    }

    /**
     * 校验并标准化目录值；多选按目录顺序去重，避免顺序变化导致重复保存。
     *
     * @param key 限定选项集合和选择方式的字段协议 code，不能为空
     * @param value 单选为选项 code，多选为非空选项 code 列表
     * @return 通过校验的原单选值或规范化后的不可变多选列表
     */
    public static Object normalize(String key, Object value) {
        PreferenceData.Option definition = require(key);
        return switch (definition.type()) {
            case SINGLE -> {
                if (!(value instanceof String text) || !definition.options().contains(text)) {
                    throw TripExceptions.validation(TripAgentResultCode.PREFERENCE_INVALID);
                }
                yield value;
            }
            case MULTI -> {
                if (!(value instanceof List<?> values) || values.isEmpty()
                        || values.stream().anyMatch(entry -> !(entry instanceof String) || !definition.options().contains(entry))) {
                    throw TripExceptions.validation(TripAgentResultCode.PREFERENCE_INVALID);
                }
                List<String> normalized = new ArrayList<>();
                for (String code : definition.options()) {
                    if (values.contains(code)) {
                        normalized.add(code);
                    }
                }
                yield List.copyOf(normalized);
            }
        };
    }
}
