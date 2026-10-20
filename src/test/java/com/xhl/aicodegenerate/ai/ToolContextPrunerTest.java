package com.xhl.aicodegenerate.ai;

import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.ai.tools.FileWriteTool;
import com.xhl.aicodegenerate.model.dto.ai.CodeGenStreamMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** 用手工消息夹具覆盖裁剪策略及协议保留边界，不访问模型或存储。 */
class ToolContextPrunerTest {
    private final ToolContextPruner pruner = new ToolContextPruner();
    private static final String LONG_CODE = "源代码不可重复携带\n".repeat(200);

    @Test
    void successfulWriteBecomesReceiptButKeepsProtocolMetadataAndOriginalArguments() {
        var call = call("w", "writeFile", "src/App.vue", Map.of("content", LONG_CODE));
        var ai = AiMessage.builder().text("开始写入").thinking("必须保留的推理")
                .toolExecutionRequests(List.of(call)).attributes(Map.of("trace", "ai-trace")).build();
        var result = ToolExecutionResultMessage.builder().id("w").toolName("writeFile")
                .text("文件写入成功: src/App.vue。若所有必要文件已经写入完成，请停止调用工具并输出生成完毕提示。")
                .isError(false).attributes(Map.of("trace", "result-trace")).build();
        var request = ChatRequest.builder().messages(SystemMessage.from("系统约束"), UserMessage.from("用户需求"), ai, result)
                .modelName("测试模型").maxOutputTokens(8192).build();

        var projected = pruner.apply(request);
        var compactAi = (AiMessage) projected.messages().get(2);
        var compactResult = (ToolExecutionResultMessage) projected.messages().get(3);
        assertFalse(compactAi.toolExecutionRequests().getFirst().arguments().contains("源代码不可重复携带"));
        assertEquals("w", compactAi.toolExecutionRequests().getFirst().id());
        assertEquals("writeFile", compactAi.toolExecutionRequests().getFirst().name());
        assertEquals("src/App.vue", JSONUtil.parseObj(compactAi.toolExecutionRequests().getFirst().arguments()).getStr("relativeFilePath"));
        assertTrue(JSONUtil.parseObj(compactAi.toolExecutionRequests().getFirst().arguments()).getStr("content").contains("省略"));
        assertEquals(ai.thinking(), compactAi.thinking());
        assertEquals(ai.text(), compactAi.text());
        assertEquals(ai.attributes(), compactAi.attributes());
        assertEquals(result.id(), compactResult.id());
        assertEquals(result.toolName(), compactResult.toolName());
        assertEquals(result.isError(), compactResult.isError());
        assertEquals(result.attributes(), compactResult.attributes());
        var receipt = JSONUtil.parseObj(compactResult.text());
        assertEquals("success", receipt.getStr("status"));
        assertEquals("src/App.vue", receipt.getStr("file"));
        assertEquals(200, receipt.getInt("lines"));
        assertEquals(LONG_CODE.length(), receipt.getInt("chars"));
        assertTrue(receipt.getStr("sha256").matches("[0-9a-f]{64}"));
        assertTrue(receipt.getBool("contentOmitted"));
        assertEquals(LONG_CODE, JSONUtil.parseObj(call.arguments()).getStr("content"));
        assertEquals("文件写入成功: src/App.vue。若所有必要文件已经写入完成，请停止调用工具并输出生成完毕提示。", result.text());
        assertEquals(request.parameters(), projected.parameters());
        assertSame(request.messages().getFirst(), projected.messages().getFirst());
        assertSame(request.messages().get(1), projected.messages().get(1));
    }

    @Test
    void modifyPrunesOnlyLargePatchFieldsAndDoesNotClaimPatchHashIsFileHash() {
        var call = call("m", "modifyFile", "src/App.vue", Map.of("oldContent", "旧片段", "newContent", LONG_CODE));
        var request = request(AiMessage.from(call), result(call, "文件修改成功: src/App.vue"));
        var projected = pruner.apply(request);
        var arguments = JSONUtil.parseObj(((AiMessage) projected.messages().getFirst()).toolExecutionRequests().getFirst().arguments());
        assertEquals("旧片段", arguments.getStr("oldContent"));
        assertFalse(arguments.getStr("newContent").contains("源代码不可重复携带"));
        var receipt = JSONUtil.parseObj(((ToolExecutionResultMessage) projected.messages().get(1)).text());
        assertEquals("modifyFile", receipt.getStr("tool"));
        assertEquals("success", receipt.getStr("status"));
        assertFalse(receipt.containsKey("sha256"));
        assertFalse(receipt.containsKey("lines"));
        assertEquals(LONG_CODE.length(), receipt.getJSONObject("omittedArguments").getJSONObject("newContent").getInt("chars"));
        assertFalse(receipt.getJSONObject("omittedArguments").containsKey("oldContent"));
    }

