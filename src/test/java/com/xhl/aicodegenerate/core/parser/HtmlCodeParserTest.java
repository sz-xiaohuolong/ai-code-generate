package com.xhl.aicodegenerate.core.parser;

import com.xhl.aicodegenerate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class HtmlCodeParserTest {
    private final HtmlCodeParser parser = new HtmlCodeParser();
    private static final String PAGE = "<!DOCTYPE html>\n<html lang=\"zh-CN\"><head><title>赛车</title></head><body><button>开始游戏</button></body></html>";

    @ParameterizedTest
    @ValueSource(strings = {"```html\n", "```HTML index.html\r\n", "```\n", ""})
    void explanationsOutsideDocumentAreNeverSaved(String opening) {
        String reply = "这是生成的完整页面。\n" + opening + PAGE + "\n```\n可以点击开始游戏。";

        assertEquals(PAGE, parser.parseCode(reply).getHtmlCode());
    }

    @Test
    void completeDocumentWithoutClosingFenceIsStillExtracted() {
        assertEquals(PAGE, parser.parseCode("这是页面。\n```html\n" + PAGE).getHtmlCode());
    }

    @Test
    void markdownAndHtmlLookingStringsInsideScriptArePreserved() {
        String html = "<!doctype html><html><body><script>const example = '</html>'; const fence = '```';</script><p>可用</p></body></html>";

        assertEquals(html, parser.parseCode("说明\n```html\n" + html + "\n```\n完成").getHtmlCode());
    }

    @Test
    void markdownFenceInsideJavascriptTemplateDoesNotTruncateThePage() {
        String html = "<html><body><script>const example = `示例\n```\n结束`;</script><p>可用</p></body></html>";

        assertEquals(html, parser.parseCode("```html\n" + html + "\n```").getHtmlCode());
    }

    @Test
    void rawDocumentCanContainHtmlFenceInsideJavascriptTemplate() {
        String html = "<!doctype html><html><body><script>const example = `\n```html\n<div>示例</div>\n```\n`;</script></body></html>";

        assertEquals(html, parser.parseCode(html).getHtmlCode());
    }

    @Test
    void commentsAndQuotedAttributesCannotEndTheDocument() {
        String html = "<html><body><!-- </html> --><div title='</html>'>可用</div></body></html>";

        assertEquals(html, parser.parseCode("说明\n" + html + "\n完成").getHtmlCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "说明\n```html\n<!doctype html><html><body><script>ctx.fillStyle",
            "```html\n<html><body><script>ctx.fillStyle\n```",
            "<html><body><script>const example = '</html>';",
            "<html><head><style>body { color: red;",
            "<html><body><!-- </html>",
            "<html><body><h1>未完成</h1>"
    })
    void truncatedDocumentIsRejected(String reply) {
        BusinessException error = assertThrows(BusinessException.class, () -> parser.parseCode(reply));

        assertTrue(error.getMessage().contains("不完整"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "生成失败，请稍后重试", "```html\n```"})
    void responseWithoutHtmlIsRejected(String reply) {
        assertThrows(BusinessException.class, () -> parser.parseCode(reply));
    }
}
