package com.xhl.aicodegenerate.knowledge;

import com.xhl.aicodegenerate.controller.KnowledgeController;
import com.xhl.aicodegenerate.exception.GlobalExceptionHandler;
import com.xhl.aicodegenerate.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实内嵌容器解析 multipart，验证超限异常发生在控制器之前时仍有明确提示。 */
class KnowledgeMultipartLimitTest {
    @Configuration(proxyBeanMethods = false)
    @Import({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, MultipartAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            GlobalExceptionHandler.class})
    static class Config {
        @Bean KnowledgeController controller() {
            return new KnowledgeController(mock(KnowledgeStore.class), mock(KnowledgeDocumentService.class),
                    mock(UserService.class), new KnowledgeProperties());
        }
    }
    @Test void oversizedMultipartReturnsCapacityErrorInsteadOfSystemError() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            TestPropertyValues.of("server.port=0", "spring.servlet.multipart.max-file-size=32B",
                    "spring.servlet.multipart.max-request-size=128B").applyTo(context);
            context.register(Config.class); context.refresh();
            String boundary = "knowledge-test-boundary";
            String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.md\"\r\n"
                    + "Content-Type: text/plain\r\n\r\n" + "x".repeat(256) + "\r\n--" + boundary + "--\r\n";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/knowledge/documents"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertTrue(response.body().contains("40000"), response.body());
            assertTrue(response.body().contains("容量"), response.body());
            assertFalse(response.body().contains("系统错误"), response.body());
        }
    }
}
