package com.fons.cloud.ai.trip.common.vo;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.agent.model.response.AgentExecutionSummary;
import com.fons.cloud.ai.trip.domain.entity.ChatRequestTrace;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** 请求级展示摘要，独立于消息正文；同一 Run 的多条消息共享这一份含义。 */
@Slf4j
public record RequestTraceInfo(String runId, String state, Long usedToken, List<String> usedTools,
                               Map<String, Object> analysis, Map<String, Object> executionSummary) {
    public static RequestTraceInfo from(ChatRequestTrace trace) {
        if (trace == null) return null;
        return new RequestTraceInfo(trace.getRunId(), trace.getState() == null ? null : trace.getState().getCode(),
                trace.getUsedToken() == null || trace.getUsedToken() < 0 ? null : trace.getUsedToken(),
                StringUtils.isBlank(trace.getUsedTools()) ? List.of()
                        : Arrays.stream(trace.getUsedTools().split(",")).filter(StringUtils::isNotBlank).toList(),
                readAnalysis(trace), readExecutionSummary(trace));
    }

    /** 存量分析 JSON 缺失或损坏不影响正文读取；只映射会话摘要需要的安全字段。 */
    private static Map<String, Object> readAnalysis(ChatRequestTrace trace) {
        JSONObject value = parseObject(trace.getAnalysisContent(), trace.getRunId(), "analysis_content");
        if (value == null) return null;
        JSONObject analysis = new JSONObject();
        if (value.get("queryRewrite") instanceof String text) analysis.put("queryRewrite", text);
        if (value.get("intentRecognition") instanceof Map<?, ?> intent) {
            JSONObject result = new JSONObject();
            if (intent.get("overallReason") instanceof String text) result.put("overallReason", text);
            if (intent.get("primary") instanceof String text) result.put("primary", text);
            analysis.put("intentRecognition", result);
        }
        return analysis;
    }

    /** 不认识的版本、损坏的统计结构都退化为未知，不能被解释成零次调用。 */
    private static Map<String, Object> readExecutionSummary(ChatRequestTrace trace) {
        JSONObject value = parseObject(trace.getExecutionSummary(), trace.getRunId(), "execution_summary");
        if (value == null || !(value.get("version") instanceof Number version) || version.doubleValue() != 1
                || !(value.get("usageComplete") instanceof Boolean)
                || !(value.get("modelCalls") instanceof List<?>) || !(value.get("toolCalls") instanceof List<?>)) return null;
        try {
            // 公共摘要保留了兼容构造器；不要让 JSON 反射选择构造器，显式传入 v1 三个字段。
            AgentExecutionSummary summary = new AgentExecutionSummary(
                    value.getList("modelCalls", AgentExecutionSummary.ModelCall.class),
                    value.getList("toolCalls", AgentExecutionSummary.ToolCall.class),
                    value.getBooleanValue("usageComplete"));
            if (summary.modelCalls().stream().anyMatch(call -> StringUtils.isBlank(call.callId()))
                    || summary.toolCalls().stream().anyMatch(call -> StringUtils.isBlank(call.callId())
                            || StringUtils.isBlank(call.toolName()) || StringUtils.isBlank(call.status()))) return null;
            JSONObject result = new JSONObject();
            result.put("version", 1);
            result.put("usageComplete", summary.usageComplete());
            result.put("modelCalls", summary.modelCalls());
            result.put("toolCalls", summary.toolCalls());
            if (value.get("originRunId") instanceof String origin) result.put("originRunId", origin);
            return result;
        } catch (RuntimeException error) {
            log.warn("请求摘要结构无法解析，按未知展示，runId:{}, errorType:{}",
                    trace.getRunId(), error.getClass().getSimpleName());
            return null;
        }
    }

    private static JSONObject parseObject(String source, String runId, String field) {
        if (StringUtils.isBlank(source)) return null;
        try { return JSON.parseObject(source); }
        catch (RuntimeException error) {
            // 不把原 JSON 或异常消息写入日志，避免泄漏历史输入、工具结果及凭据。
            log.warn("请求摘要字段无法解析，按未知展示，runId:{}, field:{}", runId, field);
            return null;
        }
    }
}
