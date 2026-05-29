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
}
