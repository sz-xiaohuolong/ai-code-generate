package com.xhl.aicodegenerate.langgraph4j.node;

import cn.hutool.core.util.StrUtil;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import com.xhl.aicodegenerate.core.builder.VueProjectBuilder;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.utils.SpringContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.stereotype.Component;

import java.io.File;

import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 项目构建节点。
 * <p>
 * 条件边保证只有 Vue 工程会进入本节点。
 * </p>
 */
@Slf4j
@Component
public class ProjectBuilderNode {

    public static AsyncNodeAction<MessagesState<String>> create() {
        return node_async(state -> {
            WorkflowContext context = WorkflowContext.getContext(state);
            log.info("执行节点: 项目构建");
            context.setCurrentStep("项目构建");

            String generatedCodeDir = context.getGeneratedCodeDir();
            if (StrUtil.isBlank(generatedCodeDir)) {
                context.setErrorMessage("代码生成目录为空，无法构建项目");
                context.setBuildResultDir(generatedCodeDir);
                return WorkflowContext.saveContext(context);
            }

            String buildResultDir;
            try {
                VueProjectBuilder vueBuilder = SpringContextUtil.getBean(VueProjectBuilder.class);
                boolean buildSuccess = vueBuilder.buildProject(generatedCodeDir);
                if (buildSuccess) {
                    buildResultDir = generatedCodeDir + File.separator + "dist";
                    log.info("Vue 项目构建成功，dist 目录: {}", buildResultDir);
                } else {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败");
                }
            } catch (Exception e) {
                log.error("Vue 项目构建异常: {}", e.getMessage(), e);
                buildResultDir = generatedCodeDir;
                context.setErrorMessage(e.getMessage());
            }

            context.setBuildResultDir(buildResultDir);
            log.info("项目构建节点完成，最终目录: {}", buildResultDir);
            return WorkflowContext.saveContext(context);
        });
    }
}
