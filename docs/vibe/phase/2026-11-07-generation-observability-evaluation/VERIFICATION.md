# 验收记录：生成过程追踪与独立质量评测

后端追踪、工作台查询与独立评测命令已完成本地交付，验证状态为 **VERIFIED（本地受控范围）**。全部 21 项验收具备对应证据；最终两轮各 24 条评测、报告比较、进程中断与导出独立生命周期核验通过，源码／编译产物与报告一致。需求以 [PRD](PRD.md) 为准，执行顺序见 [实施计划](IMPLEMENTATION_PLAN.md)。

## 1. 范围、环境与证据

- 核验日期：2026-11-07～2026-11-08（运行清单使用 UTC，原始日志采用宿主时区）；基线为当前未提交工作树，开工快照位于 `.evidence/generation-observability-20261107/baseline-tree/`。
- 使用 JDK 21、Maven/JUnit、MockMvc、隔离 MySQL 8.0.42、临时 Redis、真实 Vue 编译及 Chrome/WebDriver。模型与外部素材边界使用受控夹具，不代表真实供应商生成质量。
- 原始证据目录：仓库根目录 `.evidence/generation-observability-20261107/`。下文日志名均相对该目录；分步 Surefire 结果已按轮次保存；最终采用 `t7-trace-verified/`、`t8-evaluation-verified/` 及其摘要，避免后续报告覆盖导致误认。
- 验证预算：复用输入和实现未变的有效结果；变更后只补受影响验证。禁止运行默认可能调用真实模型的全量测试。
- 未执行业务数据库迁移、付费模型调用或部署；代码提交与推送已按长期授权完成，功能提交 `e987d7bb5cde748181ee037ffa93f9ee15869732` 已同步 `origin/main` 并核对完整哈希一致。

## 2. 需求证据矩阵

| 验收 | 已核验行为 | 证据与边界 |
|---|---|---|
| AC-01 | 创建请求编号、主备路由与绑定 | AppCreationTraceTest、RoutingFallbackTest；t7-trace-summary；工作台真实 Cookie/CORS 诊断 |
| AC-02 | 两种模式的失败／修复／最终产物及独立父子步骤 | 真实 Vue 修复既有证据；EvaluationCaseRunnerTest 补原生两次尝试模型子步骤；最终两轮 CLI 的 E01～E04 与 t8-completion-audit.json，见 5.4 |
| AC-03 | 幂等、断连与显式停止分别处理 | GenerationTaskServiceTest 的复用、断连、STOP、取消及资源清理；t7-trace-summary |
| AC-04 | 旧实例未闭合记录按证据恢复，不造成功或耗时 | GenerationTraceRecoveryTest、TraceInstanceRegistryTest；MybatisTraceStoreTest 在真实 MySQL 检查 UNKNOWN／空耗时及迟到更新 |
| AC-05 | 所有者权限，管理员不例外 | GenerationTraceServiceTest／ControllerTest 和 TraceWorkbenchBrowserTest 两账号实际 Cookie 请求 |
| AC-06 | 缺分项 Token 保持未知及覆盖率 | UsageTrackingStreamingModelTest、记录器、报告与比较测试；工作台实际 1/2 上报和输出未知 |
| AC-07 | 采集故障不阻断正常生成，不恢复伪完整 | 记录器队列／存储故障、任务成功保护；E11／E12 实际链路注入追踪故障 |
| AC-08 | 超过一天的追踪独立于任务快照查询 | GenerationTraceServiceTest 两天前追踪可查，查询实现不访问 Redis；不延长原任务幂等期 |
| AC-09 | 新增追踪不含秘密、正文和绝对路径 | TraceMetadataTest、模型包装器、创建及工作台注入标记检查；原业务日志不在新增追踪契约内 |
| AC-10 | 七天边界、删除与迟到写入 | 真实 MySQL 幂等迁移／事务／删除竞态，服务层精确过期与 UNRESOLVED，浏览器删除后清空 |
| AC-11 | 导出与在线删除／过期解耦 | 比较命令无数据库或模型依赖；t8-export-lifecycle.log：删除 24 个测试应用并清理在线追踪后，实际 compare 和逐文件摘要校验通过 |
| AC-12 | 24 编号、18/6、固定规格与摘要 | EvaluationDatasetTest；冻结输入、拒绝无效规格／路径；内容改动用 SHA-256 区分 |
| AC-13 | 默认不触达真实模型／外部业务工具 | EvaluationMainTest 注入无效真实端点仍可 fixture；Runtime 显式模型／空素材工具；CLI 不加载业务配置 |
| AC-14 | live 显式预算，真实出站及截止受控 | EvaluationOptionsTest／BudgetTest；EvaluationLiveModelsTest 的本地 HTTP/SSE、无重试／重定向与超时竞争 |
| AC-15 | 独立环境，固定初始输入及输出 | 数据库用途标记／排他锁／目录与 Redis 隔离；修改用例不走新建路由的失败后通过回归 |
| AC-16 | 能打开但无效按钮仍失败 | EvaluationBrowserTest 实际 Chrome 无效按钮、脚本错误及后续断言 SKIPPED；业务字段独立于布局 |
| AC-17 | 环境错误、取消、未执行保留分母 | 浏览器缺失、预算批次、路由阶段取消测试；进程级 SIGINT 最终复验通过，t8-cli-sigint-summary.json，见 5.4 |
| AC-18 | 独立判定不改变正常发布规则 | GenerationTaskServiceTest、GenerationWorkspaceTest、GenerationCompatibilityTest；报告只导出，运行使用独立库／目录 |
| AC-19 | 每次独立编号，不覆盖且可追溯 | Report／Artifacts／Provenance 测试及最终两轮 CLI；不同 runId、固定输入与输出，t8-completion-audit.json |
| AC-20 | 不可比项和上报缺口如实展示 | EvaluationComparisonTest 的规格差异、篡改、SKIPPED 和缺 Token；最终实际 compare 与摘要复核通过，24／22／18 条分别可比；含范围／中位数及有效样本检查，t8-cli-comparison.json |
| AC-21 | 文件故障非完整完成，不造空成功 | 截图失败真实 Chrome；MainTest 输出入口冲突退出 3、运行中报告冲突退出 2，逐用例保留且无 complete.json |

