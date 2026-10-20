package com.xhl.aicodegenerate.ai;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只投影发给模型的历史上下文，原始工具参数、记忆和文件保持不变。 */
@Slf4j
public final class ToolContextPruner implements UnaryOperator<ChatRequest> {
    private static final int MIN_BODY_CHARS = 1024;
    private static final String OMITTED = "[历史正文已省略；该调用已经执行，请勿将占位符写入文件；需要精确源码时调用 readFile]";
    private static final Pattern WRITE_DISPLAY = Pattern.compile(
            "(?m)^\\[工具调用] 写入文件 ([^\\r\\n]+)\\r?\\n(```(?:vue|javascript|typescript|css|html|json|markdown)?\\r?\\n)([\\s\\S]*?)(^```[\\t ]*(?:\\r?\\n|$))");
    private static final Pattern FENCE_LINE = Pattern.compile("(?m)^`{3,}[^\\r\\n]*");

    @Override
    public ChatRequest apply(ChatRequest request) {
        List<ChatMessage> original = request.messages();
        List<ChatMessage> projected = new ArrayList<>(original);
        List<CompletedGroup> groups = completedGroups(original);
        Map<String, Integer> latestReads = latestSuccessfulReads(groups);
        for (CompletedGroup group : groups) {
            List<ToolExecutionRequest> calls = new ArrayList<>(group.ai().toolExecutionRequests());
            for (int i = 0; i < calls.size(); i++) {
                ToolExecutionRequest call = calls.get(i);
                IndexedResult indexed = group.results().get(call.id());
                ToolExecutionResultMessage result = indexed.result();
                JSONObject arguments = arguments(call);
                if (arguments == null || !result.hasSingleText() || Boolean.TRUE.equals(result.isError())) continue;
                String path = string(arguments, "relativeFilePath");
                if (path == null || path.isBlank()) continue;
                Compacted compacted = compactWriteOrModify(call, result, arguments, path);
                if (compacted != null) {
                    calls.set(i, compacted.call());
                    projected.set(indexed.index(), copyResult(result, compacted.result()));
                } else if ("readFile".equals(call.name()) && successfulRead(result)
                        && !isReadReceipt(result, path)
                        && result.text().length() > MIN_BODY_CHARS
                        && latestReads.getOrDefault(path, indexed.index()) > indexed.index()) {
                    String summary = receipt("readFile", path, "success", result.text())
                            .set("contextPruned", true).set("reason", "同文件较新的成功读取正文已保留").toString();
                    if (summary.length() < result.text().length()) projected.set(indexed.index(), copyResult(result, summary));
                }
            }
            if (!calls.equals(group.ai().toolExecutionRequests())) {
                projected.set(group.aiIndex(), group.ai().toBuilder().toolExecutionRequests(calls).build());
            }
        }
        for (int i = 0; i < original.size(); i++) {
            if (original.get(i) instanceof AiMessage ai && !ai.hasToolExecutionRequests() && ai.text() != null) {
                String text = compactDisplay(ai.text());
                if (!text.equals(ai.text())) projected.set(i, ai.withText(text));
            }
        }
        if (projected.equals(original)) return request;
        if (log.isDebugEnabled()) {
            log.debug("工具上下文裁剪：消息数={}，AI／工具正文字符数={} -> {}",
                    original.size(), bodyChars(original), bodyChars(projected));
        }
        return request.toBuilder().messages(projected).build();
    }

