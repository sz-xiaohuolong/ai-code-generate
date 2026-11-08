# 当前进度

## 平台 UI 美化（2026-11-08）

用户已批准按参考方案实施首页、生成工作台与三类管理员页面。需求 FROZEN；实现已完成，受控前端验收通过，独立复审 ship；状态 RELEASED（代码同步），功能提交 `a2d34c7` 已推送 `origin/main` 并核对完整远程哈希一致。方案、任务和证据见 [阶段目录](phase/2026-11-07-platform-ui-beautification/PROPOSED_DESIGN.md)、[验收记录](phase/2026-11-07-platform-ui-beautification/VERIFICATION.md)。

首页将模式、知识库与生成动作集中到输入区，保留真实作品封面与明确状态。工作台采用 Cobalt 中性色与蓝色操作、预览侧工具栏、生成记录抽屉和手机分区；管理页采用 Ink／Almanac 的平面表格层级，保留筛选、分页、编辑、精选、删除确认及封面预览。Ant Design Vue 和 CSS 共享深浅色来源。现有 16 项测试、类型／生产构建、修改文件 lint 和 26 项生产浏览器验收通过；复审发现的深色链接对比度已修至 7.016:1。

前置与边界：按 Ponytail full 复用已有组件和接口；cix-workspace 已检索并读取 PaiSmart ThemeDrawer，因技术栈不符未采用外部实现。image-to-code 所需生图能力不可用，使用用户认可的原参考图分析并落实，不冒称生成新图。验证使用真实 Chrome 与受控 API／样例，未调用真实模型、未部署、未修改业务数据；其他任务的后端与配置改动保留。


## 个人知识库辅助代码生成（2026-11-07）

用户已批准 PRD／方案并授权 MVP 开发、人工验收说明和远程同步。需求 FROZEN、方案 APPROVED；实现及受控检查完成，质量验收 PARTIAL，默认 `knowledge.enabled=false`。2026-11-08 用户批准使用 Ollama BGE-M3，已安装并切换默认与本地配置；原 5 条校准样本全过，新增保留集 11/12，年假审批仍误匹配报销资料。模型切换验证完成，质量门槛仍未全过；真实供应商生成效果未验收。

独立交付树基于 `6fcf5d2`，保留主工作区并行任务。后端 18 类 65 项、前端 11 项、类型检查／构建及新增知识文件 lint 通过；六组合实际工厂／图／工具／修复接入使用受控模型验证。代码独立复审同意默认关闭同步，界面评审 ship；容器超限、来源补写与图片规划资料边界均已回归。原有 AppChatPage 的 7 项 lint 和服务关闭历史补记竞态已单独记录。

[实施计划](phase/2026-11-07-private-knowledge-generation/IMPLEMENTATION_PLAN.md)、[验收及证据边界](phase/2026-11-07-private-knowledge-generation/VERIFICATION.md)、[业务流程与人工验收](phase/2026-11-07-private-knowledge-generation/USER_GUIDE.md)。T1～T4 实现完成，功能代码 `2e3dc3f` 已正常推送 origin/main 并核对远端哈希；T5 代码同步完成，模型质量与业务验收未完成，不宣称整个需求 VERIFIED。

BGE 切换追加验证：15 类 53 项后端测试通过，含真实 Ollama／Chroma／MySQL、旧模型空间拒绝查询及混合发布；中文质量脚本如实返回退出码 1。资源、候选阈值 0.78、复现命令与未完成项见验收第 9 节；本次未部署、未迁移业务库。

2026-11-08 本地试用已获明确批准并开启：备份业务库后创建六张知识表，启动 `ai-code-knowledge` 持久化 Chroma（仅本机 8000），本地配置覆盖 enabled=true 并关闭原始模型日志，重启后端 8123。真实登录／上传／READY／检索及容器重启持久化通过，测试夹具已清理；共享配置仍默认关闭，质量状态 PARTIAL 不变。最新证据与启动参数见验收第 10 节。

## 当前阶段：2026-11-07-generation-observability-evaluation（生成追踪与独立质量评测）

工作台创建／生成追踪与独立质量评测已完成本地实现和验收。需求 `FROZEN`，设计 `IMPLEMENTED`，验证 `VERIFIED（本地受控范围）`，工作流 `RELEASED（代码同步）`；代码同步授权 `APPROVED`，功能提交 `e987d7b` 已推送 `origin/main` 并核对完整哈希一致（此前遗漏已纠正）；未部署；业务库迁移、真实供应商质量和高负载未验证。

