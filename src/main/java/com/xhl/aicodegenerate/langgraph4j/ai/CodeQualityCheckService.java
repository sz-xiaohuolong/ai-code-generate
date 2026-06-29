package com.xhl.aicodegenerate.langgraph4j.ai;

import com.xhl.aicodegenerate.langgraph4j.model.QualityResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 代码质量检查 AI 服务。
 */
public interface CodeQualityCheckService {

    /**
     * 检查生成代码是否存在会影响运行或打包的问题。
     */
    @SystemMessage(fromResource = "prompt/code-quality-check-system-prompt.txt")
    QualityResult checkCodeQuality(@UserMessage String codeContent);
}
