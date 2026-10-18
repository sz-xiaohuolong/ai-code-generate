package com.xhl.aicodegenerate.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实无头浏览器校验首屏，不调用外部模型。 */
class PreviewVerifierTest {
    @TempDir Path root;
    @Test void validPagePassesAndJavascriptRuntimeFailureIsRejected() throws Exception {
        Files.writeString(root.resolve("index.html"), "<!doctype html><html><body><h1>可用页面</h1></body></html>");
        var verifier = new PreviewVerifier();
        assertTrue(verifier.verify(root, false, null).success());
        Files.writeString(root.resolve("index.html"), "<!doctype html><html><body><h1>错误页面</h1><script>throw new Error('p0-runtime-failure')</script></body></html>");
        var failure = verifier.verify(root, false, null);
        assertFalse(failure.success()); assertTrue(failure.diagnostic().contains("p0-runtime-failure"));
    }
    @Test void staticPlaceholderMustNotMasqueradeAsVueMount() throws Exception {
        Files.createDirectories(root.resolve("dist"));
        Files.writeString(root.resolve("dist/index.html"),"<!doctype html><html><body><div id='app'><p>加载中</p></div></body></html>");
        assertFalse(new PreviewVerifier().verify(root,true,null).success());
    }
    @Test void vueWithoutMountedContentFails() throws Exception {
        Files.createDirectories(root.resolve("dist"));
        Files.writeString(root.resolve("dist/index.html"), "<!doctype html><html><body><div id='app'></div></body></html>");
        var result = new PreviewVerifier().verify(root, true, null);
        assertFalse(result.success()); assertTrue(result.diagnostic().contains("未成功挂载"));
    }
}
