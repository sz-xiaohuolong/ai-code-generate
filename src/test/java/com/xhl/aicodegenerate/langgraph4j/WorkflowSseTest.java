package com.xhl.aicodegenerate.langgraph4j;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

class WorkflowSseTest {

    @Test
    void formatSseEventShouldUseNamedEventAndJsonData() {
        String event = CodeGenWorkflow.formatSseEvent("step_completed", Map.of(
                "stepNumber", 1,
                "currentStep", "图片收集"
        ));

        Assertions.assertTrue(event.startsWith("event: step_completed\n"));
        Assertions.assertTrue(event.endsWith("\n\n"));
        String dataLine = event.lines()
                .filter(line -> line.startsWith("data: "))
                .findFirst()
                .orElseThrow();
        Assertions.assertTrue(JSONUtil.isTypeJSON(dataLine.substring("data: ".length())));
    }
}
