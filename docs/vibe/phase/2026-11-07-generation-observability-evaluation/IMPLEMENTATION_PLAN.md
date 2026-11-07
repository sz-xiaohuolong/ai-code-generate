# 实施计划 — 生成过程追踪与独立质量评测

交付工作台中的创建／生成过程查询，以及可独立运行的固定用例评测命令。先实现可验证的追踪记录和所有者查询闭环，再接入真实生成链路与工作台，最后完成隔离评测、报告和端到端验收。使用 writing-plans 编排计划、executing-plans 在当前会话逐项执行、test-driven-development 验证行为；项目路径、既有授权及本计划的交付边界优先于技能默认目录与发布步骤。

| 计划信息 | 内容 |
|---|---|
| 需求依据 | [PRD](PRD.md)，REQ-01～06、AC-01～21 |
| 方案依据 | [已批准方案](PROPOSED_DESIGN.md)，Q11／D1 |
| 授权范围 | 用户于 2026-11-07 要求先计划后编码，并完成验证及人工验收说明；本地实现与隔离验证；完成后依长期授权提交并推送至当前 upstream，不部署、不调用付费模型 |
| 实际状态与证据 | [PROGRESS](../../PROGRESS.md)；[本阶段验收记录](VERIFICATION.md)；原始证据放 `.evidence/generation-observability-20261107/` |
| 代码基线 | HEAD 6fcf5d2 加用户已有未提交修改；开工前 330 个 Git 可见文件已保存到证据目录 baseline-tree，禁止整文件回退覆盖既有工作 |

## 1. 环境准备与前置依赖

### 1.1 技术基线与执行约束