上述 21 项均已在适用的本地受控环境核验。矩阵结合单元测试、真实存储／浏览器、完整 CLI 与最终输入审计；不把受控通过解释为供应商真实生成质量或生产部署通过。

## 3. 分步检查记录

| 检查 | 入口与范围 | 结果与证据 |
|---|---|---|
| MySQL 与恢复 | `MybatisTraceStoreTest`、`TraceInstanceRegistryTest`、`GenerationTraceRecoveryTest`；隔离端口 23316／数据库 generation_trace_test | 分别 8、2、2 项，失败与错误均 0；`t2-recovery-integration.log` |
| 创建／权限 HTTP | `AppCreationTraceTest`、`GenerationTraceControllerTest`、`GenerationTraceServiceTest` | 分别 3、2、5 项，失败与错误均 0；创建、HTTP、查询日志 |
| 记录与清理 | `GenerationTraceRecorderTest`、`GenerationTraceMaintenanceTest`、`TraceMetadataTest` | 分别 6、2、1 项，失败与错误均 0；`t2-cleanup-green.log`、`t3-trace-regression.log` |
| 任务／流式模型／图 | `GenerationTaskServiceTest`、`UsageTrackingStreamingModelTest`、`WorkflowTraceTest` 及既有图回归 | 选定套件通过；`t3-task-green.log`、`t3-trace-regression.log` |
| 真实 Vue 交付 | `./mvnw -q '-Dtest=VueReliabilityIntegrationTest#bothModesRepairRealVueCompilerErrorAndPublishOnlyVerifiedVersion' test` | 2 个模式场景通过；真实 Redis、Vue 构建与浏览器挂载；`t3-real-vue.log` |
| Schema 注解编译 | `./mvnw -q -DskipTests compile` | 退出码 0；`t3-schema-compile.log`，仅证明编译通过 |

真实 Vue 检查中的模型及素材为夹具；Chrome 的 CDP 版本警告未阻断 WebDriver 检查。此检查不覆盖新追踪页面，也不证明真实模型成功率。

## 4. 工作台受控浏览器验收

命令：`./mvnw -q -Dtest=TraceWorkbenchBrowserTest '-Dtrace.test.mysql.url=jdbc:mysql://127.0.0.1:23316/generation_trace_test?useSSL=false&allowPublicKeyRetrieval=true' test`。先在前端目录运行 `yarn build`，8123 和 23317 端口需空闲；测试自己启动并关闭隔离后端与静态前端，数据库仅允许指定测试库。

- 最终运行退出码 0，完整测试 1 项通过；`t4-browser-final.log`、`t4-browser-result.json`。使用真实浏览器、HttpSession Cookie、跨域 HTTP、生产追踪控制器／查询服务／MySQL 与前端构建。应用详情、任务快照、登录和创建错误为专用夹具，未执行供应商模型。
- 22 条历史与 105 条步骤分页，键盘展开原失败和后续成功，输入用量 1/2 覆盖、输出未知，查询故障保留已加载步骤，创建错误与诊断编号同时显示，其他用户和管理员五条查询均拒绝。
- 应用切换期间旧请求晚到不能污染新页面；无记录时加载状态正常结束。运行中第二页步骤会刷新到终态。逻辑删除后重新查询清空详情；关联创建追踪另由 9 项真实 MySQL 回归验证。
- 390×844 视口无追踪面板横向溢出；桌面、窄屏和创建诊断截图为 `t4-workbench-desktop.png`、`t4-workbench-mobile.png`、`t4-creation-diagnostic-mobile.png`。
- `t4-cors-red.log` 记录修复前浏览器无法读取请求编号的失败；显式暴露 X-Request-Id 与 Content-Disposition 后通过，既有跨域来源规则不变。
- 前端 14 项测试、类型检查与生产构建通过；`t4-ui-final.log`、`t4-build-final.log`。新追踪组件、API 和格式工具 ESLint 通过（`t4-lint.log`）；机械设计检查无命中（`t4-design-detector.json`）。

