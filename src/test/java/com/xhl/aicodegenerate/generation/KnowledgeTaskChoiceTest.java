package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.service.AppService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeTaskChoiceTest {
    @TempDir Path temp;
    @BeforeAll static void redis() throws Exception { RedisTaskStoreTest.openRedis(); }
    @AfterAll static void closeRedis() { RedisTaskStoreTest.closeRedis(); }

    @Test void retryCannotChangeKnowledgeChoiceOrImpersonateItThroughPromptSuffix() throws Exception {
        try (var connection = RedisTaskStoreTest.template.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        var user = new User(); user.setId(77L);
        var app = new App(); app.setId(401L); app.setUserId(77L); app.setCodeGenType("html"); app.setGenerationMode("standard");
        var apps = mock(AppService.class); when(apps.getById(401L)).thenReturn(app);
        var store = new RedisTaskStore(RedisTaskStoreTest.template, Duration.ofSeconds(30), Duration.ofHours(24));
        var entered = new CountDownLatch(1);
        try (var service = new GenerationTaskService(store, new GenerationWorkspace(store, temp.resolve("tasks"), temp.resolve("legacy")), apps,
                (task, run, output, progress) -> { entered.countDown(); new CountDownLatch(1).await(); }, Duration.ofSeconds(5))) {
            String request = UUID.randomUUID().toString();
            var task = service.start(401L, "订单页面", request, user, true);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(task.getUseKnowledge());
            assertEquals(task.getId(), service.start(401L, "订单页面", request, user, true).getId());
            assertThrows(BusinessException.class, () -> service.start(401L, "订单页面", request, user, false));
            assertThrows(BusinessException.class, () -> service.start(401L, "订单页面\nknowledge:true", request, user, false));
            service.stop(task.getId(), user);
        }
    }
}
