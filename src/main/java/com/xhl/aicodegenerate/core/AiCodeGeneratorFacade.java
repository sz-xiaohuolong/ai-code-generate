package com.xhl.aicodegenerate.core;

import com.xhl.aicodegenerate.ai.AiCodeGeneratorService;
import com.xhl.aicodegenerate.ai.AiCodeGeneratorServiceFactory;
import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.ai.model.HtmlCodeResult;
import com.xhl.aicodegenerate.ai.model.MultiFileCodeResult;
import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.core.parser.CodeParserExecutor;
import com.xhl.aicodegenerate.core.saver.CodeFileSaverExecutor;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.exception.IncompleteCodeException;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.Map;

/**
 * AI 代码生成外观类，组合生成和保存功能
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    private static final String VUE_PROJECT_DIR_PREFIX = "vue_project_";

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private com.xhl.aicodegenerate.service.AppScreenshotService appScreenshotService;

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
        File savedDir = switch (codeGenTypeEnum) {
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
        triggerScreenshot(memoryId.getAppId(), codeGenTypeEnum, savedDir);
        return savedDir;
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, AppChatMemoryId memoryId) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, memoryId, null, true);
    }

    /** 工作流负责最终质检、构建和截图，生成阶段只写入源码。 */
    public Flux<String> generateAndSaveCodeStreamForWorkflow(String userMessage, CodeGenTypeEnum type,
                                                            AppChatMemoryId memoryId, ChatMemoryProvider memoryProvider) {
        return generateAndSaveCodeStream(userMessage, type, memoryId, memoryProvider, false);
    }

    private Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum,
                                                   AppChatMemoryId memoryId, ChatMemoryProvider memoryProvider,
                                                   boolean finalizeProject) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据类型创建 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = memoryProvider == null
                ? aiCodeGeneratorServiceFactory.createAiCodeGeneratorService(memoryId, codeGenTypeEnum)
                : aiCodeGeneratorServiceFactory.createAiCodeGeneratorService(memoryId, codeGenTypeEnum, memoryProvider);
        return switch (codeGenTypeEnum) {
            case HTML, MULTI_FILE -> generateNativeCode(aiCodeGeneratorService, userMessage,
                    codeGenTypeEnum, memoryId, finalizeProject);
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeTokenStream(memoryId, userMessage);
                yield tokenStreamToFlux(tokenStream, memoryId, finalizeProject);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /** 完整性失败只重生成一次；供应商异常、取消和超时直接交回任务处理。 */
    private Flux<String> generateNativeCode(AiCodeGeneratorService service, String userMessage,
                                            CodeGenTypeEnum type, AppChatMemoryId memoryId,
                                            boolean finalizeProject) {
        GenerationExecution execution = GenerationExecution.find(memoryId.getTaskId());
        var parentContext = execution == null ? io.opentelemetry.context.Context.current() : execution.traceContext();
        return nativeAttempt(service, userMessage, type, memoryId, finalizeProject, parentContext)
                .onErrorResume(IncompleteCodeException.class, error -> Flux.defer(() -> {
                    checkTask(memoryId);
                    if (memoryId.getTaskId() != null) {
                        GenerationExecution.require(memoryId.getTaskId()).progress(Map.of(
                                "id", "code_generator", "name", "代码完整性修复", "status", "running",
                                "message", "检测到代码不完整，正在重新生成完整文件"));
                    }
                    log.warn("原生代码不完整，执行一次修复：{}", error.getMessage());
                    String contract = type == CodeGenTypeEnum.HTML
                            ? "完整输出一个 html 代码块，包含所有样式、脚本和 </html> 结束标签。"
                            : "依次完整输出 index.html、style.css、script.js，分别使用 html、css、javascript 三个代码块，并关闭每个围栏。";
                    String repair = userMessage + "\n\n交付完整性检查未通过：" + error.getMessage()
                            + "\n请重新生成全部文件，不要只补片段。" + contract
                            + "保留原需求功能，精简重复结构、装饰和注释，为脚本和最后一个文件预留输出空间，不要省略代码。";
                    return nativeAttempt(service, repair, type, memoryId, finalizeProject, parentContext);
                }));
    }

    private Flux<String> nativeAttempt(AiCodeGeneratorService service, String prompt, CodeGenTypeEnum type,
                                       AppChatMemoryId memoryId, boolean finalizeProject,
                                       io.opentelemetry.context.Context parentContext) {
        return Flux.defer(() -> {
            checkTask(memoryId);
            GenerationExecution run = GenerationExecution.find(memoryId.getTaskId());
            com.xhl.aicodegenerate.observability.TraceSession.Step step;
            try (var ignored = parentContext.makeCurrent()) {
                step = run == null ? null : run.traceStep(com.xhl.aicodegenerate.observability.TraceSession.Kind.STAGE, "native_generation");
            }
            try (var attemptScope = (step == null ? parentContext : step.context()).makeCurrent()) {
                Flux<String> stream = type == CodeGenTypeEnum.HTML
                        ? service.generateHtmlCodeStream(memoryId, prompt)
                        : service.generateMultiFileCodeStream(memoryId, prompt);
                // 每次订阅和修复独立收集，不能把两轮代码拼成一个文档。
                Flux<String> result = processCodeStream(stream, type, memoryId, finalizeProject);
                if (step == null) return result;
                Flux<String> tracked = result.doOnComplete(step::succeed).doOnError(step::fail).doOnCancel(step::cancel);
                // 模型在订阅时才启动；范围必须覆盖真实 subscribe，异步回调由模型包装器继续传递。
                return new Flux<String>() {
                    @Override public void subscribe(reactor.core.CoreSubscriber<? super String> subscriber) {
                        try (var ignored = step.context().makeCurrent()) { tracked.subscribe(subscriber); }
                    }
                };
            } catch (RuntimeException error) {
                if (step != null) step.fail(error);
                throw error;
            }
        });
    }


    /**
     * 将 LangChain4j TokenStream 适配成 Reactor Flux。
     * <p>
     * TokenStream 能监听工具调用事件；这里统一封装成 JSON 字符串，
     * 下游再按生成类型选择不同流处理器。
     * </p>
     */
    private Flux<String> tokenStreamToFlux(TokenStream tokenStream, AppChatMemoryId memoryId, boolean finalizeProject) {
        Long appId = memoryId.getAppId();
        return Flux.create(sink -> {
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicReference<StreamingHandle> activeHandle = new AtomicReference<>();
            sink.onCancel(() -> {
                completed.set(true);
                StreamingHandle handle = activeHandle.get();
                if (handle != null) handle.cancel();
            });
            tokenStream
                    .onPartialResponseWithContext((partialResponse, context) -> {
                        activeHandle.set(context.streamingHandle());
                        if (sink.isCancelled()) context.streamingHandle().cancel();
                        else emit(sink, completed, CodeGenStreamMessage.aiResponse(partialResponse.text()));
                    })
                    .onPartialThinkingWithContext((partialThinking, context) -> {
                        activeHandle.set(context.streamingHandle());
                        if (sink.isCancelled()) context.streamingHandle().cancel();
                        else emit(sink, completed, CodeGenStreamMessage.thinking(partialThinking.text()));
                    })
                    .onPartialToolCallWithContext((partialToolCall, context) -> {
                        activeHandle.set(context.streamingHandle());
                        if (sink.isCancelled()) context.streamingHandle().cancel();
                        else emit(sink, completed, toToolRequestMessage(partialToolCall));
                    })
                    .beforeToolExecution(beforeToolExecution -> {
                        checkTask(memoryId);
                        if (sink.isCancelled()) throw new CancellationException("生成已中断，停止后续工具执行");
                        emit(sink, completed, toToolRequestMessage(beforeToolExecution));
                    })
                    .onToolExecuted(toolExecution -> emit(sink, completed,
                            toToolExecutedMessage(toolExecution)))
                    .onCompleteResponse(response -> {
                        if (!sink.isCancelled() && completed.compareAndSet(false, true)) {
                            try {
                                if (finalizeProject) {
                                    buildVueProject(appId);
                                    String projectPath = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, VUE_PROJECT_DIR_PREFIX + appId).toString();
                                    triggerScreenshot(appId, CodeGenTypeEnum.VUE_PROJECT, new File(projectPath));
                                }
                            } catch (Exception e) {
                                sink.error(e);
                                return;
                            }
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

    private void checkTask(AppChatMemoryId memoryId) {
        if (memoryId.getTaskId() != null) com.xhl.aicodegenerate.generation.GenerationExecution.require(memoryId.getTaskId()).checkActive();
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
     * 同步构建 Vue 项目。
     * <p>
     * 在 TokenStream 完成回调中阻塞执行 npm install + npm run build，
     * 保证 AI 回复完成时构建也已结束，前端可以立即浏览到最新页面。
     * </p>
     */
    private void buildVueProject(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        String projectPath = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, VUE_PROJECT_DIR_PREFIX + appId).toString();
        boolean buildSuccess = vueProjectBuilder.buildProject(projectPath);
        if (!buildSuccess) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Vue 项目构建失败");
        }
    }

    /**
     * 通用流式代码处理方法
     *
     * @param codeStream  代码流
     * @param codeGenType 代码生成类型
     * @return 流式响应
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, AppChatMemoryId memoryId,
                                            boolean finalizeProject) {
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
                checkTask(memoryId);
                java.nio.file.Path directory = memoryId.getTaskId() == null ? null : com.xhl.aicodegenerate.generation.GenerationExecution.require(memoryId.getTaskId()).root();
                File savedDir = directory == null
                        ? CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, memoryId.getAppId(), null)
                        : com.xhl.aicodegenerate.generation.GenerationExecution.require(memoryId.getTaskId()).files(
                                () -> CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, memoryId.getAppId(), directory));
                log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
                if (finalizeProject) {
                    triggerScreenshot(memoryId.getAppId(), codeGenType, savedDir);
                }
            } catch (IncompleteCodeException | CancellationException | GenerationExecution.BudgetExceeded e) {
                throw e;
            } catch (Exception e) {
                log.error("保存失败: {}", e.getMessage());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存生成代码失败：" + e.getMessage());
            }
        });
    }

    /**
     * 触发异步应用封面截图
     */
    private void triggerScreenshot(Long appId, CodeGenTypeEnum codeGenType, File savedDir) {
        if (appId == null || appId <= 0 || savedDir == null || !savedDir.exists()) {
            return;
        }
        try {
            appScreenshotService.captureAppCoverAsync(appId, savedDir.getAbsolutePath());
        } catch (Exception e) {
            log.warn("触发应用封面截图异常: appId={}, error={}", appId, e.getMessage());
        }
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
