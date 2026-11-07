package com.xhl.aicodegenerate.core.parser;

import com.xhl.aicodegenerate.ai.model.HtmlCodeResult;
import com.xhl.aicodegenerate.exception.IncompleteCodeException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 单文件代码解析器
 *
 */
public class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final Pattern HTML_START = Pattern.compile("\\A\\s*(?:<!doctype\\s+html\\b|<html\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_FENCE = Pattern.compile(
            "^[ \\t]*```(?:html|htm|index\\.html)(?:[ \\t]+[^\\r\\n]*)?[ \\t]*\\r?\\n",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    // 属性、注释和原始文本元素中的 </html> 不能当作文档结束位置。
    private static final Pattern HTML_TOKEN = Pattern.compile(
            "<!--[\\s\\S]*?(?:-->|$)|<!doctype\\s+html\\b[^>]*>|<(/?)([a-z][a-z0-9:-]*)\\b(?:\"[^\"]*\"|'[^']*'|[^'\">])*>",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> RAW_TEXT_ELEMENTS = Set.of("script", "style", "textarea", "title");

    @Override
    public HtmlCodeResult parseCode(String codeContent) {
        if (codeContent == null || codeContent.isBlank()) {
            throw new IncompleteCodeException("未生成有效的 HTML 页面，请重新生成");
        }
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(extractHtmlCode(extractFence(codeContent)));
        return result;
    }

    /** 围栏可以缺少结束标记，但其中的 HTML 文档必须完整。 */
    private String extractFence(String content) {
        if (HTML_START.matcher(content).find()) {
            return content;
        }
        Matcher opening = HTML_FENCE.matcher(content);
        if (!opening.find()) {
            return content;
        }
        // 文档结束位置交给 HTML 提取，避免截断脚本模板字符串中的 Markdown 标记。
        return content.substring(opening.end());
    }

    /** 只保存完整文档，不把模型的解释文字或截断结果回退为网页。 */
    private String extractHtmlCode(String content) {
        Matcher tokens = HTML_TOKEN.matcher(content);
        int doctypeStart = -1;
        int documentStart = -1;
        while (tokens.find()) {
            String token = tokens.group();
            String name = tokens.group(2);
            if (name == null) {
                if (token.startsWith("<!--")) {
                    if (documentStart >= 0 && !token.endsWith("-->")) {
                        throw incompleteHtml();
                    }
                } else if (documentStart < 0) {
                    doctypeStart = tokens.start();
                }
                continue;
            }
            name = name.toLowerCase(Locale.ROOT);
            boolean closing = "/".equals(tokens.group(1));
            if ("html".equals(name)) {
                if (!closing && documentStart < 0) {
                    documentStart = doctypeStart >= 0 ? doctypeStart : tokens.start();
                } else if (closing && documentStart >= 0) {
                    return content.substring(documentStart, tokens.end()).trim();
                }
            } else if (!closing && RAW_TEXT_ELEMENTS.contains(name)) {
                Matcher end = Pattern.compile("</" + name + "\\s*>", Pattern.CASE_INSENSITIVE).matcher(content);
                if (!end.find(tokens.end())) {
                    throw incompleteHtml();
                }
                tokens.region(end.end(), content.length());
            }
        }
        if (documentStart >= 0 || doctypeStart >= 0) {
            throw incompleteHtml();
        }
        throw new IncompleteCodeException("回复中没有完整的 HTML 页面，请重新生成");
    }

    private IncompleteCodeException incompleteHtml() {
        return new IncompleteCodeException("生成的 HTML 不完整，可能已被截断，请重新生成");
    }
}
