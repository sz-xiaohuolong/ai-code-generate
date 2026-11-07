# 验证记录

- 日期：2026-10-18。
- 结论：功能已实施，适用自动化回归、两种模式的真实 HTML 生成与本地产物闭环通过；完整新建端到端及三种代码类型的全部真实模型组合尚未验证，整体状态为 `PARTIAL`。
- 验证输入：当前 `generation-modes` 工作区；基线 `680f0c9`。本次仅提交和推送代码，不创建 Tag、Release 或部署线上服务。
- 环境：macOS，Java 21.0.8，Node.js 23.11.0，本地 MySQL/Redis；后端 `8123/api`，本任务前端 `5174`。真实模型沿用本地配置，未变更提供商或密钥。

## 自动化证据

以下命令均已实际执行，最终结果为成功。真实模型和网络边界由可控依赖替代的测试，不能视为外部服务端到端成功。

```bash
./mvnw -Dtest=AppGenerationModeTest,WorkflowBehaviorTest,WorkflowTokenStreamCancellationTest,WorkflowSseControllerSecurityTest,CodeGenWorkflowRoutingTest,CodeQualityCheckNodeTest,WorkflowContextTest,AppControllerSseErrorTest,DatabaseLoadingChatMemoryStoreTest,StreamHandlerExecutorTest test
cd ai-code-frontend
npm test
npm run build
```

| 范围 | 结果 | 覆盖 |
| --- | --- | --- |
| 后端 10 个适用测试类 | 37 项通过，0 失败、0 错误、0 跳过 | 创建缺省与非法值、模式固定与分流、权限、真实图路由、重试、降级、正式记忆、SSE 错误和流适配 |
| `WorkflowBehaviorTest` | 8 项通过 | 真实图与真实 Facade 配合可控模型／构建，验证质检后 Vue 只构建一次、构建失败、重试上限、取消及历史不污染 |
| `WorkflowTokenStreamCancellationTest` | 2 项通过 | 真实 Facade 适配器取消底层 StreamingHandle；首 Token 前取消和迟到完成不构建 |
| 前端原生测试 | 4 项通过 | 偏好归一化、节点重试更新、跳过／失败状态、历史完成标记 |
| 前端生产构建 | 类型检查与 Vite 构建成功 | 当前页面、自动生成 API 和 TypeScript 契约；仍有依赖包体积提示 |

原始证据位于 `.evidence/generation-modes/final-backend.log`、`final-frontend-tests.log`、`final-frontend-build.log`。未运行包含真实 LLM 调用的全部 Maven 测试，避免无关模型调用与重复验证。

## 运行验收矩阵

| AC | 真实运行与自动化证据 | 状态 |
| --- | --- | --- |
| AC-01 默认与新建偏好 | 浏览器确认默认标准、切换工作流后首页说明同步、刷新保持偏好；创建请求已进入后端，因现有路由模型 429 配额限制未创建成功 | 部分通过 |
| AC-02 持久化与兼容 | 本地迁移成功且重复执行显示跳过；列默认 `standard`、非空，全表无空模式。专用标准测试应用省略字段后由数据库默认标准；创建 DTO 缺省／非法值由单测覆盖；真实更新请求携带 `generationMode=standard` 不能改变工作流应用 | 通过（创建成功路径为可控模型单测） |
| AC-03 应用固定与分流 | 工作流应用打开后切换顶部标准，应用仍为工作流；刷新后顶部偏好和应用自身模式各自保持；两种模式均实际生成 HTML | 通过 |
| AC-04 节点与结束状态 | 浏览器看到素材、提示增强、代码生成、质检完成及静态构建跳过；本人演示 SSE 返回真实节点开始／完成、代码块和成功结束。降级、重试耗尽、构建失败由真实图配合可控外部依赖测试验证 | 通过（失败分支为自动化验证） |
| AC-05 代码类型与构建 | 真实工作流后续对话保持 HTML；HTML 明确跳过构建；HTML／多文件路由及 Vue 质检后单次构建、失败由单测覆盖 | 部分通过（未穷举真实多文件／Vue 模型组合） |
| AC-06 历史与鉴权 | 首轮正式历史恰好 2 条，演示接口后续对话后 4 条，均为原始用户输入及最终结果；刷新恢复应用与预览。真实 HTTP 匿名和其他用户应用的聊天／演示 SSE 请求均拒绝；POST 演示鉴权及跳过原因由测试覆盖 | 通过 |
| AC-07 产物闭环 | 两种模式真实 HTML 均可静态 HTTP 预览、下载有效 ZIP，调用本地部署接口成功并产生部署目录；浏览器点击工作流生成的计数器，数值从 0 到 1 | 通过（本地部署目录，未验证线上域名） |

本地验收使用专用非生产账号及两条测试应用。由于创建依赖的路由模型返回 429，两条应用由测试 SQL 建立并固定 HTML 类型；后续生成、质检、历史、预览、下载、部署均使用真实服务与真实模型。没有把测试 SQL 建立应用视作创建 API 成功。

原始运行记录：`database-live.log`、`api-live.log`、`api-auth-live.log`、`standard-live.log`、`demo-live.log`、`demo-live.sse`。`api-live.log` 完成产物检查后，首次鉴权探测因 SSE 错误包装解析方式不匹配退出；修正探测解析后，独立 `api-auth-live.log` 已通过。中间探测也触发过本地请求限流，等待窗口后完成验证，未修改限流配置或清理 Redis。

截图：`.evidence/generation-modes/workflow-success.jpg`（本轮真实进度与产物）、`standard-success.jpg`（标准模式），最终工作台截图见 `workflow-history-preview.jpg`。截图保存在忽略目录，仓库中的本记录保留验证结论和重现入口。

## 审查闭环

独立只读审查覆盖相对 `680f0c9` 的模式、历史、失败状态与鉴权变更，排除用户并行修改的模型配置。发现并修复三项问题，复审通过，无遗留阻断问题：

1. Vue TokenStream 取消未传播到底层：增加 StreamingHandle 取消、工具执行前检查及迟到构建保护。
2. 历史末条仅为用户消息时误恢复预览：要求末条 AI 带服务端明确完成标记。
3. 同步演示接口丢失质检降级：保留真实跳过原因及重试次数。

另将断线后的进行中节点标记为失败。最后通过真实窄屏检查修正应用名称、代码类型和新增模式标签的换行，避免模式被截断；修正后再次完成生产构建并保存最终截图。新增测试曾分别验证缺少取消回调和同步降级信息的失败，再验证修复后通过。

## 迁移与交付边界

存量环境启动新版前执行 `sql/migrations/20261018_generation_mode.sql`，新库使用更新后的 `sql/create_table.sql`。迁移可重复执行，历史应用默认标准模式。回退代码可保留新增列；恢复新版前避免旧代码继续编辑已创建的工作流应用，不建议删除模式数据。

未提交用户并行修改的 `ReasoningStreamingChatModelConfig.java`（模型名称变化），未覆盖该改动。本次真实验证运行环境包含该本地配置变化；HTML 使用的常规模型链路已通过，Vue 真实外部模型没有在本次验证中作成功声明。

功能提交 `c9179c4b4e337a22e9ee0955588b31ee58f23268` 已推送 `origin/main`，通过 `git rev-parse HEAD` 与 `git ls-remote --heads origin main` 核对一致；后续补充交付状态归档与窄屏标签显示修正，最终远程提交以当前 Git 历史为准。验证缺口不因提交或推送成功升级为 `VERIFIED`。