## 5. 独立评测验证

本节前半部分和 5.1 保存开发过程中的分步证据；其中“尚待”描述仅指当时边界。最终结论以 5.4 和第 2 节验收矩阵为准；5.2、5.3 保留报告统计补齐前的有效证据。

- `EvaluationOptionsTest`、`EvaluationBudgetTest`、`EvaluationDatasetTest` 共 8 项通过：默认 fixture、live 显式预算、未知／重复参数、单调时限、并发调用预占、24 个编号及 18／6 分组、夹具与断言摘要、路径与动作白名单。证据 `t5-dataset-green.log`。调用适配已接入，完整截止调度和真实协议请求计数仍待验收。
- `EvaluationIsolationTest` 3 项通过：拒绝业务库及带凭据／危险属性 URL、运行目录独立、公开目录及符号链接拒绝、专属 Redis 的进程归属／数据隔离／幂等关闭。证据 `t5-isolation-isolated-green.log`。
- `EvaluationDatabaseTest` 3 项通过：显式隔离 MySQL 23316 上的随机 `generation_evaluation_*` 库；未标记已有数据不变、空库提取白名单五表初始化、用途／DDL 摘要校验、排他租约释放、结构差异／未知表拒绝。证据 `t5-database-green.log`。测试仅删除自己创建的随机库。
- `EvaluationRuntimeTest` 1 项含两个模式通过：真实 AppServiceImpl 创建、AiServices 路由、GenerationTaskService、标准与真实工作流图、parser/saver、Redis、MySQL 正式历史、Chrome 预览、追踪终态及创建关联。模型／素材受控，封面发布明确关闭，调用共 6 次；证据 `t5-runtime-green.log`。这证明隔离环境复用了真实生成链路，不代表全部固定用例已通过。

- `EvaluationRuntimeTest` 后续扩展为 10 个场景，仍为 1 项测试：两个 HTML 基础模式，加 B03、E01、E03、E04、E05、E07、E09、E11，覆盖真实 Vue 文件工具／编译修复、原生重生成、模型错误、流取消、真实 Node 构建超时、追踪写入故障不阻断生成。`t5-fixture-runtime.log`，37.93 秒通过；不是完整 CLI 或全部业务断言。
- `EvaluationFixtureModelsTest` 2 项与 `EvaluationLiveModelsTest` 4 项通过。后者使用本地 HTTP/SSE 服务器验证四个槽位共用实际出站计数、503 不重试、307 不跟随、批次截止关闭未结束 SSE、配置拒绝；无付费请求。证据 `t5-fixture-model-green.log`、`t5-live-protocol-green.log`。完整批次的停止与资源回收尚待验证。
- `EvaluationBrowserTest` 6 项真实 Chrome 验证通过：6 项初始断言失败后实现；覆盖填入／点击／选择／文本／可见数量／可见性，能加载但按钮无效判 FAIL、后续步骤 SKIPPED，空文本要求节点存在，缺 Chrome 判 ERROR，截图写入故障不抹掉已证实的业务结果，页面脚本错误判加载失败。证据 `t6-browser-{red,green}.log`。浏览器复用现有 Selenium，静态 HTTP 使用 JDK 21 SimpleFileServer，无新依赖。固定测试摘要 `t5-t6-verification-summary.json` 和对应 `*-verified.xml` 保存上述有效结果。

- `EvaluationDatasetTest` 4 项、`EvaluationArtifactsTest` 2 项通过，新增 3 个行为先失败：冻结输入独立于后续编辑、摘要重新核验、只读产物与追踪摘要一致、依赖排除、重复覆盖／符号链接拒绝。证据 `t6-snapshot-{red,green}.log`。浏览器另补文本精确匹配回归，先复现 11 被误认成 1，修复后 7 项通过；联合快照回归 `t6-browser-snapshot-green.log` 共 13 项通过。
- `EvaluationCaseRunnerTest` 1 项覆盖 5 个实际用例（B01、B08、E08、E10、E12）及路由不匹配、两秒批次截止、停止后未执行样本。真实 MySQL／Redis／任务／Chrome、只读快照和业务断言串联通过；取消保留初始成功产物，追踪注入缺口工程 PASS 但排除完整比较，路由不匹配保留原类型且不启动任务。`t5-case-runner-red.log`、`t5-case-runner-green.log`、`t5-case-deadline-check.log`；截止用例确认真实流取消句柄被关闭。结果不是完整批次 CLI 验收。

- `EvaluationReportTest` 4 项先失败后通过：JSON／HTML 分组、完整分母、E11 预期缺口、动态文本转义、结果不可覆盖、证据文件与摘要校验、缺用例拒绝、文件故障不生成完成清单。`t6-report-{red,green}.log`。最新联合验证 `t5-t6-runner-report-regression.log` 共 18 项通过；固定结果 `t5-t6-runner-report-summary.json` 及 5 份 `*-verified.xml`。此处报告测试使用显式单元测试输入；真实完整批次报告和 HTML 展示仍待 CLI 联合验收。

