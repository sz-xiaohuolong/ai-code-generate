package com.xhl.aicodegenerate.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 仅连接显式指定的隔离数据库，验证真实事务与权限边界。 */
class KnowledgeStoreTest {
    private KnowledgeStore store;
    private JdbcTemplate jdbc;
    private KnowledgeProperties properties;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("knowledge.test.mysql.url");
        assertNotNull(url, "必须指定隔离测试库");
        assertTrue(url.contains("/knowledge_mvp_test"), "禁止连接用户业务库");
        var ds = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE IF NOT EXISTS app(id BIGINT PRIMARY KEY,userId BIGINT NOT NULL,isDelete TINYINT NOT NULL DEFAULT 0)");
        String migration = Files.readString(Path.of("sql/migrations/20261107_private_knowledge.sql"));
        for (int repeat = 0; repeat < 2; repeat++) for (String sql : migration.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        for (String table : new String[]{"knowledge_reference_item", "knowledge_task_reference", "knowledge_operation", "knowledge_document_version", "knowledge_document", "knowledge_space", "app"}) jdbc.execute("DELETE FROM " + table);
        jdbc.update("INSERT INTO app(id,userId) VALUES(11,1),(22,2)");
        properties = new KnowledgeProperties();
        store = new KnowledgeStore(ds, properties);
    }

    private KnowledgeTypes.Work upload(long owner, String text) {
        return store.upload(owner, "接口.md", text.getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString(), null, null);
    }

    @Test void isolatesUsersAndKeepsOldVersionUntilReplacementPublishes() {
        var original = upload(1, "旧接口 /old");
        var other = upload(2, "旧接口 /old");
        assertTrue(store.publish(original.operation(), "profile", 1));
        assertTrue(store.publish(other.operation(), "profile", 1));
        var doc = store.list(1).getFirst();
        var replacement = store.upload(1, "新版.md", "新接口 /new".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString(), doc.id(), doc.revision());
        assertEquals(original.version().id(), store.activeVersions(1).getFirst().id());
        assertEquals("接口.md", store.activeVersions(1).getFirst().name());
        assertThrows(RuntimeException.class, () -> store.delete(2, doc.id()));
        store.fail(replacement.operation(), "EMBEDDING_UNAVAILABLE");
        assertEquals(original.version().id(), store.activeVersions(1).getFirst().id());
        assertEquals("接口.md", store.activeVersions(1).getFirst().name());
        var retry = store.retry(1, doc.id(), replacement.version().id(), UUID.randomUUID().toString());
        assertNotEquals(replacement.operation().buildId(), retry.operation().buildId());
        assertFalse(store.publish(replacement.operation(), "profile", 1));
        assertTrue(store.publish(retry.operation(), "profile", 1));
        assertEquals("新版.md", store.activeVersions(1).getFirst().name());
        store.delete(1, doc.id());
        assertTrue(store.activeVersions(1).isEmpty());
        assertEquals(other.version().id(), store.activeVersions(2).getFirst().id());
        assertFalse(store.publish(retry.operation(), "profile", 1));
    }

    @Test void replayAfterRetryFailsDoesNotStartAnotherAttempt() {
        var work = upload(1, "接口");
        store.fail(work.operation(), "FAILED");
        String request = UUID.randomUUID().toString();
        var first = store.retry(1, work.version().documentId(), work.version().id(), request);
        store.fail(first.operation(), "FAILED_AGAIN");
        var replay = store.retry(1, work.version().documentId(), work.version().id(), request);
        assertFalse(replay.newlyCreated());
        assertEquals(first.operation().buildId(), replay.operation().buildId());
        assertEquals(first.operation().attempt(), replay.operation().attempt());
        assertEquals("FAILED", replay.operation().status());
    }

    @Test void uploadRequestsAreIdempotentAndNamesAreNotUnique() {
        String request = UUID.randomUUID().toString();
        var one = store.upload(1,"资料.txt",new byte[]{65},request,null,null);
        var replay = store.upload(1,"资料.txt",new byte[]{65},request,null,null);
        assertEquals(one.version().id(), replay.version().id());
        assertFalse(replay.newlyCreated());
        assertThrows(RuntimeException.class, () -> store.upload(1,"资料.txt",new byte[]{66},request,null,null));
        store.upload(1,"资料.txt",new byte[]{65},UUID.randomUUID().toString(),null,null);
        assertEquals(2, store.list(1).size());
    }

