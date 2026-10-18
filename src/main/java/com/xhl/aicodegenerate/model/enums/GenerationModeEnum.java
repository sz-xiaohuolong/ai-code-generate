package com.xhl.aicodegenerate.model.enums;

import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import lombok.Getter;

/** 应用创建后固定的生成方式，与代码类型独立。 */
@Getter
public enum GenerationModeEnum {
    STANDARD("standard"),
    WORKFLOW("workflow");

    private final String value;

    GenerationModeEnum(String value) {
        this.value = value;
    }

    public static GenerationModeEnum resolve(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        for (GenerationModeEnum mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的生成模式");
    }
}
