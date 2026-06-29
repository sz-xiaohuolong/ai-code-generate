package com.xhl.aicodegenerate.langgraph4j.tools;

import com.xhl.aicodegenerate.constant.AppConstant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class LocalWorkflowAssetServiceTest {

    @Test
    void saveSvgWritesFileAndReturnsStaticPath() throws Exception {
        LocalWorkflowAssetService assetService = new LocalWorkflowAssetService();

        String url = assetService.saveSvg(123L, "logo", "<svg><text>Hello</text></svg>");

        Assertions.assertTrue(url.startsWith("/api/static/workflow_assets/app_123/"));
        Assertions.assertTrue(url.endsWith(".svg"));
        Path savedPath = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                url.replace("/api/static/", ""));
        Assertions.assertTrue(Files.exists(savedPath));
        Assertions.assertEquals("<svg><text>Hello</text></svg>",
                Files.readString(savedPath, StandardCharsets.UTF_8));
    }
}
