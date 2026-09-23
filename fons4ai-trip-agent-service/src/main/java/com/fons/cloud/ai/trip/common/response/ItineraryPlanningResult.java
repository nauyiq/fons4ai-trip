package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.dto.TravelPolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 往返行程规划的完整计算结果，供保存、方案读取、后续审核及页面渲染使用。
 * 第一阶段仅表示单目的城市的“去程 + 住宿 + 返程”，不承接当天往返无住宿场景。
 *
 * <p>工具调用成功由外层 R 表达，本对象不重复定义 ok、错误码或错误消息。
 * 计算完成不代表审核通过、用户已选择或允许预订；审核报告和用户选择由后续业务分别承接。
 * 各列表应传入空列表表示没有明细，不能通过缺失数据推断没有风险。
 *
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryPlanningResult {

    /**
     * 本次规划结果标识，由应用层生成并在保存时关联可信用户及会话。
     * 后续方案读取、审核和展示使用此标识，不向模型暴露存储 Key。
     */
    private String planId;

    /**
     * 本次结果生成时间，包含明确的 UTC 偏移，不使用无时区的服务器时间。
     */
    private OffsetDateTime generatedAt;

    /**
     * 本次规划关联的可信差旅单快照。独立规划时为 null；存在时表示服务端已按当前用户验证归属，
     * 不表示差旅单已经审批通过，审核仍需判断其状态及与规划内容的一致性。
     */
    private TravelOrderReference sourceTravelOrder;

    /**
     * 本次计算采用的明确行程信息，不保存“下周五”等未解析的相对日期。
     */
    private TripRequest userRequest;

    /**
     * 用户明确表达或从长期记忆召回的真实偏好；没有偏好时为空字符串。
     */
    private String preferences;

    /**
     * 本次体验评分实际使用的天气摘要；没有可用天气数据时为空字符串，不表示天气良好。
     */
    private String weatherSummary;

    /**
     * 本次计算实际采用的差旅政策快照，由应用层查询，不由模型自行填写标准。
     */
    private TravelPolicy policy;

    /**
     * 本次计算实际采用的四维权重，不能用页面默认值代替实际权重。
     */
    private DimensionWeights dimensionWeights;

    /**
     * 所有方案金额的统一币种，第一阶段为 CNY；不同币种候选不能未经换算直接组合。
     */
    private String currency;

    /**
     * 完成有效性过滤后、选择代表方案前的组合数量，不是搜索候选数量。
     */
    private long combinationCount;

    /**
     * 代表方案，按综合分降序排列，成功计算时为 1 至 4 条。
     * 同一组合命中多个标签时合并标签，不重复输出；完整候选池另行保存。
     * 排在首位只表示综合分最高，最终推荐还需结合后续审核结论。
     */
    private List<Proposal> proposals;

    /**
     * 单目的城市往返行程请求。
     *
     * @param origin        出发城市，与搜索候选使用一致的城市标识
     * @param destination   目的城市，与搜索候选使用一致的城市标识
     * @param departureDate 去程出发日期，按出发地当地日历解释
     * @param returnDate    返程出发日期，按目的地当地日历解释，不早于去程日期
     */
    public record TripRequest(String origin, String destination, LocalDate departureDate, LocalDate returnDate) {
    }

    /**
     * 由服务端按可信用户读取的差旅单快照，供后续一致性审核使用。
     *
     * @param orderId 差旅单号
     * @param approvalId 关联审批实例标识，未发起审批时为 null
     * @param status 规划生成时的差旅单状态
     * @param origin 差旅单出发城市
     * @param destination 差旅单目的城市
     * @param departureDate 差旅单出发日期
     * @param returnDate 差旅单返回日期
     */
    public record TravelOrderReference(String orderId,
                                       String approvalId,
                                       TravelOrderStatus status,
                                       String origin,
                                       String destination,
                                       LocalDate departureDate,
                                       LocalDate returnDate) {
    }

    /**
     * 综合分计算权重，各项非负且合计为 1，不接受模型修改。
     * 初始沿用旧方案：时间 0.20、价格 0.10、偏好 0.40、体验 0.30。
     * 政策风险单独记录，不参与本阶段综合分，不能据此跳过后续审核。
     *
     * @param time       时间权重
     * @param price      价格权重
     * @param preference 用户偏好权重
     * @param experience 出行体验权重
     */
    public record DimensionWeights(BigDecimal time, BigDecimal price, BigDecimal preference, BigDecimal experience) {
    }

    /**
     * 一套真实候选组成的代表方案，不包含 LLM 编写的推荐文案或尚未产生的审核结论。
     *
     * @param proposalId       本次规划内唯一且可稳定引用的方案标识，与 planId 一起定位方案
     * @param tags             命中的代表方案标签，同一方案可有多个标签
     * @param outbound         去程交通
     * @param hotel            住宿候选，第一阶段必须有真实住宿数据
     * @param inbound          返程交通
     * @param metrics          客观费用与时间指标
     * @param scores           本次计算产生的各维评分
     * @param preferenceBasis  模型为三项候选提供的真实偏好评分理由，无理由时传空列表
     * @param policyViolations 已识别的政策违规项，不表示已被剔除或已获额外审批
     * @param warnings         已识别的政策提醒，不代替后续完整审核
     * @param experienceFlags  已识别的红眼、晚到达、长通勤、恶劣天气等体验风险
     */
    public record Proposal(
            String proposalId,
            List<ProposalTag> tags,
            TransportOption outbound,
            HotelOption hotel,
            TransportOption inbound,
            Metrics metrics,
            Scores scores,
            PreferenceBasis preferenceBasis,
            List<String> policyViolations,
            List<String> warnings,
            List<String> experienceFlags) {
    }

    /**
     * 参与本次计算的交通候选快照；价格和时间必须有效，缺失值不能按 0 参与排序。
     *
     * @param candidateId    候选池中的稳定 ID，补搜或重新读取时不能因排序改变而重新编号
     * @param type           交通业务类型，仅使用 FLIGHT 或 TRAIN，不使用 HOTEL
     * @param carrier        航空公司或铁路运营方，未知时为 null
     * @param code           航班号或车次号
     * @param origin         出发城市
     * @param destination    到达城市
     * @param departureTime  出发地当地时间及其 UTC 偏移，由供应商适配层明确解析
     * @param arrivalTime    到达地当地时间及其 UTC 偏移，跨时区时按实际时间线计算耗时
     * @param transitMinutes 出发至到达的实际耗时，单位分钟，不包含未提供的市内接驳时间
     * @param price          单人单程含已知税费价格，币种使用结果的 currency
     * @param cabinClass     供应商提供的舱位或席别原文，未知时为 null，不虚构等级
     * @param direct         是否直飞或直达，供应商未提供时为 null
     * @param refundPolicy   供应商提供的退改规则，未知时为 null，不写死“可退”或“不可退”
     */
    public record TransportOption(
            String candidateId,
            BookingType type,
            String carrier,
            String code,
            String origin,
            String destination,
            OffsetDateTime departureTime,
            OffsetDateTime arrivalTime,
            long transitMinutes,
            BigDecimal price,
            String cabinClass,
            Boolean direct,
            String refundPolicy) {
    }

    /**
     * 参与本次计算的酒店候选快照，入住晚数必须与实际入住、退房日期一致且大于 0。
     *
     * @param candidateId             候选池中的稳定 ID，标识具体酒店及报价选项
     * @param name                    酒店名称
     * @param city                    酒店所在城市
     * @param brand                   品牌，未知时为 null
     * @param starRating              明确的酒店星级，未知时为 null，不把未识别的档次默认成三星
     * @param roomType                房型，未知时为 null
     * @param checkInDate             入住日期，按酒店所在地日历解释
     * @param checkOutDate            退房日期，必须晚于入住日期
     * @param nights                  实际入住晚数，不用最少一晚的默认值掩盖无住宿或无效日期
     * @param pricePerNight           单间每晚报价，币种使用结果的 currency；第一阶段仅支持统一每晚报价
     * @param distanceToDestinationKm 距离实际到访地点的千米数，未获取时为 null，不能以 0 表示未知
     * @param breakfastIncluded       是否含早餐，未知时为 null
     * @param cancelPolicy            供应商提供的取消规则，未知时为 null
     */
    public record HotelOption(
            String candidateId,
            String name,
            String city,
            String brand,
            Integer starRating,
            String roomType,
            LocalDate checkInDate,
            LocalDate checkOutDate,
            int nights,
            BigDecimal pricePerNight,
            BigDecimal distanceToDestinationKm,
            Boolean breakfastIncluded,
            String cancelPolicy) {
    }

    /**
     * 客观计算指标，金额不附加单位字符串，不包含未实际获取的餐饮或市内交通费用。
     *
     * @param hotelTotalPrice     酒店每晚报价乘入住晚数，第一阶段按单间计算
     * @param totalPrice          去程价格 + 酒店总价 + 返程价格
     * @param totalTransitMinutes 去程和返程交通耗时之和，单位分钟
     * @param stayHours           去程到达至返程出发之间的时间，单位小时，不等于可用工作或游览时长
     */
    public record Metrics(BigDecimal hotelTotalPrice, BigDecimal totalPrice, long totalTransitMinutes,
                          BigDecimal stayHours) {
    }

    /**
     * 各维评分除未提供政策时的policy外均为 0 至 100，数值越高越好。
     * 时间、价格、体验归一化分反映本次候选池内的相对比较，不是跨候选池稳定的绝对评级。
     *
     * @param time          交通总耗时的归一化分
     * @param price         总价的归一化分
     * @param preference    去程、酒店、返程偏好分的平均值；中性评分模式各项使用 50，0 不表示排除
     * @param policy        已检查政策项目的风险评分；未提供政策时为null，不代表完整合规审核通过
     * @param experienceRaw 红眼、晚到达、长通勤、天气等扣分后的原始体验分
     * @param experience    原始体验分在当前组合中的归一化分
     * @param overall       时间、价格、偏好和归一化体验分按 dimensionWeights 加权的综合分
     */
    public record Scores(BigDecimal time, BigDecimal price, BigDecimal preference, BigDecimal policy,
                         BigDecimal experienceRaw, BigDecimal experience, BigDecimal overall) {
    }

    /**
     * 各候选的偏好评分理由，只承接真实输入，不由计算引擎补写或推测用户偏好。
     *
     * @param outbound 去程偏好评分理由
     * @param hotel    酒店偏好评分理由
     * @param inbound  返程偏好评分理由
     */
    public record PreferenceBasis(List<String> outbound, List<String> hotel, List<String> inbound) {
    }

    /**
     * 代表方案标签，枚举值作为业务编码，label 用于页面展示。
     * 时间、价格优选沿用旧算法的体验容差策略，不承诺绝对耗时最短或价格最低。
     */
    @Getter
    @AllArgsConstructor
    public enum ProposalTag {
        OVERALL_BEST("综合最佳"),
        TIME_PREFERRED("时间优选"),
        PRICE_PREFERRED("价格优选"),
        PREFERENCE_BEST("最符合偏好");

        /**
         * 页面展示名称，不作为审核状态或用户选择。
         */
        private final String label;
    }
}