用户于 2026-11-07 授权先计划后编码；[实施计划](phase/2026-11-07-generation-observability-evaluation/IMPLEMENTATION_PLAN.md) 覆盖 T1～T7、REQ-01～06 与 AC-01～21。事实与证据统一见 [验收记录](phase/2026-11-07-generation-observability-evaluation/VERIFICATION.md)，可执行人工操作见其第 6 节。

| 任务 | 状态 | 交付与验证定位 |
|---|---|---|
| T1 非阻断记录器 | VERIFIED | 独立步骤与显式上下文、白名单元数据、采集故障降级；验收 AC-02／04／06／07／09 |
| T2 存储与查询 | VERIFIED | MySQL 两表、所有者鉴权、七天过期、删除竞态与中断恢复；真实隔离库与查询测试 |
| T3 调用链接入 | VERIFIED | 创建路由、标准／工作流、工具、原生重生成、Vue 修复、停止和最终产物关联 |
| T4 工作台入口 | VERIFIED | 真实 Chrome、Cookie/CORS、MySQL、分页、缺口、窄屏、越权和删除后清空 |
| T5 隔离评测命令 | VERIFIED | 24 条用例、18／6 分组、固定修改输入、fixture/live、预算、独立数据库／Redis／目录 |
| T6 断言与报告 | VERIFIED | 真实构建／浏览器、分组与完整分母、JSON／HTML、证据摘要、两轮 compare、导出独立生命周期 |
| T7 最终审计与说明 | VERIFIED | 一次独立审查的四项问题及演练发现的两项问题闭环；135 项后端、16 项前端与构建、完整 CLI／SIGINT，验收矩阵和人工步骤已完成 |

最终证据在 `.evidence/generation-observability-20261107/`：`t7-trace-summary.json`（89 项）、`t8-evaluation-summary.json`（46 项）、`t7-ui-regression.log`／`t7-ui-build.log`。A／B 两轮各 24 条符合预期、74 次受控调用；比较为 24 条质量、22 条耗时、18 条用量可比。最终源码／编译／数据／断言与报告的一致性由 `t8-completion-audit.json` 核验，报告范围／中位数、详细运行编号和最终浏览器证据见验收 5.4。

前置与取舍：按 Ponytail full 复用已有管线；通过 cix-workspace 在 local／my-vault 检索，借鉴 PaiFlow 显式上下文传递，不复制 Python 实现或原文导出。评测已检索但未采用外部实现，复用项目生成服务和 Selenium；Context7、当前官方资料与锁定依赖交叉核验。质量 FAIL 继续评测，环境／证据 ERROR 停止后续生成且保留 SKIPPED；脏工作树缺少可恢复源码时明确不能完整复现。

开工前 330 个文件的快照仍在 `baseline-tree/`；依赖的原生完整性校验／路由回退／Vue 修复一并同步，已提交的并行知识库实现保留。评测使用专用数据库、独立 Redis、工作目录及受控模型，未触达真实用户应用和付费供应商。本地验收与远程代码同步完成；业务环境启用按人工验收说明执行。

文档整理（2026-11-07）：按用户要求将原 releases 与 tasks 合并为 `phase/YYYY-MM-DD-功能名/`，统一归档 5 个阶段、17 份文档；同步导航与引用，目录规则见 [文档地图](../../DOCUMENT_MAP.md)。此次仅整理文档，阶段需求、批准与验收结论继续沿用原记录。

- 整理验证：17 份迁移文档除路径修正外正文逐字一致；24 份受影响文档中的 101 个本地链接目标均存在，公开文档无具体旧路径残留；Git 可见的 296 个非 Markdown 文件路径与 SHA-256 均与整理前一致，`git diff --check` 通过。仅做文档静态检查，未运行业务测试、提交或推送。

历史文档归档（2026-11-07）：将原独立工作流目录中的 5 份设计与计划并入 `phase/` 下的 3 个历史阶段，删除已迁空的旧目录；同步文档地图、Agent 入口与计划中的执行提示。原技术正文、任务勾选和历史结论保留，后续工作流文档统一维护在 `docs/vibe/`。此记录不改变当前阶段状态或实施授权。

- 归档验证：5 份迁移文档除 3 处执行入口提示外正文一致；受影响文档本地链接目标存在，Git 可见文件无旧路径或旧文件名引用，旧目录已删除；范围外已有文件内容保持一致，相关文档空白检查通过。证据见 `.evidence/superpowers-doc-migration-20261107/validation.log`；仅做静态检查，未运行业务测试。

