package com.xhl.aicodegenerate.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Data
@Description("生成 HTML 代码文件的结果")
public class HtmlCodeResult {

    private String htmlCode;

    private String description;
}
