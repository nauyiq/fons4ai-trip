package com.fons.cloud.ai.trip.infrastructure.client;

import cn.hutool.core.lang.Assert;
import cn.hutool.extra.spring.SpringUtil;
import com.fons.cloud.ai.trip.common.constants.ModelType;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import io.agentscope.core.model.Model;

/**
 * 模型门面类，提供获取模型的接口
 * @author hongqy
 */
public class ModelFacade {

    public static Model getFastModel() {
        return getModel(ModelType.FAST_MODEL);
    }

    public static Model getStrongModel() {
        return getModel(ModelType.STRONG_MODEL);
    }

    public static Model getStrongThinkModel() {
        return getModel(ModelType.STRONG_THING_MODEL);
    }

    public static Model getStableModel() {
        return getModel(ModelType.STABLE_MODEL);
    }

    /**
     * 根据模型类型获取对应的LLM模型
     * @param modelType
     * @return
     */
    public static Model getModel(ModelType modelType) {
        Model model = SpringUtil.getBean(modelType.getBeanName());
        Assert.notNull(model, () -> SystemIntervalException.of("Not found LLM model with modelType:" + modelType));
        return model;
    }

}