本阶段为避免与同工作区其他开发任务的 Maven 输出及测试编译相互干扰，使用 `.evidence/generation-observability-20261107/evaluation-check-pom.xml`，依赖取当前项目 pom，所有生产源码参与编译，测试仅包含 evaluation 包，输出进入独立 `t5-check-target`。运行入口：

```bash
./mvnw -q -f .evidence/generation-observability-20261107/evaluation-check-pom.xml \
  -Dtest=EvaluationDatabaseTest,EvaluationRuntimeTest \
  '-Devaluation.test.mysql.url=jdbc:mysql://127.0.0.1:23316/generation_evaluation?useSSL=false&allowPublicKeyRetrieval=true' test
```

上述是开发期隔离测试入口；正式 `scripts/evaluate.sh` 已实现独立生产类编译与 Java 命令入口，不运行 Maven 测试。当前官方 Spring Data Redis 文档与 Context7 已核对，实际使用项目既有 Jedis；Flex 初始化顺序以本机 1.11.0 JAR 和真实集成为准，不把 Context7 返回的无 Spring 示例当作独立容器验证。

### 5.1 审查前的完整命令与报告验收

- `EvaluationProvenanceTest` 1 项、`EvaluationMainTest` 2 项、`EvaluationComparisonTest` 4 项通过。最新用量与完整性联合回归 11 项通过；固定摘要 `t6-completeness-summary.json` 与四份 `t6-completeness-*-verified.xml`。取消后缺 Token 不计完整可比；SKIPPED 不计可判断质量；JSON 明确导出分项用量覆盖率和实际生成终态。
- 第一轮原始 CLI 运行 `87effff0-5189-49fc-9d7d-20e0b3ef5093`，证据 `t6-cli-first.log`；当时尚未加入最后的用量派生字段，保留作为历史链路证据。
- 随后的两轮为 `959fa97f-9426-4e1e-a3ce-b93678e7a759`、`c8840157-96df-4327-b50f-4412c6f88e1d`，日志 `t6-cli-final-{a,b}.log`，完整证据位于 `cli-runs/<运行编号>/`。每轮 12 条业务与 12 条工程用例通过、82 次受控调用、退出 0。真实生成终态为 18 成功、2 失败、2 取消、2 超时；工程用例根据预期故障判定，不能把 24 PASS 解释为 24 次生成成功。
- 实际执行 `./scripts/evaluate.sh compare --baseline <首轮目录> --candidate <第二轮目录>`，退出 0，输出 `t6-cli-comparison.json`：24 条质量可并列比较，22 条耗时可比，18 条用量完整可比。两条预期追踪故障排除完整耗时／用量比较，另四条模型失败／取消缺 Token 排除完整用量比较。源码与编译摘要相同，但脏工作树没有可恢复源码快照，报告明确说明不能声称完整复现。
- 真实 Chrome 打开导出的 HTML，验证 24 行、FAIL／全部筛选、断言展开、截图链接及 390 像素视口，页面宽度为 390，无整页横向溢出。证据 `ReportBrowserCheck.java`、`t6-report-browser-check.log`；截图由最后复验更新，最终图片见 5.3 的 `t7-report-{desktop,mobile}.png`。最初检查脚本错误地按不存在的 value 属性选择选项，修正为可见文本后通过；没有修改已导出报告。
- 实际类路径中 Selenium 为 4.31.0，Chrome 为 156.0.8078.80；pom 顶层 selenium-java 的声明版本不等于全部解析组件版本。当前 WebDriver 路径通过，CDP 配套版本警告保留在日志。
- 进程级 SIGINT 已证明报告与全部 24 条分母可以保留，进程退出 130、报告退出码 2，`complete.json.stopReason=CANCELLED`。发现停止发生在创建路由时被误分为 ERROR，已有确定性失败回归 `t7-routing-stop-red.log`，后经修复与最终进程级复验通过，见 5.2、5.3；最初演练脚本错误期待 REQUESTED 的断言失败不作为产品失败。

### 5.2 最终审查与修复回归

最终独立审查一次，覆盖本阶段相对开工快照的差异；四项 Important／P2 均修复，未发现 Critical，无延期 Minor：

| 问题与影响 | 修复 | 失败及通过证据 |
|---|---|---|
| live 超时计时器晚到，中断下一项操作；正文超时仍可能成功 | 请求收尾与中断互斥，按单调时钟判定最终截止 | `t7-live-timer-red.log` → `t7-live-timer-green.log`，5 项协议测试 |
| 库存／任务整行文案被误作业务规格 | 输入明确专用业务字段节点；断言只判断字段，保留精确数字判断 | `t7-semantic-fields-red.log` → `t7-semantic-fields-green.log`，14 项浏览器／数据集／快照测试 |
| 原生重生成调用未归属各自尝试 | 真实模型调用和订阅均进入对应尝试上下文，回调保留显式关联 | `t7-attempt-modify-red-recheck.log` → `t7-attempt-modify-green-final.log` |
| 修改用例误走新建路由，混淆两类能力 | 在隔离库准备固定类型、初始需求／产物；只在新建用例调用路由 | 同上，错误路由也不能改变修改用例受测输入 |

