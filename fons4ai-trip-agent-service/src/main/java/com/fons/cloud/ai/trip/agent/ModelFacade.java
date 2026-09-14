package com.fons.cloud.ai.trip.agent;

import cn.hutool.extra.spring.SpringUtil;
import io.agentscope.core.model.Model;

/**
 * 模型门面类，提供获取模型的接口
 * @author hongqy
 */
public class ModelFacade {
    public static final String FAST_MODEL = "fastModel";

    /**
     * 获取快速模型，常用于简单任务
     * @return
     */
    public static Model getFastModel() {
        return SpringUtil.getBean(FAST_MODEL);
    }


}