最近调研（2026-10-22）：完成 [竞品与功能差距研究](../../research.md)，核查国内外官方资料及当前工作树，区分已实现、部分实现和未发现能力；包含 15 项差距与两条候选演进路线。仅交付研究和文档检查，未冻结新需求、修改业务代码或运行竞品同题测试；检查记录见 `.evidence/competitor-research-20261022-validation.log`。

最近配置调整（2026-10-22）：按用户要求在 `application.yml` 默认设置 `spring.devtools.restart.enabled=false`，不删除 DevTools 依赖、不修改启动入口；README 补充手动重启及显式开启说明。`./mvnw -Dtest=DevToolsConfigTest test` 两项通过，覆盖默认关闭和应用启动参数显式开启；关闭用例修改前失败、修改后通过，日志见 `.evidence/devtools-default-disable-20261022-{red,green}.log`。仅完成配置加载／属性绑定验证，未启动业务应用；前后端保持停止，未提交或推送。

## 修复记录：Vue 自动修复丢失应用需求（2026-10-21）

- 状态：VERIFIED（本地受控模型与真实 Vue 构建、预览范围）；范围为标准与工作流模式共用的 Vue 自动修复。用户已授权修复“任务管理应用生成农场”问题；保留已有未提交改动，不涉及 Schema、鉴权、提交、推送或部署。
- 根因：原任务 `1fe5abb3-03ef-40f1-a143-bfaf07ed7f29` 首轮没有写入项目文件，自动修复只收到“缺少 package.json”，临时记忆未携带需求，随后生成农场。发布入口与本任务产物一致，已排除旧预览串用；首次不落盘的模型结束原因未记录，不推断为 token 耗尽。
- 实现：任务执行上下文在模型生成前固定应用初始需求、已有用户要求和本轮要求。Vue 修复将这些需求连同源码状态、真实诊断固定在单独临时记忆中，文件工具历史仍用有限窗口；空业务源码时要求按原需求完整重建，已有源码时要求优先局部修复。缺失需求上下文、取消或超时均不能开始修复模型调用；原有最多两轮修复与旧成功版本保护继续生效。
- 验证：`.evidence/task-intent-drift-20261021/` 保存原任务安全摘要、`red.log`（两模式四个集成样本因需求丢失失败）、`guard-red.log`（缺需求及取消保护失败）、`continuation-red.log`（长需求和多轮文件工具调用失败）与最终 `regression.log`。后者针对 16 个测试类共 55 项通过，含标准／工作流、空项目／已有源码、真实 Vue 编译和浏览器挂载、请求裁剪、取消、工作区与交付上限。
- 边界：外部供应商真实生成质量未由本轮受控模型回归证明；原管理员应用的历史农场产物尚未替换，本地测试账号没有该应用所有权，需由原应用所属账号按完整任务管理需求重新生成并验收。此结论不改写下方历史发布验证。

## 已交付阶段：2026-10-20-tool-context-pruning（工具调用上下文裁剪）

- 状态：RELEASED（代码同步）；验证：VERIFIED（仅本任务离线 AC 范围；工作区及纯暂存区快照各 100 项相关测试通过，独立复审通过）。用户已授权文档先行、实现、测试、提交并推送 `origin/main`，不包含部署或付费模型测试。
- 范围、方案、AC 与实施顺序见 [任务记录](phase/2026-10-20-tool-context-pruning/README.md)。先保留开工前工作区，再用失败回归驱动实现；仅精简模型请求，不删除完整展示历史或真实文件。
- 当前基线 `aa53a4d`；已有路由回退、原生代码完整性、推理配置及其他未提交差异全部保留并排除本次提交。基线快照：`.evidence/tool-context-pruning-20261020/`。
- 已完成：文档先行、失败回归、请求副本裁剪和本地真实协议适配器验证；固定样本序列化请求字符量下降 58.34%，不代表实际 Token／耗时或循环率。
- 同步结果：功能提交 `fc538d9` 已推送 `origin/main`，推送后完整远程哈希与本地一致；本记录单独文档提交收尾。本次任务完成，未部署或执行真实供应商验收；命令、证据与限制见上述任务文档。

