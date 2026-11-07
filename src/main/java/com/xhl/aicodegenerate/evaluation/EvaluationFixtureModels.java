package com.xhl.aicodegenerate.evaluation;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 模拟供应商响应协议，不直接写产物或任务状态；文件、重试、构建与取消由真实业务链路完成。 */
public final class EvaluationFixtureModels {
    private final EvaluationDataset.Case sample;
    private final Map<String, String> files;
    private final AtomicInteger generationBatches = new AtomicInteger();
    private final AtomicBoolean waiting = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public EvaluationFixtureModels(EvaluationDataset.Case sample, Path directory) {
        this.sample = sample;
        this.files = readFiles(directory, sample.fixtureDigest());
    }

    public EvaluationModelSet models(EvaluationBudget budget) {
        ChatModel routing = new ChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() { return parameters("fixture-routing-v1"); }
            @Override public ChatResponse doChat(ChatRequest request) {
                return text(CodeGenTypeEnum.getEnumByValue(sample.codeGenType()).name(), "fixture-routing-v1", FinishReason.STOP);
            }
        };
        StreamingChatModel streaming = new StreamingChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() { return parameters("fixture-generation-v1"); }
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { respond(request, handler); }
        };
        return EvaluationModelSet.budgeted(routing, routing, streaming, streaming, budget);
    }

    private void respond(ChatRequest request, StreamingChatResponseHandler handler) {
        String system = request.messages().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).map(SystemMessage::text).findFirst().orElse("");
        if (system.contains("图片素材规划助手")) {
            emit(handler, "{\"contentImageTasks\":[],\"logoTasks\":[],\"diagramTasks\":[]}", FinishReason.STOP);
            return;
        }
        if (system.contains("代码质量检查专家")) {
            emit(handler, "{\"isValid\":true,\"errors\":[],\"suggestions\":[]}", FinishReason.STOP);
            return;
        }
        if ("MODEL_FAILURE".equals(sample.fault())) {
            handler.onError(new IllegalStateException("评测注入：模型供应商不可用"));
            return;
        }
        if ("CANCEL_STREAM".equals(sample.fault())) {
            waiting.set(true);
            handler.onPartialResponse(new PartialResponse("正在生成，等待停止"), new PartialResponseContext(new StreamingHandle() {
                @Override public void cancel() { cancelled.set(true); }
                @Override public boolean isCancelled() { return cancelled.get(); }
            }));
            return;
        }
        if ("vue_project".equals(sample.codeGenType())) {
            vue(request, handler);
            return;
        }
        if (generationBatches.getAndIncrement() == 0 && "NATIVE_REGEN".equals(sample.fault())) {
            emit(handler, "```html\n<!doctype html><html><body><h1>故意截断的页面", FinishReason.LENGTH);
            return;
        }
        String output = "```html\n" + files.get("index.html") + "\n```";
        if ("multi_file".equals(sample.codeGenType())) {
            output += "\n```css\n" + files.get("style.css") + "\n```\n```javascript\n" + files.get("script.js") + "\n```";
        }
        emit(handler, output, FinishReason.STOP);
    }

    private void vue(ChatRequest request, StreamingChatResponseHandler handler) {
        if (request.messages().getLast() instanceof ToolExecutionResultMessage) {
            emit(handler, "本轮文件操作已完成，请执行真实构建和预览检查。", FinishReason.STOP);
            return;
        }
        int batch = generationBatches.getAndIncrement();
        var outgoing = new TreeMap<>(files);
        if (batch == 0 && "BUILD_REPAIR".equals(sample.fault())) {
            outgoing.put("src/App.vue", "<template><h1>故意的编译错误</h2></template>");
        }
        if ("BUILD_TIMEOUT".equals(sample.fault())) {
            // 无依赖的真实 Node 构建进程等待任务超时，由现有进程树清理逻辑终止。
            outgoing.put("package.json", "{\"name\":\"evaluation-timeout\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"node -e \\\"setInterval(()=>{},1000)\\\"\"}}");
            outgoing.remove("package-lock.json");
        }
        var tools = new ArrayList<ToolExecutionRequest>();
        for (var entry : outgoing.entrySet()) {
            var call = ToolExecutionRequest.builder().id("fixture-" + batch + "-" + tools.size()).name("writeFile")
                    .arguments(JSONUtil.toJsonStr(Map.of("relativeFilePath", entry.getKey(), "content", entry.getValue()))).build();
            handler.onCompleteToolCall(new CompleteToolCall(tools.size(), call));
            tools.add(call);
        }
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(tools)).modelName("fixture-generation-v1")
                .tokenUsage(new TokenUsage(11, 7)).finishReason(FinishReason.TOOL_EXECUTION).build());
    }

    private static void emit(StreamingChatResponseHandler handler, String value, FinishReason reason) {
        handler.onPartialResponse(value);
        handler.onCompleteResponse(text(value, "fixture-generation-v1", reason));
    }
    private static ChatResponse text(String value, String model, FinishReason reason) {
        // 此处用量为受控协议夹具，报告必须与真实供应商用量分组，不用于推断真实成本。
        return ChatResponse.builder().aiMessage(AiMessage.from(value)).modelName(model)
                .tokenUsage(new TokenUsage(11, 7)).finishReason(reason).build();
    }
    private static ChatRequestParameters parameters(String model) {
        return ChatRequestParameters.builder().modelName(model).temperature(0.0).build();
    }

    private static Map<String, String> readFiles(Path directory, String expectedDigest) {
        try (var walk = Files.walk(directory)) {
            var paths = walk.sorted().limit(1001).toList();
            if (paths.size() > 1000) throw new EvaluationConfigurationException("受控产物文件数超限");
            var files = new TreeMap<String, String>();
            var manifest = new StringBuilder();
            int total = 0;
            for (Path path : paths) {
                if (Files.isSymbolicLink(path)) throw new EvaluationConfigurationException("受控产物不能包含符号链接");
                if (Files.isDirectory(path)) continue;
                if (!Files.isRegularFile(path)) throw new EvaluationConfigurationException("受控产物必须为普通文件");
                byte[] bytes;
                try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(2 * 1024 * 1024 + 1); }
                total += bytes.length;
                if (bytes.length > 2 * 1024 * 1024 || total > 16 * 1024 * 1024) throw new EvaluationConfigurationException("受控产物大小超限");
                String relative = directory.relativize(path).toString().replace('\\', '/');
                files.put(relative, new String(bytes, StandardCharsets.UTF_8));
                manifest.append(relative).append(':').append(DigestUtil.sha256Hex(bytes)).append('\n');
            }
            if (!expectedDigest.equals(DigestUtil.sha256Hex(manifest.toString()))) {
                throw new EvaluationConfigurationException("受控产物在加载后发生变化，不能继续运行");
            }
            return Map.copyOf(files);
        } catch (IOException error) {
            throw new EvaluationConfigurationException("受控模型无法读取固定产物");
        }
    }

    public boolean waitingForCancellation() { return waiting.get(); }
    public boolean streamCancelled() { return cancelled.get(); }
}
