package com.xhl.aicodegenerate.core.parser;

import com.xhl.aicodegenerate.ai.model.MultiFileCodeResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiFileCodeParserTest {

    private final MultiFileCodeParser multiFileCodeParser = new MultiFileCodeParser();

    @Test
    void parseCodeShouldSplitInlineStyleAndScriptFromHtmlBlock() {
        String codeContent = """
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <title>测试页面</title>
                    <style>
                        body { color: #333; }
                        h1 { color: blue; }
                    </style>
                </head>
                <body>
                    <h1>Hello</h1>
                    <script>
                        console.log('loaded');
                    </script>
                </body>
                </html>
                ```
                """;

        MultiFileCodeResult result = multiFileCodeParser.parseCode(codeContent);

        assertNotNull(result.getHtmlCode());
        assertNotNull(result.getCssCode());
        assertNotNull(result.getJsCode());
        assertTrue(result.getHtmlCode().contains("style.css"));
        assertTrue(result.getHtmlCode().contains("script.js"));
        assertFalse(result.getHtmlCode().contains("<style>"));
        assertFalse(result.getHtmlCode().contains("console.log('loaded')"));
        assertTrue(result.getCssCode().contains("h1 { color: blue; }"));
        assertTrue(result.getJsCode().contains("console.log('loaded')"));
    }

    @Test
    void parseCodeShouldSupportCodeFencesWithFilenames() {
        String codeContent = """
                ```html index.html
                <!DOCTYPE html>
                <html>
                <head><title>云南旅游</title></head>
                <body><h1>昆明石林</h1></body>
                </html>
                ```

                ```css style.css
                body { background-color: #f0f0f0; }
                h1 { color: #0284c7; }
                ```

                ```javascript script.js
                console.log('initialized');
                ```
                """;

        MultiFileCodeResult result = multiFileCodeParser.parseCode(codeContent);

        assertNotNull(result.getHtmlCode());
        assertNotNull(result.getCssCode());
        assertNotNull(result.getJsCode());
        assertTrue(result.getHtmlCode().contains("昆明石林"));
        assertTrue(result.getCssCode().contains("background-color: #f0f0f0;"));
        assertTrue(result.getJsCode().contains("initialized"));
    }

    @Test
    void parseCodeShouldHandleTruncatedEndingBlockGracefully() {
        String codeContent = """
                ```html
                <!DOCTYPE html>
                <html><body><h1>页面</h1></body></html>
                ```

                ```css
                body { margin: 0; }
                """; // Truncated without closing ```

        MultiFileCodeResult result = multiFileCodeParser.parseCode(codeContent);

        assertNotNull(result.getHtmlCode());
        assertNotNull(result.getCssCode());
        assertTrue(result.getCssCode().contains("margin: 0;"));
    }
}
