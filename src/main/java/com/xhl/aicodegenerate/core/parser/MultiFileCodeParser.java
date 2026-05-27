package com.xhl.aicodegenerate.core.parser;

import com.xhl.aicodegenerate.ai.model.MultiFileCodeResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多文件代码解析器（HTML + CSS + JS）
 *
 */
public class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CODE_PATTERN = Pattern.compile("```css\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_CODE_PATTERN = Pattern.compile("```(?:js|javascript)\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_DOCUMENT_PATTERN = Pattern.compile("(<!DOCTYPE\\s+html[\\s\\S]*?</html>|<html[\\s\\S]*?</html>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_TAG_PATTERN = Pattern.compile("<style[^>]*>([\\s\\S]*?)</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_SCRIPT_TAG_PATTERN = Pattern.compile("<script(?![^>]*\\bsrc\\s*=)[^>]*>([\\s\\S]*?)</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_CSS_LINK_PATTERN = Pattern.compile("<link[^>]*href=[\"']style\\.css[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_JS_LINK_PATTERN = Pattern.compile("<script[^>]*src=[\"']script\\.js[\"'][^>]*>\\s*</script>", Pattern.CASE_INSENSITIVE);

    @Override
    public MultiFileCodeResult parseCode(String codeContent) {
        MultiFileCodeResult result = new MultiFileCodeResult();
        // 提取各类代码
        String htmlCode = extractCodeByPattern(codeContent, HTML_CODE_PATTERN);
        String cssCode = extractCodeByPattern(codeContent, CSS_CODE_PATTERN);
        String jsCode = extractCodeByPattern(codeContent, JS_CODE_PATTERN);
        if (isBlank(htmlCode)) {
            htmlCode = extractCodeByPattern(codeContent, HTML_DOCUMENT_PATTERN);
        }
        if (!isBlank(htmlCode) && (isBlank(cssCode) || isBlank(jsCode))) {
            InlineAssets inlineAssets = extractInlineAssets(htmlCode);
            if (isBlank(cssCode)) {
                cssCode = inlineAssets.cssCode();
            }
            if (isBlank(jsCode)) {
                jsCode = inlineAssets.jsCode();
            }
            if (!isBlank(inlineAssets.cssCode()) || !isBlank(inlineAssets.jsCode())) {
                htmlCode = addExternalReferences(inlineAssets.htmlCode(), !isBlank(cssCode), !isBlank(jsCode));
            }
        }
        if (!isBlank(htmlCode)) {
            htmlCode = addExternalReferences(htmlCode, !isBlank(cssCode), !isBlank(jsCode));
        }
        // 设置HTML代码
        if (!isBlank(htmlCode)) {
            result.setHtmlCode(htmlCode.trim());
        }
        // 设置CSS代码
        if (!isBlank(cssCode)) {
            result.setCssCode(cssCode.trim());
        }
        // 设置JS代码
        if (!isBlank(jsCode)) {
            result.setJsCode(jsCode.trim());
        }
        return result;
    }

    /**
     * 根据正则模式提取代码
     *
     * @param content 原始内容
     * @param pattern 正则模式
     * @return 提取的代码
     */
    private String extractCodeByPattern(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 兼容模型把 CSS / JS 内联到同一个 HTML 代码块里的情况。
     */
    private InlineAssets extractInlineAssets(String htmlCode) {
        String cssCode = extractAllByPattern(htmlCode, STYLE_TAG_PATTERN);
        String jsCode = extractAllByPattern(htmlCode, INLINE_SCRIPT_TAG_PATTERN);
        String cleanedHtml = STYLE_TAG_PATTERN.matcher(htmlCode).replaceAll("");
        cleanedHtml = INLINE_SCRIPT_TAG_PATTERN.matcher(cleanedHtml).replaceAll("");
        return new InlineAssets(cleanedHtml, cssCode, jsCode);
    }

    private String extractAllByPattern(String content, Pattern pattern) {
        StringBuilder result = new StringBuilder();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(matcher.group(1).trim());
        }
        return result.toString();
    }

    private String addExternalReferences(String htmlCode, boolean hasCssCode, boolean hasJsCode) {
        String result = htmlCode;
        if (hasCssCode && !STYLE_CSS_LINK_PATTERN.matcher(result).find()) {
            result = result.replaceFirst("(?i)</head>", "    <link rel=\"stylesheet\" href=\"style.css\">\\n</head>");
        }
        if (hasJsCode && !SCRIPT_JS_LINK_PATTERN.matcher(result).find()) {
            result = result.replaceFirst("(?i)</body>", "    <script src=\"script.js\"></script>\\n</body>");
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record InlineAssets(String htmlCode, String cssCode, String jsCode) {
    }
}