    @Test
    void smallBodiesAndUnknownToolRemainUntouched() {
        var small = call("small", "writeFile", "small.vue", Map.of("content", "<p>短代码</p>"));
        var unknown = call("unknown", "externalTool", "other.vue", Map.of("content", LONG_CODE));
        var request = request(AiMessage.from(small), result(small, "文件写入成功: small.vue。完成"),
                AiMessage.from(unknown), result(unknown, "文件写入成功: other.vue。完成"));
        assertSame(request, pruner.apply(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"文件操作失败：磁盘已满", "错误：旧内容必须唯一匹配，文件未修改", "警告：文件中未找到要替换的内容，文件未修改", "信息：替换后文件内容未发生变化", "success", "文件修改成功: other.vue"})
    void ambiguousOrFailedOperationKeepsArgumentsAndDiagnostic(String diagnostic) {
        var call = call("failed", "modifyFile", "src/App.vue", Map.of("oldContent", LONG_CODE, "newContent", LONG_CODE));
        var request = request(AiMessage.from(call), result(call, diagnostic));
        assertSame(request, pruner.apply(request));
    }

    @Test
    void explicitFrameworkErrorOverridesSuccessLookingText() {
        var call = call("failed", "writeFile", "src/App.vue", Map.of("content", LONG_CODE));
        var failure = ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name())
                .text("文件写入成功: src/App.vue。完成").isError(true).build();
        var request = request(AiMessage.from(call), failure);
        assertSame(request, pruner.apply(request));
    }

    @Test
    void incompleteAndMismatchedGroupsAreNeverPartiallyPruned() {
        var first = call("first", "writeFile", "a.vue", Map.of("content", LONG_CODE));
        var second = call("second", "writeFile", "b.vue", Map.of("content", LONG_CODE));
        var duplicate = first.toBuilder().name("readFile").build();
        List<ChatRequest> invalid = List.of(
                request(AiMessage.from(first)),
                request(AiMessage.from(first, second), result(first, "文件写入成功: a.vue。完成")),
                request(AiMessage.from(first), result(second, "文件写入成功: b.vue。完成")),
                request(AiMessage.from(first), ToolExecutionResultMessage.from("first", "readFile", LONG_CODE)),
                request(AiMessage.from(first, duplicate), result(first, "文件写入成功: a.vue。完成"), result(duplicate, LONG_CODE)),
                request(AiMessage.from(first), result(first, "文件写入成功: a.vue。完成"), result(first, "重复结果")),
                request(AiMessage.from(first), UserMessage.from("插入的用户消息"), result(first, "文件写入成功: a.vue。完成")));
        for (var request : invalid) assertSame(request, pruner.apply(request));
    }

    @Test
    void completedBatchKeepsOrderAndPreservesFailedSibling() {
        var write = call("write", "writeFile", "a.vue", Map.of("content", LONG_CODE));
        var failed = call("failed", "modifyFile", "b.vue", Map.of("oldContent", LONG_CODE, "newContent", LONG_CODE));
        var failure = result(failed, "错误：旧内容必须唯一匹配，文件未修改");
        var projected = pruner.apply(request(AiMessage.from(write, failed), failure, result(write, "文件写入成功: a.vue。完成")));
        var calls = ((AiMessage) projected.messages().getFirst()).toolExecutionRequests();
        assertEquals(List.of("write", "failed"), calls.stream().map(ToolExecutionRequest::id).toList());
        assertFalse(calls.getFirst().arguments().contains("源代码不可重复携带"));
        assertSame(failed, calls.get(1));
        assertSame(failure, projected.messages().get(1));
        assertEquals("write", ((ToolExecutionResultMessage) projected.messages().get(2)).id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "{\"relativeFilePath\":\"a.vue\",\"content\":123}", "{\"content\":\"缺少路径\"}"})
    void malformedArgumentsRemainUntouched(String arguments) {
        var call = ToolExecutionRequest.builder().id("bad").name("writeFile").arguments(arguments).build();
        var request = request(AiMessage.from(call), result(call, "文件写入成功: a.vue。完成"));
        assertSame(request, pruner.apply(request));
    }

