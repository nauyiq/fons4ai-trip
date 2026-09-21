package com.fons.cloud.ai.trip.infrastructure.util;

import cn.hutool.core.util.StrUtil;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.ai.trip.infrastructure.matcher.IntentRuleMatcher;
import com.fons.cloud.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义匹配意图识别, 按 L1 → L2 → L3 的顺序尝试：
 * <ol>
 *   <li>L0 结构启发（前置守卫）：句中出现明显并列/顺承连词且句长达阈值时，
 *       视为疑似多意图复合句，直接跳过 L1/L2 交给 L3——L1/L2 结构上只能产出单意图，
 *       复合句短路会把多意图"吞"成单意图；</li>
 *   <li>L1 规则/关键词匹配：目标延迟 &lt; 50ms；单类命中即短路；子句级多类命中
 *       （跨目标子智能体）判为歧义，连 L2 一起跳过（L2 同样只会给单意图）；</li>
 *   <li>L2 向量相似度匹配：目标延迟 &lt; 100ms；命中即短路；</li>
 * </o;>
 *
 * @author hongqy
 */
@Slf4j
@Component
public class SemanticsMatcherIntentRecognition {
    /**
     * L0 结构启发使用的并列/顺承连词，复用 {@link IntentRuleMatcher#STRONG_CONJUNCTIONS}
     * （同一张表也用于 L1 的子句切分，只在一处维护）。刻意不含"和/跟/再/还要"等泛化词
     * （那些只做子句切分信号，交给 L1 守卫处理），避免把"顺便问一下差旅政策"这类
     * 单意图句也误伤到 L3 慢路径。
     */
    private static final Pattern MULTI_INTENT_CONJUNCTION = Pattern.compile(IntentRuleMatcher.STRONG_CONJUNCTIONS);
    /**
     * L0 生效的最小句长：短句即使含连词也大概率是单意图（如"顺便问下餐标"）。
     */
    private static final int MULTI_INTENT_MIN_LENGTH = 10;
    /**
     * 连词至少要出现在句中（而非句首）才算多意图信号，句首连词多为口语衔接词。
     */
    private static final int MULTI_INTENT_MIN_CONJUNCTION_OFFSET = 4;


    /**
     * 进行L1, L2的语义识别
     *
     * @param input
     * @return
     */
    public R<IntentRecognitionResult> semanticsRecognition(String input) {
        if (StringUtils.isBlank(input)) {
            return R.failed(TripAgentResultCode.SEMANTICS_RECOGNITION_INPUT_IS_EMPTY);
        }

        String normalized = input.trim();

        // -------- L0：多意图结构启发 --------
        if (hasMultiIntentSignal(normalized)) {
            log.info("[SEMANTICS_RECOGNITION] L0 检测到并列/顺承连词，疑似多意图复合句, 跳过 L1/L2");
            return R.failed(TripAgentResultCode.SEMANTICS_RECOGNITION_HAS_MULTI_INTENT_SIGNAL);
        }

        // -------- L1 --------
        StopWatch stopWatch = StopWatch.createStarted();
        IntentRuleMatcher.Outcome outcome = IntentRuleMatcher.evaluate(normalized);
        stopWatch.stop();

        if (outcome.verdict() == IntentRuleMatcher.Verdict.HIT) {
            // 命中L1规则
            log.info("[SEMANTICS_RECOGNITION] L1规则命中, cost:{}ms.", stopWatch.getTime(TimeUnit.MILLISECONDS));
            return R.success(outcome.result());
        }
        if (outcome.verdict() == IntentRuleMatcher.Verdict.AMBIGUOUS) {
            // 多类命中跨子智能体：L2 结构上同样只会产出单意图，一并跳过
            log.info("[SEMANTICS_RECOGNITION] L1 子句级多类命中{}, 疑似多意图, cost:{}ms.", outcome.ambiguousCategories(), stopWatch.getTime(TimeUnit.MILLISECONDS));
            return R.failed(TripAgentResultCode.SEMANTICS_RECOGNITION_HAS_MULTI_INTENT_SIGNAL);
        }

        log.debug("[SEMANTICS_RECOGNITION] L1 miss, cost:{}ms.", stopWatch.getTime(TimeUnit.MILLISECONDS));

        // -------- L2 --------
        // TODO L2 调用知识库进行向量相似度检索， 由于RAG2OKF暂未完成业务 这里标识为TODO
        return R.failed();
    }

    /**
     * L0 结构启发：判断输入是否携带明显的多意图复合句信号。
     *
     * <p>判定条件（同时满足）：
     * <ol>
     *   <li>句长 ≥ {@link #MULTI_INTENT_MIN_LENGTH}；</li>
     *   <li>含 {@link #MULTI_INTENT_CONJUNCTION} 中的并列/顺承连词；</li>
     *   <li>连词出现位置 ≥ {@link #MULTI_INTENT_MIN_CONJUNCTION_OFFSET}（排除句首衔接词）。</li>
     * </ol>
     *
     * <p>包级可见，便于单元测试。误判的代价只是走 L3 慢路径，不会路由错误。</p>
     */
    static boolean hasMultiIntentSignal(String text) {
        if (text == null || text.length() < MULTI_INTENT_MIN_LENGTH) {
            return false;
        }
        Matcher matcher = MULTI_INTENT_CONJUNCTION.matcher(text);
        return matcher.find() && matcher.start() >= MULTI_INTENT_MIN_CONJUNCTION_OFFSET;
    }


}
