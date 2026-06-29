package com.xhl.aicodegenerate.langgraph4j.ai;

import com.xhl.aicodegenerate.langgraph4j.model.ImageCollectionPlan;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 图片收集规划 AI 服务。
 */
public interface ImageCollectionPlanService {

    /**
     * 根据用户需求规划要收集的素材任务。
     */
    @SystemMessage(fromResource = "prompt/image-collection-plan-system-prompt.txt")
    ImageCollectionPlan planImageCollection(@UserMessage String userPrompt);
}
