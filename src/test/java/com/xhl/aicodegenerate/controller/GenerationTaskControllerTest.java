package com.xhl.aicodegenerate.controller;

import cn.hutool.json.JSONUtil;
import com.xhl.aicodegenerate.generation.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class GenerationTaskControllerTest {
    @Test void terminalSnapshotIsDeliveredAndSnowflakeIdsRemainStrings() {
        var task = new GenerationTask(); task.setId("task"); task.setAppId(469318520992325633L);
        task.setUserId(469318520992325635L); task.setStatus(TaskStatus.SUCCEEDED); task.setOutput("最终结果");
        task.setUserMessageId(469318520992325636L); task.setAiMessageId(469318520992325637L);
        var events = GenerationTaskController.snapshots(Flux.just(task)).collectList().block(Duration.ofSeconds(2));
        assertNotNull(events); assertEquals(1,events.size());
        assertEquals("task-snapshot",events.getFirst().event());
        var data=JSONUtil.parseObj(events.getFirst().data());
        assertInstanceOf(String.class,data.get("appId")); assertEquals("469318520992325633",data.getStr("appId"));
        assertEquals("最终结果",data.getStr("output"));
        assertInstanceOf(String.class,data.get("aiMessageId")); assertEquals("469318520992325637",data.getStr("aiMessageId"));
    }
}
