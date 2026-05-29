package com.xhl.aicodegenerate.utils;

import cn.hutool.core.collection.CollUtil;
import com.xhl.aicodegenerate.model.enums.ChatHistoryMessageTypeEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 对话历史展示顺序工具。
 */
public class ChatHistoryOrderUtils {

    private static final String TOOL_SELECT_MARK = "[选择工具]";

    private static final String TOOL_EXECUTED_MARK = "[工具调用]";

    private ChatHistoryOrderUtils() {
    }

    /**
     * 修正 Vue 工程工具调用记录的展示顺序。
     * <p>
     * Vue 生成时，工具调用消息由流处理器即时落库，开场 AI 回复由 LangChain4j 记忆稍后落库，
     * 所以 DB 的 createTime 可能变成“工具调用 -> 开场回复 -> 结束回复”。这里在读取时恢复为
     * “开场回复 -> 工具调用 -> 结束回复”。
     * </p>
     */
    public static <T> List<T> normalizeVueToolMessageOrder(List<T> records,
                                                           Function<T, String> messageTypeGetter,
                                                           Function<T, String> messageGetter) {
        if (CollUtil.isEmpty(records)) {
            return records;
        }
        List<T> result = new ArrayList<>(records);
        for (int i = 0; i < result.size() - 1; i++) {
            T current = result.get(i);
            T next = result.get(i + 1);
            if (isVueToolMessage(current, messageTypeGetter, messageGetter)
                    && isNormalAiMessage(next, messageTypeGetter, messageGetter)
                    && !looksLikeCompletionMessage(messageGetter.apply(next))) {
                result.remove(i + 1);
                result.add(i, next);
                i++;
            }
        }
        return result;
    }

    private static <T> boolean isVueToolMessage(T record,
                                                Function<T, String> messageTypeGetter,
                                                Function<T, String> messageGetter) {
        if (!ChatHistoryMessageTypeEnum.AI.getValue().equals(messageTypeGetter.apply(record))) {
            return false;
        }
        String message = messageGetter.apply(record);
        return message != null && (message.contains(TOOL_SELECT_MARK) || message.contains(TOOL_EXECUTED_MARK));
    }

    private static <T> boolean isNormalAiMessage(T record,
                                                 Function<T, String> messageTypeGetter,
                                                 Function<T, String> messageGetter) {
        if (!ChatHistoryMessageTypeEnum.AI.getValue().equals(messageTypeGetter.apply(record))) {
            return false;
        }
        return !isVueToolMessage(record, messageTypeGetter, messageGetter);
    }

    private static boolean looksLikeCompletionMessage(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("生成完毕")
                || message.contains("生成完成")
                || message.contains("已生成")
                || message.contains("创建完成")
                || message.contains("已创建")
                || message.contains("所有文件已写入");
    }
}
