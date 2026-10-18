package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import java.util.function.Consumer;

@FunctionalInterface
public interface VueRepairer {
    void repair(AppChatMemoryId memoryId, String diagnostic, Consumer<String> output);
}
