package com.xhl.aicodegenerate.utils;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Spring 上下文工具类。
 * <p>
 * LangGraph4j 的节点采用静态 create() 方法创建时，无法通过构造器注入依赖，
 * 因此在节点执行阶段通过该工具从 Spring 容器中获取真实 Bean。
 * </p>
 */
@Component
public class SpringContextUtil implements ApplicationContextAware {

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringContextUtil.applicationContext = applicationContext;
    }

    public static <T> T getBean(Class<T> beanClass) {
        if (applicationContext == null) {
            throw new IllegalStateException("Spring ApplicationContext 未初始化");
        }
        return applicationContext.getBean(beanClass);
    }
}