| 当前范围 | 已核验事实 | 下一步 |
|---|---|---|
| P0 生成与 Vue 交付可靠性 | REQ-01～10 已实施并完成适用验收，功能提交 59310a9 已同步 | 本 Phase 完成 |
| 已有未提交修复 | 路由回退和推理配置保持原样，基线差异保存于 .evidence/p0-reliability/preexisting.patch | 不混入本 Phase 提交 |

## 已交付阶段：2026-10-18-p0-reliability（生成任务与 Vue 交付可靠性）

- 当前状态：RELEASED（代码同步）；验证状态：VERIFIED（限冻结范围）；操作状态：ACTIVE。
- 用户授权：2026-10-18 全部批准 Q1～Q7，明确要求一个 Phase 的设计、实现、提交与远程同步；不包含线上部署。
- 基线：9035613；有效需求：[SPEC.md](phase/2026-10-18-p0-reliability/SPEC.md)；设计：[PROPOSED_DESIGN.md](phase/2026-10-18-p0-reliability/PROPOSED_DESIGN.md)；计划：[IMPLEMENTATION_PLAN.md](phase/2026-10-18-p0-reliability/IMPLEMENTATION_PLAN.md)；验收：[VERIFICATION.md](phase/2026-10-18-p0-reliability/VERIFICATION.md)。
- 适用证据：后端 139 项回归通过，最后补充修改的 12 项发布／交付检查、2 项成功来源检查及 1 项快照检查通过；前端 9 项、类型检查与生产构建通过。独立审查 4 项 Important 已修复，真实浏览器发现的预览前缀、取消误判、秒级历史去重均已复验。
- 真实路径：标准 Vue 生成／修改／预览交互，断开连接后后台完成，主动停止、保留成功预览与下载、真实数据库补记、刷新后无重复历史。两模式真实 Vue 编译自愈由受控模型集成覆盖，不宣称真实模型成功率达标。
- 实现：现有 Redis 保存 24 小时幂等与任务；成功指针长期保留；独立草稿；待记历史稳定主键事务补记；默认 20 分钟、最多两轮构建修复；服务中断不自动重跑。无业务 Schema 变更。
- 代码同步：功能提交 `59310a9` 已推送 origin/main 并核对远程引用一致；文档收尾单独同步。
- 运行限制和未覆盖外部边界详见验收文档；未部署线上。

## 其他历史交付与修复记录

以下状态、基线与验证结果均为对应交付或修复当时的记录。

### 修复记录：原生多文件缺资源与单文件截断（2026-10-19）

- 范围：工作流原生多文件博客与标准原生 HTML 赛车的完整生成、解析和保存；保留两种模式和旧成功版本，不涉及 Schema、鉴权、提交、推送或部署。
- 根因：原博客任务 `93538eb8-79a6-4125-a621-b746c9bf6a84` 只有一个 HTML 代码块，却引用 CSS／JS；保存器用 793 字节通用 CSS 和 64 字节占位脚本伪装成功，真实布局和交互缺失。原赛车任务 `8c46c641-8f9e-4e4e-9b73-e71021e9e979` 则耗尽 8192 输出 token，在 `ctx.shadowBl` 处截断。
- 实现：去掉静默注入通用 CSS／占位 JS；写任何文件前验证完整 HTML 及其引用的 `style.css`／`script.js`。以专用完整性异常触发同一任务内至多一次完整重生成，保留原需求、附具体诊断，重置流式收集和任务输出；供应商错误不按代码修复重试，取消、执行权及总时限继续生效。补齐多文件提示词的三个结束围栏，并修正资源引用插入时将换行写成字面 `n` 的问题。
- 配置：忽略的本地原生流式 `deepseek-flash` 输出预算由 8192 调至 32768，配置模板同步增加预算示例；同步主模型和用户已有的 Vue 专用模型配置保持原样。真实请求日志已确认发送 `max_tokens: 32768`。
- 回归：新增用例先复现缺资源静默成功和无修复的截断失败；49 项解析、保存、完整性修复、旧文件保护、取消、任务与预览相关测试通过，见 `regression.log`。另 19 项真实图编排、事件格式、类型分流、执行时限和 Vue 流取消检查通过，见 `workflow-regression.log`；本次相关回归合计 68 项。
- 真实工作流：以截图博客完整需求新建 `469694549116215296`，任务 `b76535d9-2566-4436-aa9c-3b4d48602a22`。第一次真实模型仍只输出 HTML，新检查触发一次重生成后成功；最终 HTML 10302、CSS 10346、JS 2733 字节，无通用占位资源。浏览器桌面 Logo 为 34×34，手机视口及页面宽度均为 369，无横向溢出；菜单展开、跳转后收起通过，控制台无错误。
- 真实标准模式：以截图赛车原始需求新建 `469694548772282368`，任务 `cf791b85-edaa-481e-aa2c-917c6998ebbd` 一次生成成功，9038 输出 token、31315 字节完整 HTML。浏览器验证开始、分数增长、键盘暂停及重开、按钮继续和暂停；暂停时分数不再增长，手机画布 369×528 适配页面，控制台无错误。
- 验证边界：使用正常 API 与现有测试账号按原需求新建应用，未修改截图中其他账号的旧任务、文件或成功状态；旧应用需由所属账号重新生成才能替换产物。未运行包含外部模型调用的全量测试；不以本次两个真实样本宣称所有模型组合或生成内容质量均已验证。既有 P0 历史结论保持封存。
- 证据：`.evidence/native-generation-20261019/` 下原任务与输出备份、`red.log`、`newline-red.log`、`green.log`、`regression.log`、`workflow-regression.log`、`live-check.py`、`live.log`、`live-repair.log`、两模式 `live-context/live-task/live-result.json`、`browser-result.json` 及 `blog-desktop.jpg`、`blog-mobile.jpg`、`racer-playing.jpg`、`racer-paused.jpg`、`racer-mobile.jpg`。

