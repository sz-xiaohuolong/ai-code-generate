package com.xhl.aicodegenerate.langgraph4j;

import com.xhl.aicodegenerate.constant.AppConstant;
import com.xhl.aicodegenerate.entity.App;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.langgraph4j.state.WorkflowContext;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.service.AppService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootTest
class CodeGenWorkflowTest {

    @Resource
    private AppService appService;

    @Test
    void testTechBlogWorkflow() {
        WorkflowContext result = new CodeGenWorkflow().executeWorkflow("创建一个技术博客网站，需要展示编程教程和系统架构");
        Assertions.assertNotNull(result);
        System.out.println("生成类型: " + result.getGenerationType());
        System.out.println("生成的代码目录: " + result.getGeneratedCodeDir());
        System.out.println("构建结果目录: " + result.getBuildResultDir());
    }

    @Test
    void testCorporateWorkflow() {
        WorkflowContext result = executeWorkflowAndPublishToApp(
                "创建一个简洁企业官网，多文件，展示公司形象和业务介绍，包含首页横幅、服务介绍和联系方式，总代码不超过 180 行",
                "工作流企业官网");
        Assertions.assertNotNull(result);
        System.out.println("生成类型: " + result.getGenerationType());
        System.out.println("生成的代码目录: " + result.getGeneratedCodeDir());
        System.out.println("构建结果目录: " + result.getBuildResultDir());
    }

    @Test
    void testVueProjectWorkflow() {
//        WorkflowContext result = new CodeGenWorkflow().executeWorkflow("创建一个Vue前端项目，包含用户管理和数据展示功能");
        WorkflowContext result = executeWorkflowAndPublishToApp("创建一个Vue前端项目，包含用户管理和数据展示功能", "工作流Vue项目");
        Assertions.assertNotNull(result);
        System.out.println("生成类型: " + result.getGenerationType());
        System.out.println("生成的代码目录: " + result.getGeneratedCodeDir());
        System.out.println("构建结果目录: " + result.getBuildResultDir());
    }

    @Test
    void testSimpleHtmlWorkflow() {
        WorkflowContext result = new CodeGenWorkflow().executeWorkflow("创建一个简单的个人主页");
        Assertions.assertNotNull(result);
        System.out.println("生成类型: " + result.getGenerationType());
        System.out.println("生成的代码目录: " + result.getGeneratedCodeDir());
        System.out.println("构建结果目录: " + result.getBuildResultDir());
    }

    @Test
    void imageCollectionPromptShouldGenerateGenericAssetsWhenInformationIsMissing() throws Exception {
        String prompt = new ClassPathResource("prompt/image-collection-system-prompt.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        Assertions.assertTrue(prompt.contains("信息不足"));
        Assertions.assertTrue(prompt.contains("不要反问"));
        Assertions.assertTrue(prompt.contains("通用素材"));
    }

    @Test
    void extractFirstAssetUrlShouldSupportMarkdownCodeAndLinks() {
        String imageListStr = """
                - **品牌 Logo** → `/api/static/workflow_assets/app_0/logo_demo.svg`
                - **办公环境图** → https://images.pexels.com/photos/demo.jpeg?auto=compress
                """;
        Assertions.assertEquals("/api/static/workflow_assets/app_0/logo_demo.svg",
                extractFirstAssetUrl(imageListStr));
    }

    /**
     * 测试专用用户 ID，禁止使用真实用户 ID（如 407379598603583488L），避免污染线上真实用户的“我的应用列表”。
     */
    private static final Long TEST_USER_ID = 999999999999999999L;

    /**
     * 测试辅助方法：把工作流产物注册成测试 App 并执行验证。
     */
    private WorkflowContext executeWorkflowAndPublishToApp(String prompt, String appName) {
        Long userId = Long.getLong("workflow.userId", TEST_USER_ID);
        App app = createWorkflowApp(prompt, appName, userId);
        try {
            WorkflowContext result = new CodeGenWorkflow().executeWorkflow(prompt, app.getId(), userId);
            Assertions.assertNotNull(result);
            Assertions.assertNull(result.getErrorMessage(), result.getErrorMessage());
            Assertions.assertNotNull(result.getGenerationType());

            App updateApp = new App();
            updateApp.setId(app.getId());
            updateApp.setCodeGenType(result.getGenerationType().getValue());
            updateApp.setCover(extractFirstAssetUrl(result.getImageListStr()));
            updateApp.setEditTime(LocalDateTime.now());
            Assertions.assertTrue(appService.updateById(updateApp));

            User loginUser = new User();
            loginUser.setId(userId);
            String deployUrl = appService.deployApp(app.getId(), loginUser);
            App savedApp = appService.getById(app.getId());
            Assertions.assertNotNull(savedApp.getDeployKey());
            System.out.println("测试 App ID: " + savedApp.getId());
            System.out.println("测试 App 名称: " + savedApp.getAppName());
            System.out.println("测试 App 封面: " + savedApp.getCover());
            System.out.println("测试预览地址: " + deployUrl);
            System.out.println("测试用户 ID: " + userId);
            return result;
        } finally {
            // 测试环境默认清理生成的测试应用，避免数据库残留脏数据；若排查问题可通过 -Dworkflow.keepApp=true 保留
            if (!Boolean.getBoolean("workflow.keepApp")) {
                try {
                    appService.deleteApp(app.getId());
                } catch (Exception ignored) {
                }
            }
        }
    }

    private App createWorkflowApp(String prompt, String appName, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        App app = new App();
        app.setAppName(appName);
        app.setCover(AppConstant.CODE_DEPLOY_HOST + "/placeholder-workflow-cover.svg");
        app.setInitPrompt(prompt);
        // 先根据应用特征预置合法 codeGenType，避免流程中断时产生 codeGenType 为 NULL 的脏数据
        CodeGenTypeEnum defaultType = appName.contains("Vue") ? CodeGenTypeEnum.VUE_PROJECT : CodeGenTypeEnum.MULTI_FILE;
        app.setCodeGenType(defaultType.getValue());
        app.setPriority(0);
        app.setUserId(userId);
        app.setEditTime(now);
        app.setCreateTime(now);
        app.setUpdateTime(now);
        app.setIsDelete(0);
        Assertions.assertTrue(appService.save(app));
        Assertions.assertNotNull(app.getId());
        return app;
    }

    private String extractFirstAssetUrl(String imageListStr) {
        if (imageListStr == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("`(/api/static/[^`]+|https?://[^`]+)`|\\((/api/static/[^)]+|https?://[^)]+)\\)|(?:→\\s*)(/api/static/\\S+|https?://\\S+)");
        Matcher matcher = pattern.matcher(imageListStr);
        if (!matcher.find()) {
            return null;
        }
        for (int i = 1; i <= matcher.groupCount(); i++) {
            if (matcher.group(i) != null) {
                return matcher.group(i);
            }
        }
        return null;
    }
}