另外两项由运行演练发现并修复：创建路由包装预算异常后正确归为 CANCELLED（`t7-routing-stop-{red,green}.log`）；任务清理等待工作流线程退出，避免根追踪先结束造成偶发 PARTIAL（`t7-workflow-close-{red,green}.log`）。后者用真实线程及锁存器重现收尾未完成，不能只靠重跑碰到通过。

该轮受影响后端回归为 **23 类 89 项通过**，证据 `t7-trace-regression.log`、`t7-trace-summary.json` 及 `t7-trace-verified/`；含真实 MySQL、工作台浏览器和工作流收尾。完整评测套件 **15 类 45 项通过**，证据 `t7-evaluation-final.log`、`t7-evaluation-summary.json` 及 `t7-evaluation-verified/`。前端当前工作树 **16 项及生产构建通过**（`t7-ui-regression.log`、`t7-ui-build.log`）。不运行含真实模型的全量测试。

审查未判断的事项处理：用户旧修复与并行知识库不属于本阶段修改范围；文档由本次收尾核对；真实供应商质量、业务库迁移和高负载缺少对应运行条件，保留为外部验收边界。已发现问题以失败后通过的测试及上述联合回归闭环，不另发第二次审查。

### 5.3 报告统计补齐前的完整运行与审计

审查修复后，以下两轮均执行 `./scripts/evaluate.sh --mode fixture --suite all`，输出至证据目录 `cli-final-runs/`，退出码均为 0：

| 运行 | 运行编号 | 结果 | 原始日志 |
|---|---|---|---|
| A | `a6360c9a-ece9-4286-aa53-87971ae3f42c` | 12 业务 PASS、12 工程 PASS，74 次受控调用 | `t7-cli-a.log` |
| B | `352c9dc7-a021-4e0a-b3fc-30b9967d645a` | 12 业务 PASS、12 工程 PASS，74 次受控调用 | `t7-cli-b.log` |

- 每轮实际生成终态：18 SUCCEEDED、2 FAILED、2 CANCELLED、2 TIMED_OUT。工程 PASS 表示故障符合预期，不表示生成成功。8 条修改用例使用固定初始应用且不经过创建路由，因此较审查前减少 8 次受控调用。E01／E02 各两次原生生成尝试，每次均有自己的模型子步骤；E11／E12 保留预期 PARTIAL。
- 实际 compare 输出 `t7-cli-comparison.json`：24 条质量、22 条耗时、18 条完整用量可比较。比较逐一核验完成清单与证据摘要；记录缺口和取消后缺用量仍按各自规则排除。
- `ExportLifecycleCheck.java` 仅对 A 轮所属的 24 个隔离应用逻辑删除并清理 40 个在线追踪根、295 条步骤，未修改导出目录（`t7-export-lifecycle.log`）。清理后 compare 成功，报告的独立生命周期成立。
- `ReportBrowserCheck.java` 用真实 Chrome 验证最终 HTML 的 24 行、FAIL／全部筛选、断言展开、截图链接和窄屏布局；390px 视口的页面宽度为 390。证据 `t7-report-browser.log`、`t7-report-desktop.png`、`t7-report-mobile.png`。
- 进程级 SIGINT 最终运行 `74992d8a-59b2-4096-9046-0b0da6596faf`，操作系统进程码 130、报告退出码 2，停止原因 CANCELLED；完整保留 24 条：1 PASS、1 CANCELLED、22 SKIPPED、0 ERROR，模型调用 2 次。证据 `t7-cli-sigint.log`、`t7-cli-sigint-summary.json` 与 `cli-final-stop-runs/`；`complete.json` 表示报告完整保存，不代表全部用例执行完成。
- `CompletionAudit.java` 只读核对当前源码、实际编译产物、运行环境、数据集、断言与 Prompt 摘要，均与最终 A／B 两轮一致；重新核验两份完整报告及中断报告的逐文件摘要，并检查固定修改输入、尝试父子关系、预期追踪缺口和比较资格。结果 `t7-completion-audit.json`、`t7-completion-audit.log` 为 PASS。
- 源码摘要为 `e4395946cff8da421f1c8676098eaeebd5d8cb8474068df2548ed6c573494fad`，CLI 编译产物摘要为 `9aad8877b6f3ec2d19e07cc1efc498145613ae8d3a7bc1dee166922a04e69e02`。工作树含未提交改动且未保存完整可恢复源码快照，报告已明确该限制；摘要一致不能替代完整复现材料。

### 5.4 交付版本：报告统计补齐与最终审计

