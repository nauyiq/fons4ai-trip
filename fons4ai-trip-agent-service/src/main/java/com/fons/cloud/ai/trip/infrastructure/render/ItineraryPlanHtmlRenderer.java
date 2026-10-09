package com.fons.cloud.ai.trip.infrastructure.render;

import com.fons.cloud.ai.trip.common.dto.ItineraryPublicationSource;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Locale;

/**
 * 将已通过发布资格校验的行程数据渲染成独立HTML文档。
 * 仅生成页面内容，不负责OSS保存或向客户端发布事件。
 *
 * @author hongqy
 */
@Component
@RequiredArgsConstructor
public class ItineraryPlanHtmlRenderer {

    private static final String TEMPLATE_NAME = "itinerary/plan";

    private final SpringTemplateEngine templateEngine;
    private final ItineraryPlanPageViewAssembler viewAssembler;

    /**
     * 渲染可独立展示的中文行程页面。
     *
     * @param source 已通过发布资格校验的规划与审核快照
     * @return 包含内联CSS的完整HTML
     */
    public String render(ItineraryPublicationSource source) {
        Context context = new Context(Locale.SIMPLIFIED_CHINESE);
        context.setVariable("page", viewAssembler.assemble(source));
        String html = templateEngine.process(TEMPLATE_NAME, context);
        if (StringUtils.isBlank(html)) {
            throw SystemIntervalException.of("行程方案HTML渲染结果为空");
        }
        return html;
    }
}
