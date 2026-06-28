package com.xhl.aicodegenerate.core;

import com.xhl.aicodegenerate.ai.AiCodeGeneratorService;
import com.xhl.aicodegenerate.ai.AiCodeGeneratorServiceFactory;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.ai.model.HtmlCodeResult;
import com.xhl.aicodegenerate.ai.model.MultiFileCodeResult;
import com.xhl.aicodegenerate.core.parser.CodeParserExecutor;
import com.xhl.aicodegenerate.core.saver.CodeFileSaverExecutor;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 代码生成外观类，组合生成和保存功能
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /**
     * 统一入口：根据类型生成并保存代码
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, AppChatMemoryId memoryId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.createAiCodeGeneratorService(memoryId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                try {
                    HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(memoryId, userMessage);
                    yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, memoryId.getAppId());
                } catch (Exception e) {
                    log.warn("HTML 结构化生成解析失败，尝试使用原始文本解析兜底: {}", e.getMessage());
                    AiCodeGeneratorService fallbackService = aiCodeGeneratorServiceFactory
                            .createAiCodeGeneratorServiceWithoutMemory(CodeGenTypeEnum.HTML);
                    String rawCode = fallbackService.generateHtmlCodeRaw(userMessage);
                    Object parsedResult = CodeParserExecutor.executeParser(rawCode, CodeGenTypeEnum.HTML);
                    yield CodeFileSaverExecutor.executeSaver(parsedResult, CodeGenTypeEnum.HTML, memoryId.getAppId());
                }
            }
            case MULTI_FILE -> {
                try {
                    MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(memoryId, userMessage);
                    yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, memoryId.getAppId());
                } catch (Exception e) {
                    log.warn("多文件结构化生成解析失败，尝试使用原始文本解析兜底: {}", e.getMessage());
                    AiCodeGeneratorService fallbackService = aiCodeGeneratorServiceFactory
                            .createAiCodeGeneratorServiceWithoutMemory(CodeGenTypeEnum.MULTI_FILE);
                    String rawCode = fallbackService.generateMultiFileCodeRaw(userMessage);
                    Object parsedResult = CodeParserExecutor.executeParser(rawCode, CodeGenTypeEnum.MULTI_FILE);
                    yield CodeFileSaverExecutor.executeSaver(parsedResult, CodeGenTypeEnum.MULTI_FILE, memoryId.getAppId());
                }
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, AppChatMemoryId memoryId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据类型创建 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.createAiCodeGeneratorService(memoryId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(memoryId, userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, memoryId.getAppId());
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(memoryId, userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, memoryId.getAppId());
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeTokenStream(memoryId, userMessage);
                yield tokenStreamToFlux(tokenStream);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }


    /**
     * 将 LangChain4j TokenStream 适配成 Reactor Flux。
     * <p>
     * TokenStream 能监听工具调用事件；这里统一封装成 JSON 字符串，
     * 下游再按生成类型选择不同流处理器。
     * </p>
     */
    private Flux<String> tokenStreamToFlux(TokenStream tokenStream) {
        return Flux.create(sink -> {
            AtomicBoolean completed = new AtomicBoolean(false);
            tokenStream
                    .onPartialResponse(partialResponse -> emit(sink, completed,
                            CodeGenStreamMessage.aiResponse(partialResponse)))
                    .onPartialThinking(partialThinking -> emit(sink, completed,
                            CodeGenStreamMessage.thinking(partialThinking.text())))
                    .onPartialToolCall(partialToolCall -> emit(sink, completed,
                            toToolRequestMessage(partialToolCall)))
                    .beforeToolExecution(beforeToolExecution -> emit(sink, completed,
                            toToolRequestMessage(beforeToolExecution)))
                    .onToolExecuted(toolExecution -> emit(sink, completed,
                            toToolExecutedMessage(toolExecution)))
                    .onCompleteResponse(response -> {
                        if (completed.compareAndSet(false, true)) {
                            sink.complete();
                        }
                    })
                    .onError(error -> {
                        if (completed.compareAndSet(false, true)) {
                            sink.error(error);
                        }
                    })
                    .start();
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private CodeGenStreamMessage toToolRequestMessage(PartialToolCall partialToolCall) {
        String id = partialToolCall.id() != null ? partialToolCall.id() : String.valueOf(partialToolCall.index());
        return CodeGenStreamMessage.toolRequest(id, partialToolCall.name(), partialToolCall.partialArguments());
    }

    private CodeGenStreamMessage toToolRequestMessage(BeforeToolExecution beforeToolExecution) {
        ToolExecutionRequest request = beforeToolExecution.request();
        return CodeGenStreamMessage.toolRequest(request.id(), request.name(), request.arguments());
    }

    private CodeGenStreamMessage toToolExecutedMessage(ToolExecution toolExecution) {
        ToolExecutionRequest request = toolExecution.request();
        return CodeGenStreamMessage.toolExecuted(request.id(), request.name(), request.arguments(), toolExecution.result());
    }

    private void emit(FluxSink<String> sink, AtomicBoolean completed, CodeGenStreamMessage message) {
        if (completed.get() || sink.isCancelled()) {
            return;
        }
        sink.next(JSONUtil.toJsonStr(message));
    }

    /**
     * 通用流式代码处理方法
     *
     * @param codeStream  代码流
     * @param codeGenType 代码生成类型
     * @return 流式响应
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
            // 实时收集代码片段
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            // 流式返回完成后保存代码
            try {
                String completeCode = codeBuilder.toString();
                // 使用执行器解析代码
                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                // 使用执行器保存代码
                File savedDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存失败: {}", e.getMessage());
            }
        });
    }



    /**
     * 生成 HTML 模式的代码并保存（流式）
     *
     * @param userMessage 用户提示词
     * @return 保存的目录
     */
    private Flux<String> generateAndSaveHtmlCodeStream(String userMessage) {
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.createAiCodeGeneratorService(
                new AppChatMemoryId(0L, 0L), CodeGenTypeEnum.HTML);
        Flux<String> result = aiCodeGeneratorService.generateHtmlCodeStream(new AppChatMemoryId(0L, 0L), userMessage);
        // 当流式返回生成代码完成后，再保存代码
        StringBuilder codeBuilder = new StringBuilder();
        return result
                .doOnNext(chunk -> {
                    // 实时收集代码片段
                    codeBuilder.append(chunk);
                })
                .doOnComplete(() -> {
                    // 流式返回完成后保存代码
                    try {
                        String completeHtmlCode = codeBuilder.toString();
                        HtmlCodeResult htmlCodeResult = CodeParser.parseHtmlCode(completeHtmlCode);
                        // 保存代码到文件
                        File savedDir = CodeFileSaver.saveHtmlCodeResult(htmlCodeResult);
                        log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存失败: {}", e.getMessage());
                    }
                });
    }

    /**
     * 生成多文件模式的代码并保存（流式）
     *
     * @param userMessage 用户提示词
     * @return 保存的目录
     */
    private Flux<String> generateAndSaveMultiFileCodeStream(String userMessage) {
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.createAiCodeGeneratorService(
                new AppChatMemoryId(0L, 0L), CodeGenTypeEnum.MULTI_FILE);
        Flux<String> result = aiCodeGeneratorService.generateMultiFileCodeStream(new AppChatMemoryId(0L, 0L), userMessage);
        // 当流式返回生成代码完成后，再保存代码
        StringBuilder codeBuilder = new StringBuilder();
        return result
                .doOnNext(chunk -> {
                    // 实时收集代码片段
                    codeBuilder.append(chunk);
                })
                .doOnComplete(() -> {
                    // 流式返回完成后保存代码
                    try {
                        String completeMultiFileCode = codeBuilder.toString();
                        MultiFileCodeResult multiFileResult = CodeParser.parseMultiFileCode(completeMultiFileCode);
                        // 保存代码到文件
                        File savedDir = CodeFileSaver.saveMultiFileCodeResult(multiFileResult);
                        log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
                    } catch (Exception e) {
                        log.error("保存失败: {}", e.getMessage());
                    }
                });
    }





    /**
     * 生成 HTML 模式的代码并保存
     *
     * @param userMessage 用户提示词
     * @return 保存的目录
     */
    private File generateAndSaveHtmlCode(String userMessage) {
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.createAiCodeGeneratorService(
                new AppChatMemoryId(0L, 0L), CodeGenTypeEnum.HTML);
        HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(new AppChatMemoryId(0L, 0L), userMessage);
        return CodeFileSaver.saveHtmlCodeResult(result);
    }

    /**
     * 生成多文件模式的代码并保存
     *
     * @param userMessage 用户提示词
     * @return 保存的目录
     */
    private File generateAndSaveMultiFileCode(String userMessage) {
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.createAiCodeGeneratorService(
                new AppChatMemoryId(0L, 0L), CodeGenTypeEnum.MULTI_FILE);
        MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(new AppChatMemoryId(0L, 0L), userMessage);
        return CodeFileSaver.saveMultiFileCodeResult(result);
    }
}
