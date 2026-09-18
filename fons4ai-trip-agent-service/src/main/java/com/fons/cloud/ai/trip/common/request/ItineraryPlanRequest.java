package com.fons.cloud.ai.trip.common.request;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fons.cloud.ai.trip.common.dto.TravelPolicy;
import com.fons.cloud.common.request.BaseRequest;
import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 工具层提交给应用层的往返行程规划请求。
 * 工具字符串参数应先完成归一化、日期解析及 JSON 反序列化，再组装本对象。
 * 搜索候选由应用层按可信身份和行程条件读取，不接受模型自行编造候选数据。
 * 第一阶段仅支持单目的城市的“去程 + 住宿 + 返程”，不承接当天往返无住宿场景。
 *
 * <p>本对象只声明数据契约，身份、日期、评分及候选归属校验由工具层和应用层执行。
 * 不承接人工审批、用户方案选择、超时或存储 Key。
 *
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryPlanRequest extends BaseRequest {

    /**
     * Java 序列化标识。
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前用户 ID，由工具层从可信 RuntimeContext 填入，不接受模型指定。
     */
    private String userId;

    /**
     * 当前业务会话标识，由可信运行上下文填入；用于业务数据隔离，不是模型编造的子 Agent 句柄。
     */
    private String conversationId;

    /**
     * 出发城市，去除首尾空格，与候选搜索、存储及查询使用同一城市标识。
     */
    private String origin;

    /**
     * 目的城市，去除首尾空格；第一阶段仅支持一个目的城市。
     */
    private String destination;

    /**
     * 去程出发日期，已解析为明确日期，按出发地当地日历解释。
     */
    private LocalDate departureDate;

    /**
     * 返程出发日期，按目的地当地日历解释，不早于去程日期；实际住宿必须至少一晚。
     */
    private LocalDate returnDate;

    /**
     * 用户明确表达或长期记忆召回的真实偏好，无偏好时为空字符串；计算引擎不自行解读。
     */
    private String preferences;

    /**
     * 候选偏好评分，使用稳定候选 ID 关联真实候选。
     * null 表示中性评分模式，所有参与候选使用 50 分；工具 scores 空白或 auto 应转换为 null。
     * 非 null 表示手动评分模式，未提供评分的候选沿用旧规则按 0 分处理，不自动补成 50。
     * 评分为 0 仅表示低偏好，不表示排除候选。
     */
    private CandidatePreferenceScores scores;

    /**
     * 实际采用的差旅政策，由应用层按可信用户和目的城市查询补齐。
     * 现有工具 policy 参数即使透传查询结果，也必须经应用层核验或重新查询，不能直接信任模型填写的标准。
     * 未取得政策不能据此推断没有限制。
     */
    private TravelPolicy policy;

    /**
     * 出发地与目的地的真实天气摘要，无可用数据时为空字符串，不推断为天气良好。
     */
    private String weatherSummary;

    /**
     * 明确排除的交通或酒店候选 ID，null 或空列表表示不额外排除。
     * 应校验 ID 属于本次候选池，组合计算前排除；不得以 scores 中的 0 分替代排除。
     * 工具层后续需增加相应参数，或由应用层承接已确定的修复指令，当前工具入口尚未接入该字段。
     */
    private List<String> excludedCandidateIds;

    /**
     * 候选偏好评分集合，Map 的键为稳定候选 ID，值为具体评分对象。
     * JSON 字段名沿用当前工具声明的 transport_scores、hotel_scores。
     *
     * @param transportScores 去程、返程交通候选的评分；null 或空 Map 表示未提供交通评分
     * @param hotelScores     酒店候选的评分；null 或空 Map 表示未提供酒店评分
     */
    public record CandidatePreferenceScores(
            @JSONField(name = "transport_scores") Map<String, PreferenceScore> transportScores,
            @JSONField(name = "hotel_scores") Map<String, PreferenceScore> hotelScores) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * 单个候选的偏好评分，不包含时间、价格、政策或天气等客观计算分。
     *
     * @param score 必填，0 至 100，越高越符合用户真实偏好，不允许越界或缺失
     * @param basis 偏好评分理由，来自用户真实偏好及候选事实；无理由时使用空列表，不由计算引擎编写
     */
    public record PreferenceScore(BigDecimal score, List<String> basis) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }
}
