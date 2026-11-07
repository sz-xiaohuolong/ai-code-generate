package com.xhl.aicodegenerate.observability;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TraceMetadataTest {
    @Test
    void stripsBodiesSecretsAndHostPathsInsteadOfRegexRedactingErrors() {
        String json = TraceMetadata.json(Map.of("prompt", "secret-input", "response", "secret-output",
                "relativePath", "/Users/private/token", "toolName", "writeFile", "error", "api-key=secret"));
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("/Users/"));
        assertTrue(json.contains("writeFile"));
        assertNull(TraceMetadata.relativePath("../../secret"));
        assertNull(TraceMetadata.relativePath("C:\\secrets\\key"));
        assertNull(TraceMetadata.relativePath("https://host/key"));
        assertEquals("src/App.vue", TraceMetadata.relativePath("src/App.vue"));
        assertFalse(TraceMetadata.errorMessage(new RuntimeException("api-key=secret")).contains("secret"));
    }
}
