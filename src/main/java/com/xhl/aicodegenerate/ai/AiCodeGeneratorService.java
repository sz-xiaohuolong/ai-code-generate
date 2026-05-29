package com.xhl.aicodegenerate.ai;

import com.xhl.aicodegenerate.ai.model.HtmlCodeResult;
import com.xhl.aicodegenerate.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;


//仅作为 AI 调用接口，不写复杂逻辑
public interface AiCodeGeneratorService {

    /**
     * 生成html代码
     *
     * @param memoryId    记忆 id
     * @param userMessage 用户消息
     * @return
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(@MemoryId AppChatMemoryId memoryId, @UserMessage String userMessage);


    /**
     * 生成多文件代码
     *
     * @param memoryId    记忆 id
     * @param userMessage 用户消息
     * @return
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(@MemoryId AppChatMemoryId memoryId, @UserMessage String userMessage);


    /**
     * 生成 HTML 代码（流式）
     *
     * @param memoryId    记忆 id
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(@MemoryId AppChatMemoryId memoryId, @UserMessage String userMessage);

    /**
     * 生成多文件代码（流式）
     *
     * @param memoryId    记忆 id
     * @param userMessage 用户消息
     * @return 生成的代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(@MemoryId AppChatMemoryId memoryId, @UserMessage String userMessage);


    /**
     * 生成 Vue 项目代码（流式）
     *
     * @param memoryId    记忆 id
     * @param userMessage 用户消息
     * @return 生成过程的流式响应
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    Flux<String> generateVueProjectCodeStream(@MemoryId AppChatMemoryId memoryId, @UserMessage String userMessage);

    /**
     * 生成 Vue 项目代码（TokenStream）
     * <p>
     * Vue 工程模式依赖工具调用写文件，TokenStream 能拿到工具调用开始、工具调用完成等事件，
     * 便于前端更早看到文件写入进度。
     * </p>
     *
     * @param memoryId    记忆 id
     * @param userMessage 用户消息
     * @return 生成过程的 TokenStream
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    TokenStream generateVueProjectCodeTokenStream(@MemoryId AppChatMemoryId memoryId, @UserMessage String userMessage);


}
