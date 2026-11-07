package com.xhl.aicodegenerate.core.saver;

import com.xhl.aicodegenerate.ai.model.MultiFileCodeResult;
import com.xhl.aicodegenerate.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MultiFileCodeFileSaverTest {
    @TempDir Path root;

    @Test
    void missingReferencedStylesCannotBeReplacedByGenericFallback() throws Exception {
        Files.writeString(root.resolve("index.html"), "上次成功页面");
        Files.writeString(root.resolve("style.css"), ".logo { width: 32px; }");
        var result = new MultiFileCodeResult();
        result.setHtmlCode("<html><head><link rel='stylesheet' href='style.css'></head><body>博客</body></html>");

        BusinessException error = assertThrows(BusinessException.class,
                () -> new MultiFileCodeFileSaverTemplate().saveCode(result, 1L, root));

        assertTrue(error.getMessage().contains("style.css"));
        assertEquals("上次成功页面", Files.readString(root.resolve("index.html")));
        assertEquals(".logo { width: 32px; }", Files.readString(root.resolve("style.css")));
    }

    @Test
    void missingReferencedScriptCannotBecomeFakeSuccessfulInteraction() {
        var result = new MultiFileCodeResult();
        result.setHtmlCode("<html><body><button>开始</button><script src='script.js'></script></body></html>");

        BusinessException error = assertThrows(BusinessException.class,
                () -> new MultiFileCodeFileSaverTemplate().saveCode(result, 1L, root));

        assertTrue(error.getMessage().contains("script.js"));
        assertFalse(Files.exists(root.resolve("index.html")));
    }

    @Test
    void relativeReferenceWithQueryStillRequiresRealStyles() {
        var result = new MultiFileCodeResult();
        result.setHtmlCode("<html><head><link rel=stylesheet href=./style.css?v=2></head><body>博客</body></html>");
        assertThrows(BusinessException.class,
                () -> new MultiFileCodeFileSaverTemplate().saveCode(result, 1L, root));
        assertFalse(Files.exists(root.resolve("index.html")));
    }

    @Test
    void pageWithoutExternalReferencesDoesNotNeedPlaceholderFiles() throws Exception {
        var result = new MultiFileCodeResult();
        result.setHtmlCode("<html><body>静态说明页面</body></html>");
        new MultiFileCodeFileSaverTemplate().saveCode(result, 1L, root);
        assertEquals(result.getHtmlCode(), Files.readString(root.resolve("index.html")));
        assertFalse(Files.exists(root.resolve("style.css")));
        assertFalse(Files.exists(root.resolve("script.js")));
    }
}
