# P0 可靠性实施计划

> 执行约定：在当前会话按切片原生实现；行为测试先失败后通过，完成后执行一次独立审查。用户已明确授权直接实施，无需再次选择执行方式。

**目标：** 完成 [SPEC.md](SPEC.md) 的 REQ-01～10，并提交推送 origin/main。

**架构：** Redis 原子任务与所有权；独立工作目录和成功版本指针；共享 Vue 构建诊断／有限修复；快照 SSE 与显式停止。

**技术栈：** Java 21、现有 Spring Boot／Redis／Reactor／LangChain4j／Selenium，Vue 3／TypeScript；不引入新供应商。

## 全局约束

- 一个 Phase 两个业务任务；默认总时限 20 分钟、最多两轮构建修复。
- 保护工作区既有未提交修复；提交只包含本 Phase 变更。
- 不修改历史 Release；原始证据沿用 .evidence/p0-reliability/。

## 审查重点

失去租约后旧线程迟到写入或发布；停止和完成竞争；请求提交成功但响应丢失后的重试；刷新页面期间历史与任务重复展示；首次生成失败与已有成功版本失败的差异。分别由 S1／S2／S3 回归覆盖。

## 切片与接口

### S1：任务执行与工作目录

文件：generation/ 下的任务状态、仓储、执行上下文、工作目录服务；AppChatMemoryId、文件工具与 saver；GenerationTaskController。

产出接口：GenerationTaskService.start(appId,message,clientRequestId,user)、get/current/stop/events；GenerationExecution.forTask(taskId)、checkActive、文件操作和进程清理；GenerationWorkspace.prepare/published/publish。

- [x] 写任务并发／断线／停止、Redis 所有权和目录边界失败回归；执行并确认失败原因。
- [x] 实现后台任务、快照和权限；历史只提交一次；接入标准／工作流与演示入口。
- [x] 验证失主不能释放／发布、取消后不能写入、成功前不影响原版本。

### S2：Vue 交付

文件：core/builder/VueProjectBuilder、generation/VueDeliveryService／PreviewVerifier；Facade、CodeGeneratorNode／ProjectBuilderNode。

消费 S1 的截止时间、任务根目录、所有权及进度；产出 BuildResult（阶段／退出码／分类／诊断）及交付成功或明确失败。

- [x] 写真实 Node 进程输出、超时／取消、构建失败和有限修复回归，观察失败。
- [x] 实现结构化构建诊断、进程树清理、依赖指纹、最多两轮修复和预览验证。
- [x] 两种模式共享交付；更新静态服务、下载和部署读取成功版本。

### S3：前端、系统验收与同步

文件：前端任务 API／状态辅助模块／AppChatPage、前端行为测试；文档与现有架构事实源。

消费 S1 快照协议；用户发送生成 UUID 并 POST 创建，EventSource 只订阅；刷新查询 current；主动停止调用 stop；页面销毁只断开订阅。

- [x] 写恢复／状态／成功版本展示测试并先观察失败；实现简要状态与工作流步骤。
- [x] 运行适用后端、前端测试和生产构建；真实 Redis、Node 与浏览器验证关键端到端路径，少量真实模型验证。
- [x] 独立审查并修复阻断问题；按 REQ 记录证据和限制，更新架构／地图／进度。
- [ ] 检查纯净提交范围，提交并推送 origin/main，核对本地和远程哈希。

## 执行记录

- 2026-10-18：复核基线 9035613 与既有未提交修复；前置差异保存于 .evidence/p0-reliability/preexisting.patch。
- 方案选择：沿用 Redis 任务存储，不新增 SQL；原生实现，共享执行上下文通过显式 taskId 传递，禁止依赖线程局部变量。

- 验收补充：真实浏览器发现旧 Vue 预览前缀、保留中断标记导致取消误判、秒级历史时间导致重复展示，均先复现并补回归后修复；停止入口固定于状态栏。
- 兼容收尾：保留成功发布后的异步封面更新，演示页接入稳定请求 ID 和任务快照协议。
- 最终验证与同步结果：[VERIFICATION.md](VERIFICATION.md)。
