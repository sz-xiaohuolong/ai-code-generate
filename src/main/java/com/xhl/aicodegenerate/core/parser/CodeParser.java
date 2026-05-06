package com.xhl.aicodegenerate.core.parser;

/**
 * 代码解析器策略接口,统一方法的解析参数
 * 由实现类决定返回什么类型
 */
public interface CodeParser<T> {

    /**
     * 解析代码内容
     * 
     * @param codeContent 原始代码内容
     * @return 解析后的结果对象
     */
    T parseCode(String codeContent);
}
