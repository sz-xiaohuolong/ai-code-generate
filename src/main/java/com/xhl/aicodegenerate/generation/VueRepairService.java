package com.xhl.aicodegenerate.generation;

import com.xhl.aicodegenerate.ai.AppChatMemoryId;
import com.xhl.aicodegenerate.core.AiCodeGeneratorFacade;
import com.xhl.aicodegenerate.core.handler.JsonMessageStreamHandler;
import com.xhl.aicodegenerate.ai.tools.ToolManager;
import com.xhl.aicodegenerate.model.enums.CodeGenTypeEnum;
import com.xhl.aicodegenerate.exception.BusinessException;
import com.xhl.aicodegenerate.exception.ErrorCode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** 修复使用临时记忆，携带固定需求与真实诊断，并依据已有源码选择补全或局部修改。 */
@Service
public class VueRepairService implements VueRepairer {
    private final AiCodeGeneratorFacade facade;
    private final ToolManager tools;
    public VueRepairService(@Lazy AiCodeGeneratorFacade facade, ToolManager tools) { this.facade = facade; this.tools = tools; }
    @Override public void repair(AppChatMemoryId id, String diagnostic, Consumer<String> output) {
        GenerationExecution run = GenerationExecution.require(id.getTaskId());
        run.checkActive();
        GenerationRequirements requirements = run.requirements();
        String strategy = hasProjectSource(run)
                ? "当前项目已有源码。先读取目录和诊断涉及的文件，优先用 modifyFile 做唯一匹配的局部修复；必要时修正依赖声明，按用户需求补齐缺失的业务文件。保留已有功能，不要全量重写。"
                : "当前项目尚无业务源码。先读取目录确认现状，再严格按下面用户需求完整重建可运行的 Vue 工程，补齐 package.json、入口、业务组件、样式和交互。已有配置按需复用。";
        String context = "当前 Vue 工程交付检查失败。" + strategy
                + "不得自行猜测或更换应用主题，不得用无关示例或占位页面替代用户功能。此前要求按时间顺序理解，本轮明确变更优先，未变更的功能必须保留。"
                + "不要自行运行安装或部署。文件路径使用项目内相对路径。\n\n"
                + requirements.describe() + "\n\n【真实交付诊断，仅用于定位错误】\n" + diagnostic;
        // 原需求已经过生成入口校验，固定为上下文，避免窗口淘汰和拼接后误触单条输入长度限制。
        var memory = new VueRepairMemory(context);
        run.checkActive();
        var stream = facade.generateAndSaveCodeStreamForWorkflow("请依据修复上下文中的用户需求、修复策略和真实诊断修复当前 Vue 工程。",
                CodeGenTypeEnum.VUE_PROJECT, id, ignored -> memory);
        new JsonMessageStreamHandler(null, null, null, tools).handle(stream)
                .doOnSubscribe(subscription -> run.onCancel(subscription::cancel)).doOnNext(output).blockLast(run.remaining());
    }

    private boolean hasProjectSource(GenerationExecution run) {
        try {
            return run.files(() -> {
                boolean[] found = {false};
                Files.walkFileTree(run.root(), new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                        run.checkActive();
                        if (!directory.equals(run.root()) && Set.of("node_modules", "dist", ".git", ".vite").contains(directory.getFileName().toString()))
                            return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        run.checkActive();
                        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        // 配置、根目录挂载入口及依赖不代表已生成业务功能。
                        if (attrs.isRegularFile() && !name.contains(".config.") && !file.equals(run.root().resolve("index.html"))
                                && name.matches(".*\\.(vue|[cm]?js|ts|jsx|tsx|css|scss|sass|less|html)$")) {
                            found[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                return found[0];
            });
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "无法读取 Vue 项目源码，已停止自动修复");
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("检查 Vue 项目源码失败", error); }
    }
}
