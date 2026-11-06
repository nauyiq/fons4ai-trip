package com.fons.cloud.ai.trip.common.dto.preference;

import com.fons.cloud.ai.trip.common.constants.PreferenceOperation;
import com.fons.cloud.ai.trip.common.constants.PreferenceSelectionType;
import com.fons.cloud.ai.trip.common.constants.PreferenceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 页面与 Agent 共用的偏好 CRUD 契约；目录和值的约束统一由 PreferenceCatalog 校验。 */
public final class PreferenceData {
    private PreferenceData() {
    }

    /**
     * 一项业务偏好；未设置项仅在查询时补齐，清除项保留状态以免从旧记忆自动恢复。
     *
     * @param key       固定偏好业务编码，由 PreferenceKey 映射校验
     * @param value     单选为选项编码，多选为编码列表；未设置或清除时为 null
     * @param status    未设置、生效或已清除状态
     * @param updatedAt 本项最近成功修改的服务端时间；未设置时为 null，不用于与原生记忆仲裁
     */
    public record Item(
            String key,
            Object value,
            PreferenceStatus status,
            Instant updatedAt) {
    }

    /**
     * 页面和工具使用相同的修改项，只提交本次需要更改的字段。
     *
     * @param key       固定偏好业务编码，同一批次不能重复
     * @param operation 设置或清除操作
     * @param value     设置时使用目录允许的编码或编码列表；清除时为 null
     */
    public record Change(
            String key,
            PreferenceOperation operation,
            Object value) {
    }

    /**
     * 页面和修改工具共用的保存请求。
     *
     * @param expectedVersion 最近查询到的整份偏好版本，使用十进制字符串避免浏览器 BIGINT 精度损失
     * @param changes         本次实际修改项，其他未改动的偏好保留
     */
    public record SaveRequest(
            String expectedVersion,
            List<Change> changes) {
    }

    /**
     * 查询和保存统一返回当前快照，页面和模型始终读取同一份业务数据。
     *
     * @param version    整份偏好的十进制版本字符串，保存时作为 expectedVersion 提交
     * @param items      按固定目录顺序返回的偏好，包含未设置项和已清除项
     * @param categories 固定选项目录，供页面展示及模型选择合法编码
     */
    public record Snapshot(
            String version,
            List<Item> items,
            List<PreferenceCatalog.Category> categories) {
    }

    /**
     * 单项偏好的固定选项目录，显示名称与持久化编码分离。
     *
     * @param key          固定偏好业务编码，与快照和修改请求使用相同编码
     * @param label        页面展示名称，不作为持久化值
     * @param type         单选或多选，决定 value 使用字符串还是字符串列表
     * @param options      按目录顺序返回的合法选项编码
     * @param optionLabels 选项编码到中文名称的映射
     */
    public record Option(
            String key,
            String label,
            PreferenceSelectionType type,
            List<String> options,
            Map<String, String> optionLabels) {
    }
}