    /** 不完整或不匹配的工具组交给原链路处理，裁剪器不擅自修复协议。 */
    private static List<CompletedGroup> completedGroups(List<ChatMessage> messages) {
        List<CompletedGroup> groups = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) continue;
            Map<String, ToolExecutionRequest> calls = new HashMap<>();
            boolean valid = true;
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                if (call.id() == null || call.id().isBlank() || call.name() == null
                        || calls.putIfAbsent(call.id(), call) != null) valid = false;
            }
            Map<String, IndexedResult> results = new HashMap<>();
            int end = i + 1;
            while (end < messages.size() && messages.get(end) instanceof ToolExecutionResultMessage result) {
                ToolExecutionRequest call = calls.get(result.id());
                if (call == null || !call.name().equals(result.toolName())
                        || results.putIfAbsent(result.id(), new IndexedResult(end, result)) != null) valid = false;
                end++;
            }
            if (valid && results.size() == calls.size()) groups.add(new CompletedGroup(i, ai, results));
            i = end - 1;
        }
        return groups;
    }

    private static Map<String, Integer> latestSuccessfulReads(List<CompletedGroup> groups) {
        Map<String, Integer> latest = new HashMap<>();
        for (CompletedGroup group : groups) {
            for (ToolExecutionRequest call : group.ai().toolExecutionRequests()) {
                if (!"readFile".equals(call.name())) continue;
                IndexedResult indexed = group.results().get(call.id());
                JSONObject arguments = arguments(call);
                String path = arguments == null ? null : string(arguments, "relativeFilePath");
                if (path != null && !path.isBlank() && successfulRead(indexed.result())
                        && !isReadReceipt(indexed.result(), path)) latest.put(path, indexed.index());
            }
        }
        return latest;
    }

    private static Compacted compactWriteOrModify(ToolExecutionRequest call, ToolExecutionResultMessage result,
                                                  JSONObject arguments, String path) {
        JSONObject summary;
        if ("writeFile".equals(call.name()) && (result.text().equals("文件写入成功: " + path)
                || result.text().startsWith("文件写入成功: " + path + "。"))) {
            String content = string(arguments, "content");
            if (content == null || content.length() <= MIN_BODY_CHARS) return null;
            summary = receipt("writeFile", path, "success", content);
            arguments.set("content", OMITTED);
        } else if ("modifyFile".equals(call.name()) && result.text().equals("文件修改成功: " + path)) {
            if (string(arguments, "oldContent") == null || string(arguments, "newContent") == null) return null;
            JSONObject omitted = JSONUtil.createObj();
            for (String field : List.of("oldContent", "newContent")) {
                String content = string(arguments, field);
                if (content.length() > MIN_BODY_CHARS) {
                    omitted.set(field, contentMetadata(content));
                    arguments.set(field, OMITTED);
                }
            }
            if (omitted.isEmpty()) return null;
            summary = baseReceipt("modifyFile", path, "success").set("omittedArguments", omitted);
        } else {
            return null;
        }
        String compactArguments = arguments.toString();
        String compactResult = summary.toString();
        if ((long) compactArguments.length() + compactResult.length() >= (long) call.arguments().length() + result.text().length()) return null;
        return new Compacted(call.toBuilder().arguments(compactArguments).build(), compactResult);
    }

    private static JSONObject baseReceipt(String tool, String path, String status) {
        return JSONUtil.createObj().set("tool", tool).set("file", path).set("status", status).set("contentOmitted", true);
    }

    private static JSONObject receipt(String tool, String path, String status, String content) {
        JSONObject receipt = baseReceipt(tool, path, status);
        contentMetadata(content).forEach(receipt::set);
        return receipt;
    }

    private static JSONObject contentMetadata(String content) {
        return JSONUtil.createObj().set("lines", content.lines().count()).set("chars", content.length())
                .set("sha256", DigestUtil.sha256Hex(content));
    }

    private static ToolExecutionResultMessage copyResult(ToolExecutionResultMessage result, String text) {
        return ToolExecutionResultMessage.builder().id(result.id()).toolName(result.toolName()).text(text)
                .isError(result.isError()).attributes(result.attributes()).build();
    }

    private static boolean successfulRead(ToolExecutionResultMessage result) {
        if (!result.hasSingleText() || Boolean.TRUE.equals(result.isError())) return false;
        String text = result.text();
        return !text.startsWith("错误：") && !text.startsWith("文件操作失败：");
    }

    /** 回执不是源码快照；保留相同保留标记的 JSON，避免长路径下反复摘要。 */
    private static boolean isReadReceipt(ToolExecutionResultMessage result, String path) {
        if (!result.text().stripLeading().startsWith("{")) return false;
        try {
            JSONObject json = JSONUtil.parseObj(result.text());
            return "readFile".equals(string(json, "tool")) && path.equals(string(json, "file"))
                    && "success".equals(string(json, "status"))
                    && Boolean.TRUE.equals(json.get("contextPruned")) && Boolean.TRUE.equals(json.get("contentOmitted"));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static JSONObject arguments(ToolExecutionRequest call) {
        if (call.arguments() == null || !call.arguments().stripLeading().startsWith("{")) return null;
        try {
            return JSONUtil.parseObj(call.arguments());
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String string(JSONObject object, String field) {
        return object.get(field) instanceof String value ? value : null;
    }

    /** 只识别项目写文件展示格式；不能用一般代码围栏规则删除需求或普通示例。 */
    private static String compactDisplay(String text) {
        Matcher matcher = WRITE_DISPLAY.matcher(text);
        Set<Integer> frameStarts = new HashSet<>();
        while (matcher.find()) {
            frameStarts.add(matcher.start(2));
            frameStarts.add(matcher.start(4));
        }
        // 正文可包含伪造的工具标记，不能以标记为可信边界；整条消息的围栏必须完全配对。
        Matcher fences = FENCE_LINE.matcher(text);
        while (fences.find()) {
            if (!frameStarts.contains(fences.start())) return text;
        }
        matcher.reset();
        StringBuilder compact = new StringBuilder();
        while (matcher.find()) {
            String framedContent = matcher.group(3);
            // 展示器在原始正文后固定添加一个换行，该分隔符不属于文件快照。
            String content = framedContent.endsWith("\n") ? framedContent.substring(0, framedContent.length() - 1) : framedContent;
            String replacement = matcher.group();
            if (content.length() > MIN_BODY_CHARS) {
                String candidate = "[工具调用] 写入文件 " + matcher.group(1) + "\n"
                        + receipt("writeFile", matcher.group(1), "recorded", content) + "\n";
                if (candidate.length() < replacement.length()) replacement = candidate;
            }
            matcher.appendReplacement(compact, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(compact).toString();
    }

    private static long bodyChars(List<ChatMessage> messages) {
        long chars = 0;
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage ai) {
                chars += ai.text() == null ? 0 : ai.text().length();
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) chars += call.arguments() == null ? 0 : call.arguments().length();
            } else if (message instanceof ToolExecutionResultMessage result && result.hasSingleText()) chars += result.text().length();
        }
        return chars;
    }

    private record IndexedResult(int index, ToolExecutionResultMessage result) { }
    private record CompletedGroup(int aiIndex, AiMessage ai, Map<String, IndexedResult> results) { }
    private record Compacted(ToolExecutionRequest call, String result) { }
}
