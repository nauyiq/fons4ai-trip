package com.fons.cloud.ai.trip.infrastructure.matcher;

import java.util.Set;

/**
 * @author hongqy
 */
public class InternationalMatcher {

    /**
     * 用中文书写的常见境外目的地（含国家 / 地区 / 城市），命中即视为国际差旅。不是所有城市和国家都包含，因为出差一般是去有业务往来的地区。
     * <p>说明：香港 / 澳门 / 台湾按简化口径视为境内，不在此列表内。</p>
     */
    private static final Set<String> INTERNATIONAL_KEYWORDS = Set.of(
            "日本", "东京", "大阪", "京都", "名古屋", "北海道", "冲绳",
            "韩国", "首尔", "釜山", "济州",
            "美国", "纽约", "洛杉矶", "旧金山", "西雅图", "华盛顿", "芝加哥", "波士顿", "拉斯维加斯", "夏威夷",
            "加拿大", "多伦多", "温哥华", "蒙特利尔",
            "英国", "伦敦", "曼彻斯特", "爱丁堡",
            "法国", "巴黎", "尼斯", "马赛",
            "德国", "柏林", "慕尼黑", "法兰克福", "汉堡",
            "意大利", "罗马", "米兰", "威尼斯", "佛罗伦萨",
            "西班牙", "马德里", "巴塞罗那",
            "葡萄牙", "里斯本",
            "荷兰", "阿姆斯特丹",
            "比利时", "布鲁塞尔",
            "瑞士", "苏黎世", "日内瓦", "伯尔尼",
            "瑞典", "斯德哥尔摩",
            "挪威", "奥斯陆",
            "丹麦", "哥本哈根",
            "芬兰", "赫尔辛基",
            "俄罗斯", "莫斯科", "圣彼得堡",
            "澳大利亚", "悉尼", "墨尔本", "布里斯班", "珀斯",
            "新西兰", "奥克兰", "惠灵顿",
            "新加坡",
            "马来西亚", "吉隆坡", "槟城",
            "泰国", "曼谷", "普吉", "清迈", "芭提雅",
            "越南", "河内", "胡志明", "岘港",
            "菲律宾", "马尼拉", "宿务",
            "印度尼西亚", "雅加达", "巴厘岛",
            "印度", "新德里", "孟买", "班加罗尔",
            "阿联酋", "迪拜", "阿布扎比",
            "沙特", "利雅得",
            "土耳其", "伊斯坦布尔",
            "以色列", "特拉维夫",
            "南非", "开普敦", "约翰内斯堡",
            "埃及", "开罗",
            "巴西", "里约", "圣保罗",
            "阿根廷", "布宜诺斯艾利斯",
            "墨西哥", "墨西哥城");

    /**
     * 简单判断目的地是否属于国际差旅：
     * <ol>
     *   <li>为空或纯 CJK 汉字且未命中境外关键词 → 视为境内；</li>
     *   <li>包含任何非 CJK 字符（英文名 / 拼音等） → 视为境外；</li>
     *   <li>命中 {@link #INTERNATIONAL_KEYWORDS} 中的国家/城市 → 视为境外。</li>
     * </ol>
     */
    public static boolean isInternational(String destination) {
        if (destination == null || destination.isBlank()) {
            return false;
        }
        String trimmed = destination.trim();
        // 命中境外关键词（子串匹配，兼容"日本东京"这类组合）
        for (String kw : INTERNATIONAL_KEYWORDS) {
            if (trimmed.contains(kw)) {
                return true;
            }
        }
        // 含非 CJK 字符（拉丁字母等）视为境外目的地
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetter(c) && !isCjk(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }

}