### 修复记录：HTML 预览混入模型说明文字

- 范围：HTML 单文件回复的解析与保存校验；不调整前端分栏、不改变已封存 P0 验收结论，不提交、推送或部署。
- 根因：应用 `469301059857309696` 的旧产物在 `ctx.fillStyle` 处截断，没有脚本、HTML 和 Markdown 结束标记。旧解析器匹配不到完整围栏后，将包含前言的整段回复直接保存为 `index.html`，因此说明文字出现在预览顶部。
- 修复：只提取完整 HTML 文档，兼容缺少 Markdown 结束围栏的完整页面，保留脚本字符串、注释及属性内的特殊字符；缺少文档或脚本结束标记时明确失败。结构化保存入口和废弃静态入口复用同一解析器，校验在覆盖文件前执行。HTML 提示词的示例补齐围栏，并要求精简且完整输出。
- 自动化：新增回归最初 22 项中 20 项失败；补充脚本模板字符串及原始 HTML 内围栏回归均先失败后修复。最终 `HtmlCodeParserTest,HtmlCodeFileSaverTest,CodeParserTest,MultiFileCodeParserTest,GenerationWorkspaceTest,GenerationTaskServiceTest,PreviewVerifierTest` 共 40 项通过。期间任务服务的既有测试退出时出现过异步线程池拒绝异常，最终运行未再出现；未将其作为本次解析修复的一部分改动。未运行包含真实外部调用的全量测试。
- 真实验证：用截图原文件验证新解析器明确拒绝截断代码。新建工作流 HTML 测试应用 `469344555255357440`，任务 `fb04b950-e505-4e30-8bf7-ca0b44f8703e` 经真实生成、质检与预览检查后成功；保存内容从文档头到 `</html>`，无前后说明或围栏。真实浏览器右侧显示正常页面，计数按钮点击后从 0 变为 1。
- 旧页面限制：原始文件已备份，未删除前言后将其伪装成可运行游戏，也未修改历史成功状态。当前验证会话为测试账号，原应用属于另一账号，任务接口拒绝代操作；该旧游戏需由创建账号重新生成，不能把测试页验收等同于旧游戏已恢复。
- 证据：`.evidence/html-preview/` 下 `original-index.html`、`original-rejection.log`、`red.log`、`template-red.log`、`raw-template-red.log`、`green.log`、`live-check.py`、`live-context.json`、`live-result.json`、`live.log`、`live-output.txt`、`live-index.html`、`final-replay.log`、`browser-result.json`、`live-preview.jpg`。最终解析器已重放同一真实模型输出并与已验证产物逐字核对；仅声明本次解析链路与测试页交互已验证。

### 修复记录：创建请求显示系统错误

