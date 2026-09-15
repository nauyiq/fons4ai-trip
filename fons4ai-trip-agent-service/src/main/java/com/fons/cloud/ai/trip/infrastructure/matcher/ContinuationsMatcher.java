package com.fons.cloud.ai.trip.infrastructure.matcher;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用于简单判断用户输入的语义是不是 "继续任务"等 语义
 * @author hongqy
 */
public class ContinuationsMatcher {

    private ContinuationsMatcher(){}


    /** 全部扁平化的继续信号词集合（小写形式，"ok/yes/confirm/continue" 已统一为小写） */
    public static final Set<String> ALL = Set.of(
            "确定", "确认", "提交", "继续", "是的", "好的", "对", "好", "修改", "补充", "不对", "取消", "重新", "再",
            "ok", "yes", "confirm", "continue", "修改一下", "补充一下", "重新来", "再来");


    /** 按语义分组的继续信号词，便于在 prompt 中以可读形式呈现 */
    public static final Map<String, List<String>> GROUPS = Map.of(
            "确认/继续类", List.of("确定", "确认", "提交", "继续", "是的", "好的", "对", "好", "ok", "yes", "confirm", "continue"),
            "修改/补充类", List.of("修改", "补充", "修改一下", "补充一下"),
            "取消/重新类", List.of("不对", "取消", "重新", "重新来", "再来", "再"));


    /**
     * 简单判断是不是"执行继续任务"语义
     * @param input
     * @return
     */
    public static boolean isContinuation(String input) {
        String lower = input.toLowerCase();
        return ALL.stream().anyMatch(lower::equalsIgnoreCase);
    }


}