    @Test
    void onlyOlderSuccessfulReadsOfSameFileAreSummarized() {
        var old = call("read-old", "readFile", "a.vue", Map.of());
        var other = call("read-other", "readFile", "b.vue", Map.of());
        var latest = call("read-latest", "readFile", "a.vue", Map.of());
        var failed = call("read-failed", "readFile", "a.vue", Map.of());
        String current = LONG_CODE + "最新版本";
        var request = request(AiMessage.from(old), result(old, LONG_CODE), AiMessage.from(other), result(other, LONG_CODE),
                AiMessage.from(latest), result(latest, current), AiMessage.from(failed), result(failed, "文件操作失败：读取失败"));
        var projected = pruner.apply(request);
        assertTrue(((ToolExecutionResultMessage) projected.messages().get(1)).text().startsWith("{"), "旧读取应转换为 JSON 摘要");
        var receipt = JSONUtil.parseObj(((ToolExecutionResultMessage) projected.messages().get(1)).text());
        assertTrue(receipt.getBool("contentOmitted"));
        assertEquals("a.vue", receipt.getStr("file"));
        assertSame(request.messages().get(3), projected.messages().get(3));
        assertSame(request.messages().get(5), projected.messages().get(5));
        assertSame(request.messages().get(7), projected.messages().get(7));
    }

    @Test
    void failedOrIncompleteNewReadMustNotEvictLastUsefulFileContent() {
        var old = call("old", "readFile", "a.vue", Map.of());
        var failure = call("failure", "readFile", "a.vue", Map.of());
        var pending = call("pending", "readFile", "a.vue", Map.of());
        var request = request(AiMessage.from(old), result(old, LONG_CODE), AiMessage.from(failure),
                result(failure, "错误：文件不存在或不是文件 - a.vue"), AiMessage.from(pending));
        assertSame(request, pruner.apply(request));
    }

    @Test
    void legacyProjectionDoesNotClaimSuccessOrRemoveUserAndOrdinaryCode() {
        String display = "说明\n\n[工具调用] 写入文件 src/App.vue\n```vue\n" + LONG_CODE + "\n```\n\n结尾";
        var ai = AiMessage.builder().text(display).thinking("保留推理").attributes(Map.of("source", "history")).build();
        var user = UserMessage.from(display);
        var ordinary = AiMessage.from("代码示例：\n```vue\n" + LONG_CODE + "\n```\n");
        var incomplete = AiMessage.from("[工具调用] 写入文件 src/B.vue\n```vue\n" + LONG_CODE);
        var request = request(user, ai, ordinary, incomplete);
        var projected = pruner.apply(request);
        var changed = (AiMessage) projected.messages().get(1);
        assertFalse(changed.text().contains("源代码不可重复携带"));
        assertTrue(changed.text().contains("\"status\":\"recorded\""), "展示记录本身不能证明实际写入成功");
        assertTrue(changed.text().startsWith("说明"));
        assertTrue(changed.text().endsWith("结尾"));
        assertEquals(ai.thinking(), changed.thinking());
        assertEquals(ai.attributes(), changed.attributes());
        assertEquals(display, ai.text());
        assertSame(user, projected.messages().getFirst());
        assertSame(ordinary, projected.messages().get(2));
        assertSame(incomplete, projected.messages().get(3));
    }

    @Test
    void applyingProjectionTwiceDoesNotGrowOrChangeReceipts() {
        var write = call("write", "writeFile", "src/App.vue", Map.of("content", LONG_CODE));
        var old = call("old", "readFile", "src/App.vue", Map.of());
        var latest = call("latest", "readFile", "src/App.vue", Map.of());
        var request = request(AiMessage.from(write), result(write, "文件写入成功: src/App.vue。完成"),
                AiMessage.from(old), result(old, LONG_CODE), AiMessage.from(latest), result(latest, LONG_CODE));
        var once = pruner.apply(request);
        assertNotEquals(request, once);
        assertEquals(once, pruner.apply(once));
    }