逐条对照设计时发现报告缺少范围／中位数。已补充按业务／工程分组的耗时、调用数、输入／输出 Token 统计，JSON 与 HTML 同时提供有效样本数、最小值、中位数和最大值。耗时排除追踪或证据缺口，用量仅采用完整可比样本；空集合为未知，不按零参与计算。统计只描述固定样本，不宣称跨场景性能提升。

- 新增 `EvaluationReportTest.distributionsKeepUnknownSamplesOutAndReportOddEvenMediansByGroup`，先因统计缺失失败（`t7-statistics-red.log`），补齐后报告／比较共 9 项通过（`t7-statistics-green.log`）。完整评测套件随后 **15 类 46 项通过、0 失败／错误／跳过**，`t8-evaluation-final.log`、`t8-evaluation-summary.json`、`t8-evaluation-verified/`。复用实现未变的追踪／任务 89 项及前端 16 项、生产构建证据；合计 135 项后端。
- 新源码下重新执行完整 24 条 CLI：A `07b45321-83c0-4021-9fcc-b960ea15be09`、B `7ea96860-772e-48eb-8737-60a9c652b28d`，目录为 `cli-delivery-runs/<运行编号>/`。两轮均退出 0，各 12 业务 PASS、12 工程 PASS、74 次受控调用；生成终态仍为 18 成功、2 失败、2 取消、2 超时。原始日志 `t8-cli-a.log`、`t8-cli-b.log`。
- 两轮实际 compare 通过（`t8-cli-comparison.json`），24 条质量、22 条耗时、18 条完整用量可比。统计中的业务耗时／用量各 12 个样本，工程耗时 10、完整用量 6；两条追踪故障和四条无完整用量的失败／取消样本未混入完整统计。
- `ExportLifecycleCheckDelivery.java` 仅删除 A 轮的 24 个隔离应用及 40 个在线追踪根、295 条步骤；导出文件未变（`t8-export-lifecycle.log`），再次只读比较成功（`t8-after-delete-comparison.json`）。
- 最终真实 Chrome 验证统计表、24 条用例筛选、断言展开、截图链接及 390px 无整页横向溢出，`t8-report-browser.log`、`t8-report-desktop.png`、`t8-report-mobile.png`。首次复验脚本将相对链接直接作为导航地址而失败，改为真实点击链接后通过；错误保存在 `t8-report-browser-harness-error.log`，未改写报告或业务代码。
- 最终进程中断运行 `1548f4cc-7b7e-4951-b93e-c23f5b1d6823`：系统进程码 130、报告码 2、CANCELLED；24 条保留为 1 PASS、1 CANCELLED、22 SKIPPED，0 ERROR、2 次模型调用。证据 `t8-cli-sigint.log`、`t8-cli-sigint-summary.json` 和 `cli-delivery-stop-runs/`。
- `CompletionAuditDelivery.java` 重验当前源码、编译、环境、数据集、断言、Prompt 与两轮运行一致，并逐文件校验两份完整报告及中断报告。固定修改输入、两次尝试各自模型子步骤、预期追踪缺口、比较资格与统计样本数均通过（`t8-completion-audit.json`／`.log`）。最终源码摘要 `b29671b365460fb831c7461adf18607445919b2cb5f88060d0312681248fbbe2`；编译产物摘要 `8a02f17ffb7aaf1de6876d0eab003a7394c960545267abe6bfde725d47fd44b3`。
- 本任务的隔离 MySQL（23316 端口）已正常停止，数据目录与证据保留；未停止用户的 Redis、Vite 或其他服务。记录见 `t8-resource-cleanup.json`。
- 最后静态审计 `t8-evidence-audit.json` 核对全部 21 个 AC 的映射、保存的 XML 结果、前端源码早于最终测试／构建及本地文档链接；`git diff --check` 通过。工作树仍包含未提交修改，完整可恢复源码快照的限制继续保留。

## 6. 维护者可以照做的人工验收

以下是维护者操作说明。上文已执行的是隔离环境与受控模型验证；本节业务库迁移、个人账号真实生成和付费供应商调用尚未代为执行。

### 6.1 启动条件与工作台验收

从仓库根目录执行。需要 Java 21、MySQL、Redis、Node/npm、Yarn、Chrome；本次实际验证的 Node 为 23.11.0。保留已有 `application-local.yml`，核对其中数据库、Redis、模型配置。已有库先备份；以下仅向选定业务库新增两张表，`<业务库名>` 应与本地配置一致：

```bash
mysql -h 127.0.0.1 -u <数据库用户> -p <业务库名> < sql/migrations/20261107_generation_trace.sql
./mvnw spring-boot:run
```

预期：新增 `generation_trace`、`generation_trace_step`，原业务表不改写；后端监听 `8123`、路径前缀 `/api`。另开终端：

```bash
cd ai-code-frontend
yarn install
yarn dev
```

预期：浏览器访问 Vite 打印的地址，默认 `http://localhost:5173`，登录并进入应用工作台。

