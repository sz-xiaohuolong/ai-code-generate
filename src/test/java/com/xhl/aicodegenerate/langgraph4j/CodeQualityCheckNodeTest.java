package com.xhl.aicodegenerate.langgraph4j;

import com.xhl.aicodegenerate.langgraph4j.node.CodeQualityCheckNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class CodeQualityCheckNodeTest {

    @TempDir
    Path tempDir;

    @Test
    void readAndConcatenateCodeFilesShouldIncludeCodeFilesAndSkipBuildArtifacts() throws Exception {
        Files.writeString(tempDir.resolve("index.html"), "<html></html>");
        Files.writeString(tempDir.resolve("style.css"), "body { color: #333; }");
        Files.createDirectories(tempDir.resolve("dist"));
        Files.writeString(tempDir.resolve("dist").resolve("bundle.js"), "console.log('skip');");
        Files.writeString(tempDir.resolve("README.md"), "# skip");

        String content = CodeQualityCheckNode.readAndConcatenateCodeFiles(tempDir.toString());

        Assertions.assertTrue(content.contains("## 文件: index.html"));
        Assertions.assertTrue(content.contains("## 文件: style.css"));
        Assertions.assertFalse(content.contains("bundle.js"));
        Assertions.assertFalse(content.contains("README.md"));
    }

    @Test
    void readAndConcatenateCodeFilesShouldReturnBlankWhenNoCodeFileExists() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# skip");

        String content = CodeQualityCheckNode.readAndConcatenateCodeFiles(tempDir.toString());

        Assertions.assertTrue(content.isBlank());
    }
}
