package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.common.response.PlanItineraryResult;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 往返行程规划工具（去程 + 住宿 + 返程）的客观计算引擎。
 *
 * <p>设计原则：
 * <ul>
 *   <li>本工具只做"客观数学"：时间差、价格求和、min-max 归一化、policy 硬性数值比较、组合过滤、排序。</li>
 *   <li>本工具绝不做任何"偏好判断"。偏好分完全由 LLM 事先产出（scores 入参），
 *       工具只负责按 (去+住+返)/3 合成。</li>
 * </ul>
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryPlannerTools implements BaseTool {
    public static final List<String> TOOLS = List.of("plan_itinerary");

    @Tool(
            name = "plan_itinerary",
            description = "往返行程规划客观计算引擎：自动读取搜索阶段捕获的候选交通/酒店 + 你（LLM）预先产出的偏好分，" +
                    "计算并选出代表方案，将结果自动存储（按当前用户隔离，无需也无法指定存储位置）并返回摘要。" +
                    "本工具只做客观数学，绝不解读偏好文本；偏好判断必须由你在 scores 参数里给出。"
    )
    public R<PlanItineraryResult> planItinerary(
            @ToolParam(name = "origin", description = "出发地城市，如'上海'") String origin,
            @ToolParam(name = "destination", description = "目的地城市，如'杭州'") String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD") String departureDate,
            @ToolParam(name = "return_date", description = "返程日期，格式 YYYY-MM-DD") String returnDate,
            @ToolParam(name = "preferences", description = "描述用户偏好的自由文本（来自 retrieve_from_memory），无则留空。仅供打分参考，本工具不解读。", required = false) String preferences,
            @ToolParam(name = "scores",
                    description = "LLM事先对每个候选打的偏好分 JSON。结构固定：" +
                            "{ transport_scores:{ \"T1\":{score:0-100, basis:[中文理由]}, ... }, " +
                            "hotel_scores:{ \"H1\":{score:0-100, basis:[中文理由]}, ... } }。" +
                            "key 必须与候选 id 完全一致，未打分的 id 按 0 分处理。" +
                            "留空或传 \"auto\" 时，工具自动为所有候选生成中性分(50)。",
                    required = false) String scores,
            @ToolParam(name = "policy",
                    description = "差旅政策 JSON（来自 query_travel_policy 透传）：{hotelLimit,hotelStarLimit,flightClass,trainSeatClass,approvalThreshold,advanceBookingDays}，无则留空。", required = false) String policy,
            @ToolParam(name = "weather_summary",
                    description = "天气摘要（Plan Agent 已查询的天气信息合并文本），用于出行体验评分中的恶劣天气惩罚。" +
                            "格式示例：'上海 多云 28-35°C；杭州 小雨 26-33°C'。包含暴雨/大雾/大风/雷暴等关键词时，" +
                            "飞机会被扣分，优先推荐高铁。无则留空。",
                    required = false) String weatherSummary,
            RuntimeContext context) {




        return null;
    }


    @Override
    public List<String> tools() {
        return TOOLS;
    }


}