| 步骤 | 具体操作 | 预期结果 |
|---|---|---|
| 1. 创建并生成 | 用账号 A 创建一个带计数按钮的 HTML 应用，分别选择标准和工作流模式，各生成一次 | 正常生成和预览继续可用；过程入口可以查看本次任务；创建路由与生成分别有记录并关联同一应用 |
| 2. 读执行过程 | 展开模型、工具和交付步骤，查看结束状态与用量 | 显示真实顺序、耗时、模型／Prompt 版本与结束原因；缺 Token 显示未知或部分上报，不填零 |
| 3. 看历史并重连 | 再提一次修改要求；生成期间刷新页面，再打开历史 | 每次生成有独立记录；刷新或重新查询不会重复启动生成；旧记录仍可查看 |
| 4. 主动停止 | 在一次尚未结束的生成中点击停止 | 任务进入取消终态，过程保留停止信息；已有成功预览按原规则保留 |
| 5. 检查权限 | 复制 A 的应用／追踪编号，使用另一个浏览器登录账号 B，再按 [API 说明](../../API.md) 查询 | B 不能读 A 的追踪，管理员也没有例外；工作台不显示不属于自己的过程 |
| 6. 创建失败诊断 | 在独立测试环境把路由和回退模型都指向不可用端点，重启后提交一次创建，再恢复配置 | 页面保留创建错误和请求编号；可读诊断不暴露密钥、完整提示词或模型正文；关联无法确认时明确不可用 |
| 7. 删除与保留期 | 删除本次专门创建的测试应用，再请求其追踪；另保留一个应用观察跨日查询 | 已删除应用追踪立即不可查询；Redis 任务快照超过 24 小时后，未满 7 天的追踪仍可查；满 7 天不可查 |

七天、24 小时与迟到写入竞态已通过可控时钟／真实存储测试，不要求等待七天才能验收开发结果。若需自行复验，使用第 3 节的隔离测试入口；不要改系统时钟或直接修改真实业务记录。完整提示词／正文可能仍存在原聊天记录中，本需求只限制新增追踪采集。

### 6.2 受控评测、报告与比较

CLI 不需要启动网站后端或前端。需要 Java 21、Git、Node/npm、`redis-server`、Chrome 与专用 MySQL 空库；Vue 构建的 npm 安装可能访问依赖仓库。维护者先新建专用库，不能指向业务库：

```sql
CREATE DATABASE generation_evaluation_manual CHARACTER SET utf8mb4;
```

向维护者使用的数据库账号授予该专用库的建表与读写权限。配置只供当前命令使用；首次启动自动建立五张业务／追踪表及用途标记，后续检查用途、Schema 和排他锁，不清空旧数据：

```bash
export EVAL_JDBC_URL='jdbc:mysql://127.0.0.1:3306/generation_evaluation_manual?useSSL=false&allowPublicKeyRetrieval=true'
export EVAL_JDBC_USER='<专用库用户>'
read -rs 'EVAL_JDBC_PASSWORD?专用库密码：'
export EVAL_JDBC_PASSWORD
./scripts/evaluate.sh --mode fixture --suite all --output .evidence/evaluation
```

上述密码输入适用于 macOS 默认 zsh；空密码也必须显式设置。示例 TLS 参数仅用于本机隔离库，远程库按其证书要求配置。

1. 等命令结束，记录打印的运行目录。预期 24 条均有结果；正常受控环境下 12 条业务、12 条工程 PASS，退出 0。fixture 只替换模型协议和外部素材，真实创建、文件工具、构建与浏览器仍执行。
2. 打开运行目录里的 `report.html`。预期可筛选、展开断言、打开截图与 `result.json`；报告分别显示实际生成终态、业务判定、追踪／证据缺口及 Token 上报覆盖；汇总表展示各指标有效样本数、范围和中位数，缺失不按零混入。E05～E10 预期出现生成失败、取消或超时，不能据此认定评测命令错误。
3. 原样再运行一次。预期生成新的运行编号，第一轮文件不覆盖。把两个打印出的目录填入以下命令：

```bash
./scripts/evaluate.sh compare --baseline '<第一次运行目录>' --candidate '<第二次运行目录>' > .evidence/evaluation-comparison.json
```

预期不启动模型或连接评测数据库；输出每条用例的质量、耗时、用量可比性和差异原因。比较前校验证据摘要；证据缺失或被修改时退出 3，不生成伪造的可比结论。

4. 单独运行小预算验收：`./scripts/evaluate.sh --mode fixture --max-model-calls 1 --deadline-seconds 30 --output .evidence/evaluation`。预期退出 2；仍保留全部 24 条入选用例，未执行项标 SKIPPED，不缩小分母。
5. 导出目录由维护者保管；复制整个运行目录后，即使数据库记录被清理，报告仍可独立读取。自行删除不再需要的导出文件，正常应用删除不会替维护者删除本地报告。

退出码：`0` 全部符合预期；`1` 报告完整但存在质量失败；`2` 停止、预算、环境或证据未完整完成；`3` 启动参数／环境校验失败。终端主动中断时，操作系统进程码通常为 `130`，已保存报告内部仍用 `2` 表示未完整完成。

