package com.fons.cloud.ai.trip.infrastructure.persistence;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.fons.cloud.ai.trip.common.dto.preference.PreferenceData;
import com.fons.cloud.ai.trip.common.exception.TripExceptions;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/** 偏好 JSON List 映射；兼容旧来源元数据，新写入仅保留当前 CRUD 所需四个字段。 */
public class PreferenceListTypeHandler extends BaseTypeHandler<List<PreferenceData.Item>> {
    /** 当前保存契约，缺失字段不能伪装为未设置。 */
    private static final Set<String> FIELDS = Set.of("key", "value", "status", "updatedAt");
    /** 仅为读取既有 JSON 保留；这些旧字段不参与偏好选择和后续保存。 */
    private static final Set<String> LEGACY_FIELDS = Set.of("confirmedAt", "source", "sourceRef", "evidence");

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<PreferenceData.Item> value, JdbcType type)
            throws SQLException {
        ps.setString(i, JSON.toJSONString(value, JSONWriter.Feature.WriteNulls));
    }

    public static List<PreferenceData.Item> parse(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            if (!(JSON.parse(json) instanceof JSONArray array)) {
                throw TripExceptions.system("偏好存储必须是 JSON 列表");
            }
            JSONArray normalized = new JSONArray();
            for (Object entry : array) {
                if (!(entry instanceof JSONObject object) || !object.keySet().containsAll(FIELDS)
                        || object.keySet().stream().anyMatch(field -> !FIELDS.contains(field) && !LEGACY_FIELDS.contains(field))) {
                    throw TripExceptions.system("偏好对象字段不符合统一契约");
                }
                // 旧记录可以直接读取；通过页面或工具修改后自然写回精简结构，无需数据库迁移任务。
                JSONObject item = new JSONObject(object);
                LEGACY_FIELDS.forEach(item::remove);
                normalized.add(item);
            }
            return JSON.parseArray(normalized.toJSONString(), PreferenceData.Item.class,
                    JSONReader.Feature.ErrorOnUnknownProperties, JSONReader.Feature.ErrorOnEnumNotMatch);
        } catch (JSONException error) {
            throw TripExceptions.system("偏好存储 JSON 无法解析", error);
        }
    }

    @Override
    public List<PreferenceData.Item> getNullableResult(ResultSet rs, String name) throws SQLException {
        return parse(rs.getString(name));
    }

    @Override
    public List<PreferenceData.Item> getNullableResult(ResultSet rs, int index) throws SQLException {
        return parse(rs.getString(index));
    }

    @Override
    public List<PreferenceData.Item> getNullableResult(CallableStatement cs, int index) throws SQLException {
        return parse(cs.getString(index));
    }
}
