package com.xhl.aicodegenerate.core.saver;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.ai.model.MultiFileCodeResult;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;

import lombok.extern.slf4j.Slf4j;

/**
 * 多文件代码保存器
 *
 */
@Slf4j
public class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    @Override
    public CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    protected void saveFiles(MultiFileCodeResult result, String baseDirPath) {
        // 保存 HTML 文件
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
        // 保存 CSS 文件（若模型漏掉 CSS，注入基础通用保底样式，避免 404 导致完全白屏/无样式）
        String cssCode = result.getCssCode();
        if (StrUtil.isBlank(cssCode)) {
            log.warn("多文件代码保存：未提取到 CSS 样式代码，已自动注入基础自适应保底样式");
            cssCode = buildDefaultFallbackCss();
        }
        writeToFile(baseDirPath, "style.css", cssCode);
        // 保存 JavaScript 文件
        String jsCode = result.getJsCode();
        if (StrUtil.isBlank(jsCode)) {
            jsCode = "// 默认交互逻辑\nconsole.log('Page loaded successfully.');\n";
        }
        writeToFile(baseDirPath, "script.js", jsCode);
    }

    private String buildDefaultFallbackCss() {
        return """
                /* 默认基础保底样式表 */
                *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                  line-height: 1.6;
                  color: #333333;
                  background-color: #f8fafc;
                  padding: 20px;
                }
                .container { max-width: 1200px; margin: 0 auto; padding: 0 16px; }
                img { max-width: 100%; height: auto; border-radius: 8px; }
                a { color: #0284c7; text-decoration: none; }
                a:hover { text-decoration: underline; }
                button, .btn {
                  display: inline-block;
                  padding: 10px 20px;
                  background-color: #0284c7;
                  color: #ffffff;
                  border: none;
                  border-radius: 6px;
                  cursor: pointer;
                  font-size: 14px;
                }
                button:hover, .btn:hover { background-color: #0369a1; }
                ul, ol { padding-left: 20px; }
                """;
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        // 至少要有 HTML 代码，CSS 和 JS 可以为空
        if (StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML代码内容不能为空");
        }
    }
}