### 6.3 可选真实模型验收（本次未调用）

在上述专用环境配置 `EVAL_MODEL_BASE_URL`、`EVAL_MODEL_API_KEY`、`EVAL_MODEL_NAME`；按需配置路由与推理模型名。不要把密钥写进报告、命令历史或版本控制。通过终端安全输入密钥后显式启动：

```bash
./scripts/evaluate.sh --mode live --suite development --max-model-calls 50 --deadline-seconds 600 --output .evidence/evaluation
```

50 次和 600 秒仅为操作示例，由维护者按额度调整。预期仅选择适用于 live 的业务用例，工程故障用例明确排除；真实质量由实际断言决定，不保证 PASS。需要留出集时显式选择 `--suite holdout`。本次已通过本地 HTTP/SSE 协议测试验证预算、重试关闭和截止取消，未验证任何付费供应商的真实生成质量。

## 7. 当前结论

- 全部 AC 覆盖：是，REQ-01～06／AC-01～21；验证状态：`VERIFIED`，限定本地受控交付范围。
- Workflow State：`RELEASED（代码同步）`；本地实现、审查问题修复、验证与人工验收说明均完成。Shipping Authorization：`APPROVED（代码同步）`；代码已推送并核对远程哈希，部署不在本次范围。
- 外部边界：未执行业务库迁移、真实供应商质量评测及高负载测试；这些结果不能由受控回归推断。当前架构与 API 已同步，历史阶段结论保持原含义。
- 现有 API 调用说明见 [API.md](../../API.md)；进度统一维护于 [PROGRESS.md](../../PROGRESS.md)。

## 8. 远程同步纠正

此前把提交／推送记为未授权是执行遗漏，现已在根 AGENTS.md 固化长期授权，并纠正计划与进度。功能提交 `e987d7bb5cde748181ee037ffa93f9ee15869732` 已同步至 `main → origin/main`，远程完整哈希与本地一致；包括本阶段实现、依赖的原生完整性校验／路由回退／Vue 修复、相关测试和文档归档。模型名、DevTools 本地配置与其他任务文件不纳入提交。

既有完整受控验收针对当时工作树；提交树排除了上述本地配置，因此原始源码摘要不等于提交树摘要。同步时另核对暂存树并做必要编译和针对性验证，不将历史完整实验冒称为提交树上的重新运行。

同步前已从暂存区导出独立目录，后端完整源码与测试编译通过，路由回退、原生解析／保存、重生成、Vue 修复、评测报告／比较共 9 类 53 项针对性回归通过（失败、错误、跳过均为 0）；未调用真实模型。日志与清单：`sync-staged-tests.log`、`sync-staged-validation.json`。暂存差异空白检查通过，未发现私钥或常见 Token 模式。前端源码未变化，沿用前述 16 项测试与构建证据。

上述同步结果与状态说明作为后续文档提交保存；工作区中其他任务文件、本地配置和实验原始证据继续保留在本地。

## 9. 本地验收缺表故障修复（2026-11-08）

用户反馈正常生成完成后，历史列表和本次过程均显示“追踪暂不可用”。在用户当前浏览器复现，两个请求 HTTP 200、业务码均为 50001；只读检查确认业务应用表存在，而两张追踪表都返回 SQLState 42S02、错误码 1146。根因为当前本地业务库尚未执行已有追踪迁移；原控制器把缺表与临时故障合并，页面误导用户反复重试。

- 修复环境：执行已有 `sql/migrations/20261107_generation_trace.sql`，仅新增 `generation_trace`、`generation_trace_step`。执行前后原应用表均为 79 行，未变更原业务表。不是自动启动迁移，其他部署环境仍需由维护者执行迁移。
- 修复诊断：缺表返回专用业务码 50002，页面明确提示追踪存储未初始化、联系维护者处理；其他数据库故障仍为 50001，异常原文不外泄。404 提示包含采集失败的可能，避免误认为只能等待。
- 回归：先见后端缺表诊断断言与前端提示断言失败，再修复转绿。后端查询权限／记录器／错误处理／初始化恢复共 4 类 16 项通过，前端 17 项及生产构建通过。新增真实 MySQL 隔离测试覆盖已有应用库缺表、两次幂等迁移、同一记录器恢复后创建关联／生成记录／步骤可查、原应用不变及丢失历史不补造。
- 浏览器复验：原应用历史列表返回 `code=0`、空列表，本次过程返回 40400 并显示记录缺失；两个区域均不再显示泛化的“追踪暂不可用”。该任务发生于缺表期间，无法恢复执行步骤；新采集恢复由上述真实存储回归证明，未重新调用用户的付费生成模型。
- 证据：`.evidence/trace-unavailable-20261108/` 下 `backend-red.log`、`backend-green.log`、`storage-recovery.log`、`frontend-tests.log`、`frontend-build.log`、`local-migration.log`、`verification.json`。代码同步按根 AGENTS.md 的长期授权执行，实际提交以 Git 历史为准。
