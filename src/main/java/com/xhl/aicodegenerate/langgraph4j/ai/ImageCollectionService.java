package com.xhl.aicodegenerate.langgraph4j.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 图片收集 AI 服务。
 */
public interface ImageCollectionService {

    /**
     * 根据用户提示词收集图片素材说明。
     *
     * @param userPrompt 用户网站需求
     * @return 图片资源说明文本
     */
    @SystemMessage(fromResource = "prompt/image-collection-system-prompt.txt")
    String collectImages(@UserMessage String userPrompt);
}
