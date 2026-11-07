# 实施计划：个人知识库辅助代码生成 MVP

> 执行方式：在当前会话按 executing-plans 逐项实施，先写行为测试再实现；用户已授权开发、验收文档和远程同步，不重复设置审批点。

目标是完成 [PRD](PRD.md) 的 20 项验收及 [已批准方案](PROPOSED_DESIGN.md) 的闭环。MVP 表示采用最少的基础设施与界面，不删减用户隔离、替换／删除、工具门禁和来源保留。

## 1. 环境与共同约束

- 基线 main：6fcf5d2；当前工作树有其他任务改动，448 个文件快照、哈希及差异存于 `.evidence/private-knowledge-mvp-20261107/`。仅提交本需求必要差异，最终提交树在独立目录验证，禁止把整个工作树直接 git add。
- 技术基线：Java 21.0.8、Spring Boot 3.5.13、LangChain4j 1.15.0、MyBatis-Flex 1.11.0、MySQL 8、Vue 3／TypeScript。使用已有 Spring JDBC 事务能力和现有 Session；新增 Chroma 适配依赖遵循现有 BOM。模型协议以锁定版本源码及官方文档为据。
- 复用核查：cix 默认 local／my-vault 已检索并阅读 PaiSmart ParseService 与 PaiAgent KnowledgeBaseService。借鉴分段与处理状态，不复制实现；不采用先删旧索引或向量失败静默降级做法。本仓优先复用 GenerationExecution、任务服务、输入护轨、临时记忆、文件边界和 API 包装。
- 运行验证使用专用 MySQL 数据目录／knowledge_mvp_test 库、隔离 Chroma collection 与非敏感文档；不修改个人现有资料和业务数据库。默认受控代码生成模型，本地 Ollama 可真实运行；真实外部模型调用须明确记录成本边界。
- 规则来自根 AGENTS.md：Ponytail full、中文注释、先复用、服务端权限校验、不覆盖其他任务。保留已有追踪代码但不把其未完成工作当成本次已完成。

## 2. 文件与接口映射

| 路径 | 职责 |
|---|---|
| knowledge/KnowledgeTypes、KnowledgeText、KnowledgeProperties | 数据值、文本校验分块、可配置上限 |
| knowledge/KnowledgeStore | 私有库、版本、操作幂等、来源记录的数据库事务 |
| knowledge/OllamaKnowledgeEmbedding、ChromaKnowledgeIndex、KnowledgeDocumentService | 向量化、索引隔离与异步处理 |
| knowledge/KnowledgePreparation、KnowledgeRetrievalService、KnowledgeContext | 模型 query 工具、后端门禁、固定资料 |
| controller/KnowledgeController | 登录用户管理文档和查看引用 |
| generation/GenerationTask、Service、Execution、DefaultGenerationTaskRunner；ai 工厂；core Facade；文件工具及修复 | 按次选择、六组合接入、产物校验及修复资料保留 |
| ai-code-frontend/src/api/knowledge.ts、pages/knowledge/KnowledgePage.vue、components/KnowledgeReferences.vue | 文档管理、历史来源 |
| HomePage、AppChatComposer、AppChatPage、router、API 类型 | 开关和一次性参数、幂等重试、管理入口 |
| sql/migrations/20261107_private_knowledge.sql、配置示例、运行说明 | 独立六表迁移与 Chroma／Ollama 启动条件 |

上述 Java 简写路径均位于 `src/main/java/com/xhl/aicodegenerate/`。测试在对应 `src/test/java/.../knowledge/` 与前端 `tests/`。

## 3. 按依赖实施

### T1 私有文档与版本事务（AC-01、03～08、14～17）

- [ ] 写 KnowledgeTextTest 和 KnowledgeStoreTest：格式／字节边界、严格 UTF-8、中文分块完整性；双用户、替换失败旧版、50 份／20 MiB、同请求重放、迟到批次、删除来源隐藏。
- [ ] 在隔离 MySQL 运行并保存失败证据；添加六表幂等迁移及 KnowledgeStore 原子操作。
- [ ] 运行相同测试，通过后记录实际事务与验证范围。
- 接口：`upload(ownerId, name, bytes, requestId, documentId, expectedRevision)` 返回处理操作；`retry(ownerId, documentId, versionId, requestId)` 固定批次；`publish(operation, profile, count)` 仅发布当前批次；`delete(ownerId, documentId)` 立即墓碑；`references(ownerId, appId, taskId)` 检查应用所有者及删除状态。

### T2 入库与真实索引（AC-02、04～08、12～13、18）

