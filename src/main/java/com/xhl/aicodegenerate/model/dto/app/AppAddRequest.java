package com.xhl.aicodegenerate.model.dto.app;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppAddRequest implements Serializable {


    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /** 新建应用的生成模式，省略时使用标准模式。 */
    private String generationMode;


    private static final long serialVersionUID = 1L;
}
