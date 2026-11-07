package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/** 生成前固定的用户需求快照，不从模型输出或工具历史推断应用目标。 */
public record GenerationRequirements(String initialPrompt, List<String> previousRequests, String currentPrompt) {
    public GenerationRequirements {
        if (currentPrompt == null || currentPrompt.isBlank())
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成需求缺失，无法安全执行自动修复");
        if (initialPrompt == null || initialPrompt.isBlank()) initialPrompt = currentPrompt;
        previousRequests = List.copyOf(previousRequests);
    }

    public static GenerationRequirements capture(String initialPrompt, List<ChatMessage> history, String currentPrompt) {
        var requests = history.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .map(UserMessage::singleText).filter(text -> !text.isBlank())
                .filter(text -> !text.equals(initialPrompt) && !text.equals(currentPrompt)).toList();
        return new GenerationRequirements(initialPrompt, requests, currentPrompt);
    }

    public String describe() {
        StringBuilder text = new StringBuilder("【应用初始需求】\n").append(initialPrompt);
        if (!previousRequests.isEmpty()) {
            text.append("\n\n【此前用户要求，按时间顺序】");
            for (String request : previousRequests) text.append("\n\n").append(request);
        }
        return text.append("\n\n【本轮用户要求】\n").append(currentPrompt).toString();
    }
}