- [ ] 写 Ollama／Chroma HTTP 契约测试与异步处理失败回归，观察红灯。
- [ ] 接入显式 truncate=false、按用户 collection 和 buildId 过滤、成功后发布、失败重试与清理。
- [ ] 用本地真实 Ollama、隔离 Chroma 验证中文正负样本、跨用户与版本替换；记录分块、阈值、模型摘要。
- 接口：`embed(text, query)`、`index(collection, operation, chunks)`、`search(collection, publishedBuilds, embedding)`；不允许外部传入 collection。

### T3 工具门禁与生成集成（AC-09～13、18～20）

- [ ] 写 KnowledgePreparationTest 与生成集成：跳过工具、无命中、故障、关闭伪造调用、取消、六组合、修复快照稳定、摘要开关冲突。
- [ ] 统一 runner 准备阶段；使用低层模型请求仅提供 searchKnowledge；最多一次协议纠正。固定上下文进入每次生成／修复请求，保存与发布再次校验。
- [ ] 运行定向受控回归，保证普通生成关闭路径保持可用。
- 接口：`prepare(task, execution)` 返回不可变 `KnowledgeContext`；`assertGenerationAllowed()`；`project(ChatRequest)` 注入固定资料，来源头先落库。

### T4 管理与输入界面（AC-01、04～11、14～18）

- [ ] 先补请求幂等、开关恢复与异步迟到响应测试。
- [ ] 新增文档管理页与引用组件；沿用现有界面，加入两个入口开关及管理导航。
- [ ] 前端测试、类型检查、构建、所改文件 lint；真实浏览器验证上传、替换、删除、引用与页面切换。

### T5 集成验收与交付（全部 AC）

- [ ] 独立代码审查并修复实质问题；运行本次后端回归、前端检查、隔离数据库及真实索引验收。
- [ ] 编写 VERIFICATION.md 和 USER_GUIDE.md：逐项区分已执行证据和用户待执行的真实模型验收；提供启动、迁移、操作和预期结果。
- [ ] 更新 API 说明及进度，说明用户问题→上传→处理→工具检索→生成→核对的真实流程和关键知识点。
- [ ] 构造仅包含本需求的提交，干净检出验证，提交并推送 origin/main，核验远端哈希；保留其他未提交修改。

## 4. 重点边界与验证命令

重点检查：删除后的迟到写入、HTTP 重试再次失败后的重放、索引批次隔离、未确认任务与开关重置、历史资料在普通聊天／日志中的泄露。分别由 T1、T2、T3、T4 负责可观察测试。

后端命令按任务指定 `./mvnw -Dtest=Knowledge*Test,... test`；数据库测试显式传入独立 `knowledge.test.mysql.url`。禁止直接运行包含真实供应商调用的既有全量测试。前端执行现有测试命令、`yarn type-check`、`yarn build`。每项验收的实际结果集中在 VERIFICATION，未运行不得标通过。

## 5. 恢复与同步

新增表保留文档原文、版本及引用；功能停用不删表、不删 Chroma 数据卷。故障任务不静默转普通生成，用户可关闭后新建任务。提交前比对基线，针对共享文件逐块暂存；远端已有新提交先核验并正常整合，不强推。

## 6. 进度与偏差

当前已实现 T1～T4，T5 已完成独立审查和受控验收，发布质量仍为 PARTIAL；逐项证据与未完成项以 [VERIFICATION.md](VERIFICATION.md) 为准。下方原任务清单保留为范围清单，不据此宣称所有验收通过。

状态统一记录到 PROGRESS。本计划 T1～T5 覆盖全部 20 项 AC。普通内部实现收敛可记录理由后推进；变更用户可见行为必须明确记录需求变化，不能以 MVP 为由删除已确认规则。


## 2026-11-08 追加：切换 Ollama BGE-M3

用户已批准安装并替换嵌入模型。此次按有限变更执行：复用现有 Ollama 适配器和模型 profile 校验，更新模型默认值、配置示例与本地配置；不改鉴权、数据库结构或生成工具协议。技术栈仍为现有 Java 21／Spring Boot／LangChain4j，不加依赖。

执行结果：安装并记录模型 digest → 固定校准／保留样本并测量 → 默认配置及前缀协议红绿回归 → 真实 BGE 索引和旧模型边界验证 → 更新操作说明与同步本次差异。15 类 53 项定向测试通过；5 条校准样本全过、保留集 11/12；候选阈值 0.78 仅按校准集选定，未用保留集反复调参。失败项阻止默认启用，不阻止已授权的模型替换。完整证据见 [验收第 9 节](VERIFICATION.md#9-bge-m3-切换与实测2026-11-08)。

已按 cix-workspace 检索 my-vault 的 paicli `EmbeddingClient` 及调用方、测试，未采用外部实现：其旧端点、静默截断与本项目契约不符。按 Ponytail full 复用本仓现有 `/api/embed`、`truncate=false` 和 profile 隔离，无需新适配器或模型框架。