    @Test void enforcesByteAndCountLimitsWithoutDiscardingOldVersion() {
        properties.setMaxDocuments(2); properties.setMaxTotalBytes(10); properties.setMaxFileBytes(10);
        var a = upload(1,"123456"); store.publish(a.operation(),"profile",1);
        upload(1,"1234");
        assertThrows(RuntimeException.class, () -> upload(1,"1"));
        var doc = store.list(1).stream().filter(d -> d.id() == a.version().documentId()).findFirst().orElseThrow();
        assertThrows(RuntimeException.class, () -> store.upload(1,"接口.md","1234567".getBytes(),UUID.randomUUID().toString(),doc.id(),doc.revision()));
        assertEquals(a.version().id(), store.activeVersions(1).getFirst().id());
        assertEquals(10, store.quota(1).usedBytes());
    }

    @org.junit.jupiter.api.RepeatedTest(10)
    void concurrentUploadsCannotExceedCountAndDeletedProcessingCannotPublish() throws Exception {
        properties.setMaxDocuments(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> upload = () -> {
                ready.countDown(); start.await();
                try { upload(1,"并发资料"); return true; }
                catch (com.xhl.aicodegenerate.exception.BusinessException error) { return false; }
            };
            var first = pool.submit(upload); var second = pool.submit(upload);
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            assertNotEquals(first.get(),second.get());
            assertEquals(1,store.quota(1).documents());
            var pending=upload(2,"待删除资料");
            store.delete(2,pending.version().documentId());
            assertFalse(store.publish(pending.operation(),"profile",1));
            assertTrue(store.activeVersions(2).isEmpty());
        } finally { pool.shutdownNow(); }
    }

    @Test void deletionHidesHistoricalTextAndApplicationDeletionKeepsLibrary() {
        var work = upload(1,"私有接口"); store.publish(work.operation(),"profile",1);
        String task = UUID.randomUUID().toString();
        store.beginReference(task,1,11,101L,102L);
        var source = new KnowledgeTypes.Source(work.version().documentId(),work.version().id(),1,"接口.md",work.operation().buildId(),"chunk-1","私有接口",0.9);
        store.saveReference(task,1,11,java.util.List.of(source));
        store.markSubmitted(task,1,11);
        jdbc.update("UPDATE knowledge_task_reference SET created_at=DATE_SUB(created_at,INTERVAL 30 DAY) WHERE task_id=?",task);
        assertEquals("私有接口",store.reference(1,task).items().getFirst().text());
        assertThrows(RuntimeException.class, () -> store.reference(2,task));
        store.delete(1,work.version().documentId());
        assertNull(store.reference(1,task).items().getFirst().text());
        assertEquals("接口.md",store.reference(1,task).items().getFirst().documentName());
        // 删除前捕获的运行中快照迟到补写，也不能重新暴露原文。
        store.saveReference(task,1,11,java.util.List.of(source));
        assertNull(store.reference(1,task).items().getFirst().text());
        var remaining = upload(1,"另一份资料");store.publish(remaining.operation(),"profile",1);
        jdbc.update("UPDATE app SET isDelete=1 WHERE id=11");
        assertThrows(RuntimeException.class, () -> store.reference(1,task));
        assertThrows(RuntimeException.class, () -> store.markSubmitted(task,1,11));
        store.clearDeletedApplicationReferences();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_task_reference", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_reference_item", Integer.class));
        assertEquals(1,store.activeVersions(1).size());
    }
    @Test void lateFragmentRecoveryPreservesAlreadyConfirmedSubmission() {
        var work = upload(1, "恢复的资料"); store.publish(work.operation(), "profile", 1);
        String task = UUID.randomUUID().toString();
        store.beginReference(task, 1, 11, null, null);
        var source = new KnowledgeTypes.Source(work.version().documentId(), work.version().id(), 1,
                "接口.md", work.operation().buildId(), "chunk", "恢复的资料", 0.9);
        var flaky = org.mockito.Mockito.spy(store);
        org.mockito.Mockito.doThrow(new IllegalStateException("暂时写入失败"))
                .doThrow(new IllegalStateException("暂时写入失败"))
                .doCallRealMethod().when(flaky).saveReference(task, 1, 11, java.util.List.of(source));
        var context = new KnowledgeContext(flaky, task, 1, 11, java.util.List.of(source), new com.fasterxml.jackson.databind.ObjectMapper());
        context.persist(); context.persist(); context.submitted();
        assertEquals("INCOMPLETE", store.reference(1, task).state());
        context.persist(); context.submitted();
        assertEquals("SUBMITTED", store.reference(1, task).state());
        assertEquals("恢复的资料", store.reference(1, task).items().getFirst().text());
    }

}
