package com.xhl.aicodegenerate.core.saver;

import com.xhl.aicodegenerate.ai.model.HtmlCodeResult;
import com.xhl.aicodegenerate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HtmlCodeFileSaverTest {
    @TempDir Path root;

    @Test
    void incompleteStructuredResultCannotOverwriteExistingPage() throws Exception {
        Path entry = root.resolve("index.html");
        Files.writeString(entry, "<html><body>上次可用页面</body></html>");
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("说明\n```html\n<html><body><script>ctx.fillStyle");

        assertThrows(BusinessException.class, () -> new HtmlCodeFileSaverTemplate().saveCode(result, 1L, root));
        assertEquals("<html><body>上次可用页面</body></html>", Files.readString(entry));
    }

    @Test
    void structuredReplyIsNormalizedBeforeWriting() throws Exception {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("说明\n```html\n<html><body>完整页面</body></html>\n```\n生成完成");

        new HtmlCodeFileSaverTemplate().saveCode(result, 1L, root);

        assertEquals("<html><body>完整页面</body></html>", Files.readString(root.resolve("index.html")));
    }
}