- 已读取根 AGENTS.md、CLAUDE.md、文档地图、PRD 与方案；执行 Ponytail full，复用现有任务、模型、工作区、浏览器和前端组件。新增抽象仅用于真实存储／受控替换边界。
- Java 21（本机 Corretto 21.0.8）、Spring Boot 3.5.13、MyBatis-Flex 1.11.0、LangChain4j 1.15.0／starter beta25、LangGraph4j 1.6.0-rc2、selenium-java 声明 4.28.1，依据 pom.xml；实际解析与运行组件为 4.31.0，依据最终运行清单及依赖树。前端 Vue 3.5、TypeScript 5.8、Ant Design Vue 4.2、Vite 7，依据 package.json／锁文件；本机 Node 23.11.0。
- OpenTelemetry 使用已批准的 Boot BOM 管理版本 1.49.0，新增 API／SDK 直接依赖后验证解析树；不会为追求最新文档直接升级整套运行时。
- 2026-11-07 通过 Context7 `/open-telemetry/opentelemetry-java/v1.49.0` 查询 Span／Context／SDK，通过 `/langchain4j/langchain4j` 查询流式模型接口；同时联网检索并打开 [OTel 当前文档](https://opentelemetry.io/docs/languages/java/api/) 与 [LangChain4j 当前观测文档](https://docs.langchain4j.dev/tutorials/observability/)。当前文档涉及更新版本及实验性 AI Service 事件，采用显式上下文和现有模型包装入口，API 以锁定版本源码、依赖解析与编译结果为准。前端实现时已通过 Context7 `/websites/vuejs`、`/websites/selenium_dev` 核验异步清理、显式等待与截图；联网核对 Vue watchers 和 Spring 6.2 CORS 官方文档，使用兼容现有版本的 API。
- cix-workspace 已确认默认 local 服务器及 my-vault 的三个索引；本仓库索引较旧，现状以本地源码为准。参考 PaiFlow 的 trace/span 显式上下文、chat_runner 调用和传递测试，仅借鉴传递方式；不复制 Python 代码、整段序列化或外部 OTLP 导出。评测检索未发现可直接复用的同栈闭环，采用本仓库真实生成及浏览器入口。
- 两张表及所有者边界已获 Q11 批准；沿用 Session 和业务错误码。自动迁移框架不在范围内；SQL 幂等，先在专用测试库验证，不操作真实用户数据。
- 追踪保留 7×24 小时；Redis 24 小时快照、生成成功判定和发布规则保持。日常仅白名单元数据；评测证据为专门编写的非敏感内容。异常消息／完整请求对象不得直接进入追踪。
- 真实评测必须显式 live、调用数上限、总时限和专用环境；默认 fixture 不接触真实模型或外部业务工具。Java 命令独立于全量 Maven 测试。

### 1.2 验证命令与环境

| 用途 | 命令／入口 | 预期判据 |
|---|---|---|
| 依赖 | `./mvnw dependency:tree -Dincludes=io.opentelemetry:*` | API、context、SDK 解析一致，无额外外部导出依赖 |
| 局部后端 | `./mvnw -Dtest=具体测试类 test` | 对应行为失败后通过；仅运行明确受控的测试 |
| 存储与任务集成 | 隔离 MySQL、独立 Redis 测试实例、临时任务目录 | 幂等迁移、事务、权限、删除竞态及真实任务终态通过 |
| 前端 | 在 ai-code-frontend 执行 `yarn test`、`yarn build` | 相关行为、类型和生产构建通过 |
| 默认评测 | `./scripts/evaluate.sh --mode fixture --suite all --output .evidence/evaluation` | 24 条全部有明确结果、分组和证据；不启动真实模型 |
| 对比 | `./scripts/evaluate.sh compare --baseline <目录A> --candidate <目录B>` | 不发起生成；版本差异与不完整样本明确标出 |
| 人工 UI | 启动本地后端、前端，两个隔离账号 | 所有者看记录、他人拒绝、创建失败编号、空／加载／错误状态可见 |

以上为计划命令；具体执行结果进入验收记录。全量 Maven 测试含真实模型，未经显式授权不执行。

## 2. 文件级变更映射

后端包前缀均为 `src/main/java/com/xhl/aicodegenerate/`，测试位于对应 `src/test/java/`。

| 操作 | 文件或目录 | 职责／关联任务 |
|---|---|---|
| 修改 | pom.xml | 添加 Boot 管理的 OTel API／SDK，配置独立命令构建；T1、T5 |
| 新增 | observability/GenerationTraceRecorder.java、TraceSession.java、TraceStore.java、TraceMetadata.java | 上下文、步骤生命周期、只读快照、白名单、非阻断队列；T1 |
| 新增 | entity/GenerationTrace.java、entity/GenerationTraceStep.java、mapper/GenerationTraceMapper.java、mapper/GenerationTraceStepMapper.java | 按已批准表定义映射及精确查询；T2 |
| 新增 | observability/MybatisTraceStore.java、service/GenerationTraceService.java、controller/GenerationTraceController.java | 事务写入、七天查询、所有权、清理／恢复、分页；T2 |
| 新增／修改 | sql/migrations/20261107_generation_trace.sql、sql/create_table.sql、application.yml／示例配置 | 两表与有界配置，不回填旧历史；T2 |
| 修改 | service/impl/AppServiceImpl.java、controller/AppController.java、ai/AiCodeGeneratorServiceFactory.java | 请求编号、同步路由模型、保存前失败／关联未知／已绑定区分、删除后清理；T3 |
| 修改 | generation/GenerationTaskService.java、GenerationExecution.java、GenerationTask.java、UsageTrackingStreamingModel.java | claim 后建追踪、跨线程关联、一次性模型终结、停止与最终提交记录；T3 |
| 修改 | ai/tools/FileToolSupport.java、各文件工具、langgraph4j/CodeGenWorkflow.java、generation/TaskChatServices.java、VueDeliveryService.java、DefaultGenerationTaskRunner.java | 实际工具名与相对路径、阶段父子关系、重入、修复／构建／预览埋点；T3 |
| 新增／修改 | 前端 src/components/GenerationTracePanel.vue、src/api/generationTrace.ts、src/pages/app/AppChatPage.vue、src/pages/HomePage.vue、相关类型／测试 | 所有者查询、分页、步骤、缺口、创建失败编号；T4 |
| 新增 | evaluation/ 下 Java 命令、数据集、运行预算、受控模型、生成环境适配、浏览器断言、报告组件 | 独立命令和可替换边界；T5、T6 |
| 新增 | 仓库 evaluation/ 下 suite.json、assertions、fixtures；scripts/evaluate.sh | 24 条非敏感样本、固定初始代码与维护者断言、Java 薄启动脚本；T5 |
| 新增／修改 | observability／evaluation 测试及现有任务／路由／工具测试、前端 tests | AC 覆盖与既有行为回归；T1～T7 |
| 修改／新增 | 当前阶段计划、验收、公共 PROGRESS／DOCUMENT_MAP／架构说明／README | 新事实、运行命令、人工验收及限制；T7 |

读取实际调用方后按职责确定精确新文件名；不另建并行权威文档。查询不依赖 Redis 历史；评测不修改生产数据库或发布目录。

## 3. 任务、接口与依赖

### 3.1 执行顺序

| 任务 | 可验证交付 | 前置依赖 | 关联验收 |
|---|---|---|---|
| T1 | 非阻断记录器与元数据安全 | 本计划、技术基线 | AC-02、04、06、07、09 |
| T2 | MySQL 事务存储、所有者查询、过期／删除 | T1 | AC-05、07～10 |
| T3 | 创建到生成真实调用链埋点 | T1、T2 | AC-01～04、06、07、09 |
| T4 | 工作台及创建失败诊断 | T2、T3 | AC-01、05～08、10 |
| T5 | 隔离 Java 命令、数据集与预算 | T3 | AC-12～15、17～19 |
| T6 | 业务断言、证据与报告比较 | T5 | AC-11、16、17、19～21 |
| T7 | 集成复审、运行验收与说明 | T1～T6 | AC-01～21 |

计划启动时 T1 就绪。逐项执行，接口／设计偏差写入 PROGRESS；状态只在进度文档维护。已有实施授权覆盖计划后的编码，不新增逐任务确认。

### 3.2 T1：记录器

- 消费接口 `TraceStore.persist(GenerationTrace root, GenerationTraceStep step)`；生产 MyBatis 与受控内存存储共享契约。`GenerationTraceRecorder.startGeneration(taskId, appId, userId, mode, type)`／`startCreation(requestId, userId, mode)` 返回独立 `TraceSession`。`TraceSession.startStep(kind, name, parent)` 返回一次调用句柄，调用完成、异常、取消都结束同一条。
- 先写失败测试：相同阶段重入保留两条；不同线程显式父关系；模型完成／异常重复回调只计一次；缺分项 Token 不填零；存储异常和队列溢出不向调用方抛出、不标 COMPLETE；非法路径／原始错误／秘密标记不能入库。
- 采用独立 OTel provider，显式 parent Context；有界 FIFO 记录事件，根先于步骤，终态最后确认。仅在全部已知步骤闭合且事件写入确认齐全时 COMPLETE；终态后允许同调用补用量，保持执行结果和已有 PARTIAL。
- 验证：`GenerationTraceRecorderTest`／`TraceMetadataTest`，受控时钟与故障存储；恢复关闭仅结束队列与 OTel，不改变业务任务。

### 3.3 T2：存储与只读查询

- 新表字段／索引以方案 4.1 为准；根和步骤有版本条件更新。事务先锁有效应用，再操作根／步骤，删除后晚到事件不可重建。应用成功删除后触发清理，定时补偿七天过期与已删除应用。
- 查询服务返回根／步骤分页 DTO；应用列表默认／最大 20，步骤默认／最大 100。by-task 不查 Redis；by-request 的 UNRESOLVED 只返回记录不可用，NO_APP 才允许无 appId 诊断。BIGINT 对外为字符串。
- 先写 `GenerationTraceServiceTest` 覆盖两用户含管理员、过期、24 小时后查询、关联丢失；`MybatisTraceStoreTest` 在独立 MySQL 验证迁移重复执行、写入顺序／回滚／删除并发。
- 未闭合根根据已知旧实例及任务证据收敛；无法证明终态时 PARTIAL／UNKNOWN，不侵入其他活跃实例，不重放生成。
- 恢复：禁用新记录／查询／清理可回退，保留表与数据；不执行 DROP 或改写旧业务表。

### 3.4 T3：真实调用链接入

- 创建响应增加服务端 X-Request-Id；同步路由包装器逐次记录主备模型实际用量。只在确认未尝试保存前失败时 NO_APP，保存结果不明时 UNRESOLVED，成功后原子绑定。
- task claim 成功后建立追踪并放入 GenerationExecution；幂等重试读取已有关联。图节点、模型回调、素材 fork、工具按真实执行显式携带上下文；标准和工作流同用底层计数。模板摘要只包含版本化模板，配置摘要只包含白名单非秘密参数。
- 停止记录 STOP；根终态在资源清理与 Redis 最终提交后记录。追踪异常不能覆盖原错误、改变发布指针或阻断正常生成。
- 先补 `RoutingFallbackTest`、`UsageTrackingStreamingModelTest`、`GenerationTaskServiceTest` 与新追踪集成测试；交错回调、调用故障、取消／超时、两模式各类型、重试及独立草稿均可观察。
- 恢复依据：开工基线和逐文件差异，仅撤回本阶段接入，不覆盖原有修复。

### 3.5 T4：用户查询

- 使用 impeccable 的局部修改流程，保留工作台风格；通过 `isOwnApp` 展示入口，服务端仍独立鉴权。生成记录与本次过程使用同一组件，分页读取列表／步骤，保留已加载数据并标注缺口。
- 模型用量展示已知值与分项上报次数；未上报显示未知。创建失败保留原错误和编号，另可打开诊断；诊断失败不替换创建错误。
- 先验证前端结果格式化与状态处理，再实现组件；`yarn test`、`yarn build`；真实浏览器验收桌面／窄屏、键盘、空／加载／错误／运行中／终态、两账号权限。

### 3.6 T5：独立评测入口

- `EvaluationMain` 解析参数并返回 0／1／2／3；fixture 是缺省，live 必须显式配置上限／时限；compare 不启动生成环境。
- 使用独立配置／数据库／Redis／任务根及隔离应用账号，启动前确认边界。复用 GenerationTaskService、DefaultGenerationTaskRunner、创建路由和真实代码生成流程；仅模型和外部素材工具替换为确定性夹具，不用固定成功结果替代生成链路。
- 24 条用例按方案 B01～12、E01～12 分配，18 开发／6 留出；修改从固定初始产物开始，新建记录真实路由且类型不符判失败。fixture 覆盖完整 24 条，live 只选业务用例并列明排除原因。
- 调用预算在实际模型请求前预占；live 适配关闭 SDK 隐式重试并验证请求计数；批次截止清理资源、写已有证据，不再发出模型请求。
- 先写 `EvaluationOptionsTest`、`EvaluationDatasetTest`、`EvaluationBudgetTest` 与隔离测试；缺预算、零用例、路径越界、重复 ID、生产目录／配置误用必须在请求前拒绝。

实现取舍：live 使用 JDK 21 HTTP 客户端适配项目锁定的 LangChain4j HTTP SPI 与 SSE parser，预算在真实出站前预占；禁用 SDK 重试和重定向，不新增依赖或全局替换业务客户端。Context7 与官方可定制 HTTP 文档已核查；当前文档的新版本 API 不直接用于锁定依赖，编译与本地协议测试确认兼容。

### 3.7 T6：分层判断和证据

- 任务完成后冻结产物快照并做摘要；Selenium 对快照执行维护者白名单点击／输入／提交／筛选／断言。静态页面构建 NOT_APPLICABLE；Vue 用真实构建结果；按钮无效必须 FAIL，浏览器不可用必须 ERROR。
- 输出 run.json、逐样本 result.json、必要截图／诊断、report.json／report.html，最后写完成清单。报告原子替换、不覆盖旧 runId，HTML 转义所有动态内容、证据只用受控相对路径。
- 清单固定数据集／验收器／模板／安全模型配置／环境／受测源码摘要。脏工作区需可恢复源码快照或明确不能完整复现，不仅记录 HEAD。
- compare 核验版本与初始产物，缺追踪排除完整用量／耗时比较；列出全部样本分母与排除原因，不给无依据提升率。工程预期取消／故障与业务质量分组。
- 报告按业务／工程分组给出耗时、调用数和分项 Token 的样本数、范围与中位数；耗时排除追踪／证据不完整和未判定样本，用量仅采用完整上报。空集合为未知，不能填零或暗示跨场景性能提升。
- 先写 `EvaluationReportTest`、`EvaluationComparisonTest`、`EvaluationBrowserTest`；失效按钮、缺浏览器、文件故障、恶意 HTML、版本不一致、重复运行均验证。执行两次默认受控完整批次并比较。

实现口径：浏览器使用现有 Selenium 依赖和 JDK 21 静态 HTTP 服务；Context7 Selenium 显式等待／截图文档已核查，API 由锁定类路径编译及真实 Chrome 验证。`text` 对去掉首尾空白后的文本精确匹配，不能把 11 认作 1；`count` 仅计可见条目，空文本也要求节点存在。输入冻结后复核数据集／断言摘要；产物快照复用 TraceArtifacts 摘要，排除 node_modules／.git，并设为只读。单用例终态与追踪终态分别等待，预算截止后仅做停止、清理和证据保存，不开始下一次生成。

### 3.8 T7：交付验收

- 逐项审计 AC-01～21 的实际证据，不以单元测试替代浏览器、真实存储或完整命令。新增真实模型入口使用受控本地协议验证；不消耗付费模型额度。
- 以本阶段相对开工基线的差异进行独立最终审查，修正影响需求／正确性的问题并做对应失败回归。
- 写 VERIFICATION.md：已运行的命令／结果／证据，尚未运行的真实模型与部署验收；提供启动条件、迁移命令、用户操作、预期结果以及停止恢复步骤。同步实际架构与公共索引。

## 4. 验收追溯

| AC | 验证重点 | 任务与检查入口 |
|---|---|---|
| 01 | 创建主备路由、失败编号、应用绑定 | T3、T4；创建集成与浏览器 |
| 02 | 两模式／重试／模型／工具／交付父子步骤 | T1、T3；真实任务集成 |
| 03 | 幂等／重连／显式停止 | T3；任务回归与 STOP 记录 |
| 04 | 旧实例中断，未知而非伪造成功 | T1、T2；恢复测试 |
| 05 | 所有者与管理员越权拒绝 | T2、T4；Session API 与两账号浏览器 |
| 06 | 分项 Token 缺失与覆盖率 | T1、T3、T6；模型夹具和报告 |
| 07 | 队列／存储故障不阻断生成 | T1、T3；故障注入与真实任务结果 |
| 08 | Redis 过期后仍可查未满七天追踪 | T2；时钟和只读存储测试 |
| 09 | 秘密／绝对路径／正文未采集 | T1、T3；秘密标记全量扫描 |
| 10 | 七天边界、删除竞态、迟到绑定／更新 | T2；真实存储并发与查询 |
| 11 | 导出证据生命周期独立 | T6；删除／过期后读本地报告 |
| 12 | 24 个编号、18/6 分组与规格 | T5；数据集校验 |
| 13 | 默认受控无真实模型／业务工具 | T5；网络调用探针、完整 CLI |
| 14 | live 显式预算、底层请求计数与截止 | T5；本地协议服务器与时钟 |
| 15 | 修改初始产物固定、隔离环境 | T5；任务目录及数据库隔离 |
| 16 | 能打开但按钮无效判失败 | T6；真实浏览器交互 |
| 17 | 环境错误／取消单列，分母不丢样本 | T5、T6；故障批次 |
| 18 | 评测失败不改写正常发布条件 | T3、T5；既有生成／发布回归 |
| 19 | 重复运行不覆盖、可追溯产物 | T5、T6；两次完整 CLI |
| 20 | 不可比项与覆盖缺口可见 | T6；对比用例及 HTML |
| 21 | 截图／报告失败返回未完整完成 | T6；文件故障与退出码 |

## 5. 交付与恢复

交付为当前工作区的完整代码、迁移、固定数据集、命令、文档和验证证据。先隔离测试库执行两次迁移，后启动后端查询与工作台，最后运行完整受控批次。任一权限绕过、秘密泄露、真实请求误发、正常生成退化或报告伪造完整性都阻止完成声明。

恢复顺序：关闭新追踪读写／清理 → 停止评测与相关进程 → 按开工基线仅撤回本次文件差异。保留新表和本地证据，旧业务表不回填；已过期清理的数据不能靠代码回退恢复。生产迁移、真实模型调用与对外发布不自动执行。

## 6. 里程碑与偏差

| 里程碑 | 条件 | 依赖 |
|---|---|---|
| 追踪可查询 | 权限／保留期正确，真实调用链完整，故障不阻断 | T1～T4 |
| 评测可执行 | 完整受控 24 条、两次可追溯报告与比较、真实入口预算受控验证 | T5～T6 |
| 完成交付 | 全 AC 证据审计、审查问题闭环、人工验收步骤完整，并提交、推送及核对远程哈希 | T7 |

实际进度、未验证事项和设计偏差统一写 [PROGRESS](../../PROGRESS.md)，本计划不维护第二份执行状态。