    @Test
    void sharedPrunerNeverLeaksStateBetweenConcurrentRequests() {
        var jobs = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 30; i++) {
            String path = "src/Page" + i + ".vue";
            jobs.add(CompletableFuture.runAsync(() -> {
                var read = call("read", "readFile", path, Map.of());
                var request = request(AiMessage.from(read), result(read, LONG_CODE));
                assertSame(request, pruner.apply(request), "独立请求的唯一读取不能被其他请求的同 ID 消息裁剪");
            }));
        }
        CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).join();
    }

    @Test
    void legacyReceiptDescribesOriginalContentRatherThanMarkdownFramingNewline() {
        String code = "a".repeat(2048);
        var request = request(AiMessage.from("[工具调用] 写入文件 src/App.vue\n```vue\n" + code + "\n```\n"));
        String text = ((AiMessage) pruner.apply(request).messages().getFirst()).text();
        var receipt = JSONUtil.parseObj(text.substring(text.indexOf('{')).strip());
        assertEquals(2048, receipt.getInt("chars"), "展示围栏分隔换行不属于原始文件");
        assertEquals(1, receipt.getInt("lines"));
        assertEquals("b2a3a502fdfc34f4e3edfa94b7f3109cd972d87a4fec63ab21a6673379ccf7ad", receipt.getStr("sha256"));
    }

    @Test
    void ambiguousNestedFencesInDisplayAreConservativelyPreserved() {
        String code = LONG_CODE + "```\n这仍然属于文件内容\n" + LONG_CODE;
        var request = request(AiMessage.from("[工具调用] 写入文件 README.md\n```markdown\n" + code + "\n```\n"));
        assertSame(request, pruner.apply(request), "无法确认围栏边界时不能制造错误文件摘要");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[工具调用] 示例", "[选择工具] 示例", "[工具调用] 写入文件 nested.vue\n```vue\n示例\n```"})
    void toolMarkerInsideSourceCannotHideAmbiguousOuterFence(String marker) {
        String code = LONG_CODE + "```\n" + marker + "\n后半段源码\n";
        var request = request(AiMessage.from(display("README.md", code)));
        assertSame(request, pruner.apply(request), "正文工具标记不是可信的展示边界");
    }

    @Test
    void multipleUnambiguousWriteDisplaysStillBecomeReceipts() {
        var request = request(AiMessage.from("开始\n" + display("a.vue", LONG_CODE)
                + "中间说明\n" + display("b.vue", LONG_CODE) + "结束"));
        String text = ((AiMessage) pruner.apply(request).messages().getFirst()).text();
        assertFalse(text.contains("源代码不可重复携带"));
        assertEquals(2, text.split("\"status\":\"recorded\"", -1).length - 1);
        assertTrue(text.contains("中间说明"));
        assertTrue(text.endsWith("结束"));
    }

    @Test
    void ordinaryFenceBesideWriteDisplayMakesWholeTextConservativelyUnchanged() {
        var request = request(AiMessage.from(display("a.vue", LONG_CODE) + "说明\n```vue\n示例\n```\n"));
        assertSame(request, pruner.apply(request), "混合普通围栏时保守保留，避免误认文件边界");
    }

    @Test
    void longPathReadReceiptIsNeverSummarizedAgain() {
        String path = "d/".repeat(450) + "a.vue";
        var old = call("old", "readFile", path, Map.of());
        var latest = call("latest", "readFile", path, Map.of());
        var request = request(AiMessage.from(old), result(old, "x\n".repeat(10000)),
                AiMessage.from(latest), result(latest, LONG_CODE));
        var once = pruner.apply(request);
        assertNotEquals(request, once);
        assertEquals(once, pruner.apply(once), "长路径回执仍超过阈值时也不能将摘要误认为源码");
    }

    @Test
    void multimodalToolResultRemainsUntouched() {
        var call = call("image", "readFile", "image.png", Map.of());
        var image = ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name())
                .contents(ImageContent.from("data:image/png;base64,aGVsbG8=")).build();
        var request = request(AiMessage.from(call), image);
        assertSame(request, pruner.apply(request));
    }

    @Test
    void missingToolIdDoesNotTriggerPruning() {
        var call = ToolExecutionRequest.builder().name("writeFile")
                .arguments(JSONUtil.toJsonStr(Map.of("relativeFilePath", "a.vue", "content", LONG_CODE))).build();
        var request = request(AiMessage.from(call), result(call, "文件写入成功: a.vue。完成"));
        assertSame(request, pruner.apply(request));
    }

    @Test
    void veryLongPathMustNotMakeReceiptLargerThanOriginalMessages() {
        String path = "长路径".repeat(1000) + "/App.vue";
        var call = call("long-path", "writeFile", path, Map.of("content", "a".repeat(1025)));
        var request = request(AiMessage.from(call), result(call, "文件写入成功: " + path + "。完成"));
        var projected = pruner.apply(request);
        assertTrue(projected.toString().length() <= request.toString().length());
    }

    @Test
    void emptyLegacyFenceIsPreservedWithoutAbortingRequest() {
        var request = request(AiMessage.from("[工具调用] 写入文件 empty.vue\n```vue\n```\n"));
        assertSame(request, pruner.apply(request));
    }

    private static ToolExecutionRequest call(String id, String tool, String path, Map<String, String> bodies) {
        var arguments = JSONUtil.createObj().set("relativeFilePath", path);
        bodies.forEach(arguments::set);
        return ToolExecutionRequest.builder().id(id).name(tool).arguments(arguments.toString()).build();
    }

    private static String display(String path, String content) {
        var call = call("display", "writeFile", path, Map.of("content", content));
        return new FileWriteTool().formatToolExecutedMessage(
                CodeGenStreamMessage.toolExecuted(call.id(), call.name(), call.arguments(), "文件写入成功: " + path));
    }

    private static ToolExecutionResultMessage result(ToolExecutionRequest call, String text) {
        return ToolExecutionResultMessage.from(call, text);
    }

    private static ChatRequest request(ChatMessage... messages) {
        return ChatRequest.builder().messages(messages).build();
    }
}
