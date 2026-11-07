package com.xhl.aicodegenerate.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhl.aicodegenerate.generation.GenerationExecution;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeGenerationGateTest {
    @Test void cannotWriteOrGenerateUntilKnowledgeHasBeenPrepared() throws Exception {
        try (var run = new GenerationExecution(UUID.randomUUID().toString(), Path.of("target/gate"), Duration.ofSeconds(5), () -> true)) {
            run.requireKnowledge();
            assertThrows(KnowledgeException.class, run::assertGenerationAllowed);
            var written = new AtomicBoolean();
            assertThrows(KnowledgeException.class, () -> run.files(() -> { written.set(true); return null; }));
            assertFalse(written.get());
            var source = new KnowledgeTypes.Source(1, 2, 1, "接口.md", "build", "chunk", "GET /orders", 0.9);
            run.attachKnowledge(new KnowledgeContext(mock(KnowledgeStore.class), run.id(), 1, 1, List.of(source), new ObjectMapper()));
            run.assertGenerationAllowed();
            run.files(() -> { written.set(true); return null; });
            assertTrue(written.get());
            assertThrows(IllegalStateException.class, () -> run.attachKnowledge(run.knowledge()));
        }
    }
}
