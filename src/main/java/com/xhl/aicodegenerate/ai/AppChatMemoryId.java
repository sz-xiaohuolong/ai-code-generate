package com.xhl.aicodegenerate.ai;

import java.io.Serializable;
import java.util.Objects;

/**
 * 应用级别对话记忆 id。
 * equals/hashCode/toString 只使用 appId，确保同一个应用共享同一份 ChatMemory。
 *
 * <p>userId 不参与 Redis key 和记忆隔离，只用于 ChatMemoryStore 写入 chat_history 时记录操作者。</p>
 */
public class AppChatMemoryId implements Serializable {

    private final Long appId;

    private final Long userId;

    public AppChatMemoryId(Long appId, Long userId) {
        this.appId = appId;
        this.userId = userId;
    }

    public Long getAppId() {
        return appId;
    }

    public Long getUserId() {
        return userId;
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
