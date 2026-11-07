package com.xhl.aicodegenerate.knowledge;

import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 首期只解析 UTF-8 文本；分块保留原文位置，不把超长文本静默截断。 */
public final class KnowledgeText {
    private KnowledgeText() { }

    public record Chunk(int start, int end, String text) { }

    public static String decode(String name, byte[] bytes, long maxBytes) {
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("文件名称无效");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".txt")) {
            throw invalid("首期仅支持 Markdown（.md）和 TXT 文件");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > maxBytes) {
            throw invalid("文件为空或超过单文件容量限制");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\ufeff")) text = text.substring(1);
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            if (text.isBlank() || text.indexOf('\0') >= 0) throw invalid("文件没有可用文本，请上传 UTF-8 编码的文本文件");
            return text;
        } catch (CharacterCodingException error) {
            throw invalid("文件编码无法识别，请转换为 UTF-8 后上传");
        }
    }

    public static List<Chunk> chunks(String text, int size, int overlap) {
        if (size < 4 || overlap < 0 || overlap >= size / 2) throw new IllegalArgumentException("分块大小和重叠参数无效");
        List<Chunk> result = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int boundary = text.lastIndexOf('\n', end - 1);
                if (boundary >= start + size / 2) end = boundary + 1;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            result.add(new Chunk(start, end, text.substring(start, end)));
            if (end == text.length()) break;
            start = end - overlap;
            if (Character.isLowSurrogate(text.charAt(start))) start--;
        }
        return List.copyOf(result);
    }

    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.PARAMS_ERROR, message); }
}