- 范围：修复首页创建应用因专用路由模型不可用而失败的问题，以及同一作品集请求后续出现的空代码保存失败；保留两种模式、代码类型及输入护轨。
- 根因：2026-10-18 13:18 的真实请求在专用路由模型返回 HTTP 429 后直接中断，异常被全局处理器包装为“系统错误”。
- 后续根因：真实流式请求以 `length` 结束，8192 个输出 token 全部用于推理，正文为 0。已在忽略的本地配置与受版本控制的配置模板中为 DeepSeek HTML／多文件流式模型设置 `thinking.type=disabled`，保留 Vue 专用推理模型配置。
- 计划与状态：失败回归 → 路由回退与明确错误提示 → 本地流式配置修正 → 针对性审查与真实服务复测，均已完成。仅本次修复通过验证，不改变下方历史 Release 的整体验证结论。
- 自动化：`./mvnw -Dtest=RoutingFallbackTest,AppGenerationModeTest,RoutingChatModelConfigTest,AppControllerSseErrorTest,GlobalExceptionHandlerTest test`，21 项通过；新增回归修复前 5 项失败、修复后 9 项通过。覆盖两种模式创建、三种路由结果、429 与格式错误回退、双模型故障不入库、相同模型不重复回退及护轨拒绝。
- 真实服务：两种模式均通过创建 API；非法模式与输入护轨均返回 40000。截图中的原始作品集需求成功创建为 `multi_file` 工作流应用 `469298162650210304`，修正流式配置后完成代码生成、质检和浏览器预览；HTML／CSS／JS 静态资源均返回 HTTP 200。本地运行服务已自动重载本次代码与配置。
- 证据：`.evidence/routing-fallback/` 下 `red.log`、`green.log`、`diagnostic-summary.json`、`live-api.json`、`live-static.json`、`live-backend.log`、`workflow-success.jpg`；回归入口 `RoutingFallbackTest`。未运行含真实模型调用的全量 Maven 测试，也未把静态预览升级为全部真实模型组合验证。
- 本次未提交或推送；保留用户已有的推理模型配置改动。

### 已交付阶段：2026-10-18-generation-modes（标准与工作流生成模式）

| 范围 | 当前事实 | 下一步 |
|---|---|---|
| `generation-modes` | 已实现，独立审查与复审通过；后端 37 项、前端 4 项及生产构建通过；两种模式真实 HTML 与本地产物闭环通过 | 功能提交 `c9179c4` 已推送并核对远程一致；当前交付完成 |
| 外部验证缺口 | 路由模型 429 阻断完整新建流程；未穷举真实多文件／Vue 模型组合 | 外部配额恢复后补验，详见验证矩阵 |
| 上一阶段截图功能 | 变更前稳定提交 `680f0c9`；保留原 Release 文档 | 不复用其验证作为本次功能证据 |

- 历史 Release：`generation-modes`；归档阶段：`2026-10-18-generation-modes`
- Workflow State：`RELEASED`（代码同步）
- Operational Status：`ACTIVE`
- Verification Status：`PARTIAL`
- Shipping Authorization：代码提交与推送已获授权；线上部署不在范围内。
- Last Stable Commit：`c9179c4`（功能实现与验证）；后续补充交付归档与经构建、浏览器验证的窄屏标签换行。
- Last Updated：`2026-10-18`
- Effective SPEC：[SPEC.md](phase/2026-10-18-generation-modes/SPEC.md)
- 设计：[PROPOSED_DESIGN.md](phase/2026-10-18-generation-modes/PROPOSED_DESIGN.md)
- 计划：[IMPLEMENTATION_PLAN.md](phase/2026-10-18-generation-modes/IMPLEMENTATION_PLAN.md)
- 验证：[VERIFICATION.md](phase/2026-10-18-generation-modes/VERIFICATION.md)

| 切片 | 状态 | 证据 |
|---|---|---|
| S1 模式持久化与分流 | 已完成 | 创建契约测试、本地迁移及真实更新固定模式 |
| S2 工作流过程与结果 | 已完成 | 真实图回归、取消保护、审查复审及真实 SSE |
| S3 产品入口 | 已完成 | 前端 4 项测试、生产构建、浏览器偏好与两种模式 HTML 生成 |
| S4 文档与同步 | 已完成 | 功能提交 `c9179c4` 已推送 `origin/main`，本地与远程哈希一致 |

用户并行修改的推理模型名称保持在工作区，从本次提交中排除。

代码同步补记：根 AGENTS.md 已固化开发完成后的提交／推送长期授权；本阶段提交前另从暂存区导出独立目录，编译与 9 类 53 项针对性回归通过，保留此前完整受控验收的适用边界。历史文档归档和相关调研随 `e987d7b` 一并同步；上文历史“当时未提交”记录保留原义。
