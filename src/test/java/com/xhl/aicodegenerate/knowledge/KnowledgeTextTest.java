package com.xhl.aicodegenerate.knowledge;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 检验真实文本是否完整保留，不用字符数冒充模型 Token 数。 */
class KnowledgeTextTest {
    @Test void acceptsSupportedUtf8AndRejectsInvalidOrOversizedFiles() {
        assertEquals("# 接口\n订单", KnowledgeText.decode("接口.MD", "\ufeff# 接口\r\n订单".getBytes(StandardCharsets.UTF_8), 100));
        assertThrows(RuntimeException.class, () -> KnowledgeText.decode("接口.pdf", new byte[]{1}, 100));
        assertThrows(RuntimeException.class, () -> KnowledgeText.decode("接口.txt", new byte[]{(byte) 0xff}, 100));
        assertThrows(RuntimeException.class, () -> KnowledgeText.decode("接口.txt", "   ".getBytes(), 100));
        assertThrows(RuntimeException.class, () -> KnowledgeText.decode("../接口.txt", "你好".getBytes(), 100));
        assertThrows(RuntimeException.class, () -> KnowledgeText.decode("接口.txt", "你好".getBytes(StandardCharsets.UTF_8), 5));
        assertEquals("你好", KnowledgeText.decode("接口.txt", "你好".getBytes(StandardCharsets.UTF_8), 6));
    }

    @Test void chunksRetainEveryCharacterAndNeverBreakUnicodePairs() {
        String input = "# 订单接口\n\n" + "字段 😀：状态为已支付。\n".repeat(100);
        var chunks = KnowledgeText.chunks(input, 80, 10);
        assertTrue(chunks.size() > 1);
        int through = 0;
        for (var chunk : chunks) {
            assertTrue(chunk.start() <= through);
            assertTrue(chunk.end() > through);
            assertEquals(input.substring(chunk.start(), chunk.end()), chunk.text());
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
            assertTrue(chunk.text().length() <= 80);
            through = chunk.end();
        }
        assertEquals(input.length(), through);
        assertThrows(IllegalArgumentException.class, () -> KnowledgeText.chunks(input, 10, 10));
    }
}
