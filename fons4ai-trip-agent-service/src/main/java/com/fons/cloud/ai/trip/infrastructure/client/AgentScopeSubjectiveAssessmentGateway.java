package com.fons.cloud.ai.trip.infrastructure.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewEvidenceSource;
import com.fons.cloud.ai.trip.common.dto.ItineraryReviewContext;
import com.fons.cloud.ai.trip.common.dto.ItineraryReviewCoverageGap;
import com.fons.cloud.ai.trip.common.dto.ItinerarySubjectiveAssessment;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewEvidence;
import com.fons.cloud.ai.trip.infrastructure.client.api.ItinerarySubjectiveAssessmentGateway;
import com.fons.cloud.ai.trip.infrastructure.prompt.PromptLoader;
import com.fons.cloud.ai.trip.infrastructure.util.ExtractUtils;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用 AgentScope 模型执行无工具的结构化主观审核。
 * 审核模型只返回待校验发现，不参与业务仲裁。
 *
 * @author hongqy
 */
@Component
public class AgentScopeSubjectiveAssessmentGateway implements ItinerarySubjectiveAssessmentGateway {

    private final Model model;
    private final String experiencePrompt = PromptLoader.loadRequired("prompt/review-experience-preference-system.md");
    private final String resiliencePrompt = PromptLoader.loadRequired("prompt/review-resilience-system.md");

    public AgentScopeSubjectiveAssessmentGateway(@Qualifier("strongModel") Model model) {
        this.model = model;
    }

    @Override
    public ItinerarySubjectiveAssessment assess(ItineraryReviewDimension dimension,
                                                ItineraryPlanningResult planningResult,
                                                ItineraryReviewContext context) {
        // 1. 根据主观审核维度选择对应的系统提示词
        if (dimension == null) {
            throw SystemIntervalException.of("主观审核维度不能为空");
        }
        String systemPrompt = switch (dimension) {
            case EXPERIENCE_AND_PREFERENCE -> experiencePrompt;
            case RESILIENCE -> resiliencePrompt;
            default -> throw SystemIntervalException.of("不支持的主观审核维度：" + dimension);
        };
        // 2. 组装规划与事实输入，避免重复发送方案的完整 JSON
        String input = JSON.toJSONString(new AssessmentInput(planningResult, modelFacts(context.facts()),
                context.coverageGaps().stream().map(ItineraryReviewCoverageGap::message).toList()));
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).name("system")
                        .content(TextBlock.builder().text(systemPrompt).build()).build(),
                Msg.builder().role(MsgRole.USER).name("review_input")
                        .content(TextBlock.builder().text(input).build()).build());
        // 3. 请求模型完成评估并提取文本结果
        List<ChatResponse> responses = model.stream(messages, null, null).collectList().block();
        String output = ExtractUtils.extractText(responses);
        if (StringUtils.isBlank(output)) {
            throw SystemIntervalException.of("主观审核模型未返回内容");
        }
        // 4. 解析结构化发现，交由应用层审核器继续校验事实与证据
        try {
            return JSON.parseObject(output.trim(), ItinerarySubjectiveAssessment.class);
        } catch (JSONException e) {
            throw SystemIntervalException.of("主观审核模型返回格式无效", e);
        }
    }

    private Map<String, ItineraryReviewEvidence> modelFacts(Map<String, ItineraryReviewEvidence> facts) {
        Map<String, ItineraryReviewEvidence> modelFacts = new LinkedHashMap<>();
        facts.forEach((id, evidence) -> {
            if (evidence.source() == ItineraryReviewEvidenceSource.PLAN_RESULT
                    && "proposal".equals(evidence.field())) {
                // plan.proposals 已包含完整方案；仅在模型输入中去掉重复的 JSON 文本。
                modelFacts.put(id, new ItineraryReviewEvidence(evidence.source(),
                        evidence.referenceId(), evidence.field(), evidence.observedAt(),
                        null, evidence.expectedValue()));
            } else {
                modelFacts.put(id, evidence);
            }
        });
        return modelFacts;
    }

    private record AssessmentInput(ItineraryPlanningResult plan, Map<String, ItineraryReviewEvidence> facts,
                                   List<String> coverageNotes) {
    }
}
