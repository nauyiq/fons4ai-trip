package com.fons.cloud.ai.trip.application.news;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.DestinationNewsTopic;
import com.fons.cloud.ai.trip.common.dto.DestinationNewsQueryResult;
import com.fons.cloud.ai.trip.common.dto.DestinationNewsQueryResult.NewsArticle;
import com.fons.cloud.ai.trip.infrastructure.client.DestinationNewsClient;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对话查询和行程审核共用的资讯规则：单页检索、城市/主题候选门槛及基础去重。
 * 关键词命中只是候选证据，不代表新闻已经影响当前行程；不翻页、不自动放宽或补查。
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DestinationNewsQueryApplicationService {

    /** 同一规范化规则用于城市、标题和摘要，避免全角及空白差异影响比较。 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Client只负责HTTP协议，业务筛选在本服务完成。 */
    private final DestinationNewsClient destinationNewsClient;

    /**
     * 查询城市资讯并返回经过基础筛选的候选，原始返回数量保持不变。
     * 未配置返回available=false；外部失败保留原异常，不能折算为成功空结果。
     *
     * @param city 原始城市文本，不接受完整供应商查询表达式
     * @param topic 已解析的业务主题，不允许为null
     * @return 单页原始数量及有城市/主题证据、已基础去重的候选列表
     */
    public DestinationNewsQueryResult queryNews(String city, DestinationNewsTopic topic) {
        String normalizedCity = normalizeCity(city);
        Assert.notNull(topic, () -> invalidParam("资讯主题不能为空"));
        String expression = buildExpression(normalizedCity, topic);
        DestinationNewsQueryResult raw = destinationNewsClient.queryNews(expression);
        if (!raw.available()) {
            return raw;
        }

        Pattern cityEvidence = cityEvidencePattern(normalizedCity);
        List<NewsArticle> retained = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        for (NewsArticle article : raw.news()) {
            String evidence = normalizeText(article.title() + " " + StringUtils.defaultString(article.description()));
            if (!cityEvidence.matcher(evidence).find() || !hasTopicEvidence(evidence, topic)) {
                continue;
            }
            String linkKey = normalizeLink(article.link());
            String titleKey = normalizeText(article.title());
            if (seenLinks.contains(linkKey) || seenTitles.contains(titleKey)) {
                continue;
            }
            // 只记录已保留条目的键，保持供应商次序，不用另一篇转载替换第一条来源。
            retained.add(article);
            seenLinks.add(linkKey);
            seenTitles.add(titleKey);
        }
        log.info("[DestinationNewsQueryApplicationService] 资讯候选筛选完成，city={}, topic={}, retrievedCount={}, retainedCount={}",
                normalizedCity, topic.getCode(), raw.retrievedCount(), retained.size());
        return new DestinationNewsQueryResult(raw.query(), raw.source(), raw.available(), raw.retrievedCount(), retained);
    }

    /** 只规范明确的城市文本，不猜英文别名、地理归属或其他行政区后缀。 */
    private String normalizeCity(String city) {
        Assert.notBlank(city, () -> invalidParam("city 不能为空，请提供目的城市"));
        validateCityCharacters(city);
        String normalized = normalizeWhitespace(city);
        // NFKC可能将全角引号、反斜杠变为查询语法字符，因此规范后再检查一次。
        validateCityCharacters(normalized);
        Assert.notBlank(normalized, () -> invalidParam("city 不能为空，请提供目的城市"));
        if (normalized.endsWith("市")) {
            String withoutSuffix = normalized.substring(0, normalized.length() - 1);
            if (withoutSuffix.codePointCount(0, withoutSuffix.length()) >= 2
                    && withoutSuffix.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                return withoutSuffix;
            }
        }
        return normalized;
    }

    private void validateCityCharacters(String city) {
        Assert.isTrue(city.codePoints().noneMatch(c -> Character.isISOControl(c) || c == '"' || c == '\\'),
                () -> invalidParam("city 不能包含控制字符、双引号或反斜杠，请提供城市名称"));
    }

    /** 城市固定为短语；只有服务端枚举中的词能够扩展OR条件。长度仍由Client按现有配置校验。 */
    private String buildExpression(String city, DestinationNewsTopic topic) {
        String cityPhrase = "\"" + city + "\"";
        return topic == DestinationNewsTopic.GENERAL ? cityPhrase
                : cityPhrase + " AND (" + String.join(" OR ", topic.getQueryKeywords()) + ")";
    }

    /** 中文按完整城市文本查找；纯拉丁名称增加单词边界，避免York匹配Yorkshire。 */
    private Pattern cityEvidencePattern(String city) {
        String cityText = normalizeText(city);
        boolean latinCity = cityText.codePoints().anyMatch(Character::isLetter)
                && cityText.codePoints().filter(Character::isLetter)
                .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN);
        String literal = Pattern.quote(cityText);
        return Pattern.compile(latinCity ? "(?<![\\p{L}\\p{N}_])" + literal + "(?![\\p{L}\\p{N}_])" : literal);
    }

    private boolean hasTopicEvidence(String evidence, DestinationNewsTopic topic) {
        return topic == DestinationNewsTopic.GENERAL || topic.getQueryKeywords().stream().anyMatch(evidence::contains);
    }

    private String normalizeText(String text) {
        return normalizeWhitespace(text).toLowerCase(Locale.ROOT);
    }

    private String normalizeWhitespace(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll(" ").trim();
    }

    /**
     * 去重键只做保守的URL规范化，不访问外链，不清空query或合并不同新闻ID。
     * 非法或不可解释链接保留精确文本键，避免一条来源格式问题改变整个查询的成功语义。
     */
    private String normalizeLink(String link) {
        try {
            URI uri = URI.create(link);
            String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                return link;
            }
            StringBuilder key = new StringBuilder(scheme).append("://");
            if (uri.getRawUserInfo() != null) {
                key.append(uri.getRawUserInfo()).append('@');
            }
            key.append(uri.getHost().toLowerCase(Locale.ROOT));
            int port = uri.getPort();
            if (port >= 0 && !("http".equals(scheme) && port == 80) && !("https".equals(scheme) && port == 443)) {
                key.append(':').append(port);
            }
            key.append(StringUtils.defaultIfEmpty(uri.getRawPath(), "/"));
            String query = removeTrackingParameters(uri.getRawQuery());
            if (StringUtils.isNotEmpty(query)) {
                key.append('?').append(query);
            }
            return key.toString();
        } catch (IllegalArgumentException e) {
            return link;
        }
    }

    /** 只删除utm_*，其他参数及其顺序、编码原样保留，尤其不能删除百度等来源的id。 */
    private String removeTrackingParameters(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        return Arrays.stream(rawQuery.split("&", -1))
                .filter(parameter -> !isTrackingParameter(parameter))
                .collect(Collectors.joining("&"));
    }

    private boolean isTrackingParameter(String parameter) {
        int separator = parameter.indexOf('=');
        String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        return name.toLowerCase(Locale.ROOT).startsWith("utm_");
    }

    private BusinessRuntimeException invalidParam(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }
}
