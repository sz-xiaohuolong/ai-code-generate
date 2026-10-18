package com.xhl.aicodegenerate.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.Objects;

/**
 * 应用级别对话记忆 id。
 * equals/hashCode/toString 只使用 appId，确保同一个应用共享同一份 ChatMemory。
 *
 * <p>userId 不参与 Redis key 和记忆隔离，只用于 ChatMemoryStore 写入 chat_history 时记录操作者。</p>
 */
@Data
public class AppChatMemoryId implements Serializable {

    private final Long appId;

    private final Long userId;

    /** 仅用于本轮工具执行隔离，不改变应用级历史键。 */
    private final String taskId;

    public AppChatMemoryId(Long appId, Long userId) {
        this(appId, userId, null);
    }

    public AppChatMemoryId(Long appId, Long userId, String taskId) {
        this.appId = appId;
        this.userId = userId;
        this.taskId = taskId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AppChatMemoryId that)) {
            return false;
        }
        return Objects.equals(appId, that.appId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(appId);
    }

    @Override
    public String toString() {
        return String.valueOf(appId);
    }
}
