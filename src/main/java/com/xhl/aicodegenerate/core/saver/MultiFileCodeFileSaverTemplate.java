package com.xhl.aicodegenerate.core.saver;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.ai.model.MultiFileCodeResult;
import com.xhl.aicodegenerate.core.parser.HtmlCodeParser;
import com.xhl.aicodegenerate.exception.IncompleteCodeException;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;

import java.util.regex.Pattern;

/** 多文件代码保存器，任何文件写入前验证交付完整性。 */
public class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {
    private static final Pattern STYLESHEET = reference("link", "href", "style\\.css");
    private static final Pattern SCRIPT = reference("script", "src", "script\\.js");

    @Override
    public CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    protected void saveFiles(MultiFileCodeResult result, String baseDirPath) {
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
        writeToFile(baseDirPath, "style.css", result.getCssCode());
        writeToFile(baseDirPath, "script.js", result.getJsCode());
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        String html = new HtmlCodeParser().parseCode(result.getHtmlCode()).getHtmlCode();
        String markup = html.replaceAll("(?s)<!--.*?-->", "");
        if (STYLESHEET.matcher(markup).find() && StrUtil.isBlank(result.getCssCode())) {
            throw new IncompleteCodeException("多文件交付不完整：HTML 引用的 style.css 缺失，请输出真实页面样式");
        }
        if (SCRIPT.matcher(markup).find() && StrUtil.isBlank(result.getJsCode())) {
            throw new IncompleteCodeException("多文件交付不完整：HTML 引用的 script.js 缺失，请输出实际交互逻辑");
        }
        result.setHtmlCode(html);
    }

    private static Pattern reference(String tag, String attribute, String file) {
        String path = "(?:\\./)?" + file + "(?:[?#][^\\s\"'<>]*)?";
        return Pattern.compile("<" + tag + "\\b[^>]*\\b" + attribute + "\\s*=\\s*(?:\"" + path
                + "\"|'" + path + "'|" + path + "(?=[\\s>]))", Pattern.CASE_INSENSITIVE);
    }
}
