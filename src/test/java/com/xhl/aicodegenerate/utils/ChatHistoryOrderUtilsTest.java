package com.xhl.aicodegenerate.utils;

import com.xhl.aicodegenerate.model.enums.ChatHistoryMessageTypeEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ChatHistoryOrderUtilsTest {

    @Test
    void normalizeVueToolMessageOrder() {
        History user = new History(ChatHistoryMessageTypeEnum.USER.getValue(), "生成一个博客");
        History tool = new History(ChatHistoryMessageTypeEnum.AI.getValue(), "\n\n[选择工具] 写入文件\n\n[工具调用] 写入文件 src/App.vue");
        History plan = new History(ChatHistoryMessageTypeEnum.AI.getValue(), "好的，我来创建一个极简的 Vue3 博客系统。");
        History done = new History(ChatHistoryMessageTypeEnum.AI.getValue(), "极简博客系统已生成完毕。");

        List<History> result = ChatHistoryOrderUtils.normalizeVueToolMessageOrder(
                List.of(user, tool, plan, done),
                History::messageType,
                History::message
        );

        Assertions.assertEquals(List.of(user, plan, tool, done), result);
    }

    @Test
    void keepCompletionMessageAfterToolMessage() {
        History user = new History(ChatHistoryMessageTypeEnum.USER.getValue(), "生成一个博客");
        History tool = new History(ChatHistoryMessageTypeEnum.AI.getValue(), "\n\n[工具调用] 写入文件 src/App.vue");
        History done = new History(ChatHistoryMessageTypeEnum.AI.getValue(), "项目已生成完毕。");

        List<History> result = ChatHistoryOrderUtils.normalizeVueToolMessageOrder(
                List.of(user, tool, done),
                History::messageType,
                History::message
        );

        Assertions.assertEquals(List.of(user, tool, done), result);
    }

    private record History(String messageType, String message) {
    }
}
