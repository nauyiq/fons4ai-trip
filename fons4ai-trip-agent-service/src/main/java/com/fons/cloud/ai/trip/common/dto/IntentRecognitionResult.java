package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.constants.IntentCategory;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 意图识别的结果
 *
 * @author hongqy
 */
@Getter
public class IntentRecognitionResult {

    /**
     * 意图识别结果来源
     */
    private final Source source;

    /**
     * 意图项
     */
    private final List<IntentItem> intents;

    /**
     * 主要意图
     */
    private final IntentCategory primary;

    /**
     * 是否存在多个意图
     */
    private final boolean multiIntent;

    /**
     * 意图识别的结果的理由
     */
    private final String overallReason;

    /**
     * 当命中 L1/L2 时，附带的命中得分（向量相似度等），便于排障；LLM 兜底时为 null。
     */
    private final Double score;

    public IntentRecognitionResult(Source source,
                                   List<IntentItem> intents,
                                   IntentCategory primary,
                                   boolean multiIntent,
                                   String overallReason,
                                   Double score) {
        this.source = source;
        this.intents = intents == null ? List.of() : List.copyOf(intents);
        this.primary = primary == null ? IntentCategory.UNKNOWN : primary;
        this.multiIntent = multiIntent;
        this.overallReason = overallReason == null ? "" : overallReason;
        this.score = score;
    }


    public record IntentItem(IntentCategory category, String targetAgent, Confidence confidence, String reason) {
        public IntentItem(IntentCategory category, String targetAgent,
                          Confidence confidence, String reason) {
            this.category = category;
            this.targetAgent = targetAgent;
            this.confidence = confidence == null ? Confidence.MEDIUM : confidence;
            this.reason = reason;
        }

        public String getIntent() {
            return category == null ? IntentCategory.UNKNOWN.getCode() : category.getCode();
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "intent", getIntent(),
                    "target_agent", targetAgent == null ? "" : targetAgent,
                    "confidence", confidence.wireValue(),
                    "reason", reason == null ? "" : reason);
        }
    }


    public static IntentRecognitionResult single(Source source,
                                                 IntentCategory category,
                                                 Confidence confidence,
                                                 String reason,
                                                 Double score) {
        String target = category == null ? IntentCategory.UNKNOWN.getDefaultTargetAgent()
                : category.getDefaultTargetAgent();
        IntentItem item = new IntentItem(category == null ? IntentCategory.UNKNOWN : category,
                target, confidence, reason);
        return new IntentRecognitionResult(source,
                List.of(item),
                category == null ? IntentCategory.UNKNOWN : category,
                false,
                reason == null ? "" : reason,
                score);
    }


    public String getPrimaryIntent() {
        return primary.getCode();
    }


    /**
     * 输出与 LLM 同构的 JSON Map，用于塞进 master agent 的 system message。
     * L1/L2 命中与 L3 输出的 schema 完全一致，下游逻辑零侵入。
     */
    public Map<String, Object> toJsonMap() {
        return Map.of(
                "intents", intents.stream().map(IntentItem::toMap).toList(),
                "primary_intent", getPrimaryIntent(),
                "multi_intent", multiIntent,
                "overall_reason", overallReason);
    }


    /**
     * 结果来源（哪一层命中）。
     */
    public enum Source {
        /**
         * L1：规则/关键词匹配命中。
         */
        RULE,

        /**
         * L2：向量相似度匹配命中。
         */
        VECTOR,

        /**
         * L3：未命中 L1/L2，由 LLM 兜底产生。
         */
        LLM
    }

    /**
     * 置信度等级。
     */
    public enum Confidence {
        /**
         * 置信度高
         */
        HIGH,
        /**
         * 置信度中
         */
        MEDIUM,
        /**
         * 置信度低
         */
        LOW;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static Confidence fromWire(String value) {
            if (value == null) {
                return LOW;
            }
            return switch (value.toLowerCase()) {
                case "high" -> HIGH;
                case "medium" -> MEDIUM;
                default -> LOW;
            };
        }
    }

}
