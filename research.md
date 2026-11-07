# 零代码 / AI 应用生成平台竞品调研

> 调研日期：2026-10-22
> 调研对象：AI-Code-Generate 当前工作树、国内外大厂公开产品资料
> 调研目的：明确我们已经具备的能力、与竞品的结构性差距，以及下一阶段应该补齐的核心能力。
> 基线：`main`，HEAD `6fcf5d2`，包含调研时已有的未提交修复。本文是研究建议，不代表需求已冻结或已授权实施。

## 一、结论先行

AI-Code-Generate 当前代码已覆盖 **“自然语言 → 前端代码 → 构建检查 → 在线预览 → 部署 / 下载”**，有后台任务、Vue 构建自愈和预览点选修改等基础。根据产品定位及下文源码证据，当前更适合称为“AI 前端应用生成工作台”。本次没有进行同题生成实测，不能据此宣称生成质量、速度或成功率领先竞品。

竞品的领先点主要集中在四个层面：

1. **真实业务数据与身份**：秒哒和 Google AI Studio 已公开数据库、认证接入能力，使生成结果可以继续承担真实业务。[秒哒数据库维护](https://cloud.baidu.com/doc/MIAODA/s/kmhiia8g4)、[Google 全栈能力](https://ai.google.dev/gemini-api/docs/aistudio-fullstack)
2. **持续修改与发布**：秒哒提供后端环境隔离，Power Platform 提供跨环境部署管道；它们解决的是上线后的持续交付问题。[秒哒多环境](https://cloud.baidu.com/doc/MIAODA/s/emrm8ossc)、[Power Platform 管道](https://learn.microsoft.com/power-platform/alm/pipelines)
3. **外部业务连接**：宜搭连接器把数据操作、通知和第三方系统接入业务流程。[宜搭连接器](https://developers.aliwork.com/docs/guide/concept/connector)
4. **复用与代码协作**：飞书的模板 / 组件资源和 v0 的仓库导入，为应用复用和后续开发提供了入口。[飞书资源体系](https://www.feishu.cn/content/126935747291)、[v0 Git Import](https://v0.app/docs/git-import)

研究建议是：短期先改善版本恢复、素材输入和文件查看，降低现有用户迭代成本；若产品目标确认扩展到真实业务应用，再补齐 **数据与认证、连接器、运行隔离及发布治理**。后者涉及新的架构和安全边界，不应仅凭竞品具备就全部排入开发。

## 二、调研口径与当前基线

### 2.1 对标口径

本次将竞品分为三类：

| 类别 | 代表平台 | 用户主要购买的价值 | 对我们的启示 |
| --- | --- | --- | --- |
| AI 原生应用生成器 | 百度秒哒、Google AI Studio、Vercel v0、Figma Make | 用自然语言快速生成并持续修改应用 | 直接竞争，关注生成质量、运行时、代码与发布 |
| 企业低代码 / 零代码平台 | 腾讯微搭、钉钉宜搭、飞书 aPaaS、华为 Astro、Microsoft Power Apps、Google AppSheet、AWS App Studio / Amazon Quick Apps | 搭建数据驱动的业务系统 | 关注数据、权限、流程、连接器、治理，不宜机械照搬设计器 |
| 设计到代码工具 | Figma Make、v0 Design Mode | 将视觉稿或运行中页面快速变成可编辑代码 | 关注截图 / 设计稿输入、可视化编辑、团队协作和 Git |

### 2.2 证据标准与边界

- **外部资料**：以厂商产品页、帮助中心和开发文档为依据，查询日期统一为 2026-10-22；没有注册付费账号、创建竞品应用或执行同题性能测试。公开支持不代表所有套餐、地区和应用类型均可用。
- **本地事实**：检查前后端源码、Controller、路由、SQL、生成任务、构建、发布与预览入口。下文“已实现”表示当前工作树存在实现路径，不等于本次运行验证通过；历史验收的范围沿用原记录。
- **缺失判定**：组合检查数据结构、服务端接口和前端操作入口。“未发现”指本仓库未发现可由用户配置和使用的完整能力，不排除用户下载代码后自行接入服务，也不否认模型偶尔生成类似页面。
- **非穷举调研**：国内覆盖百度、腾讯、阿里、字节、华为；国外覆盖 Google、Microsoft、AWS，并以 Vercel、Figma 作为直接交互对标。后两者属于专业产品厂商，不与大型云厂商混称。
- **建议性质**：优先级依据用户阻断程度、当前定位和依赖关系判断；不提供未经实测的竞品总分、市场份额或工期承诺。

### 2.3 我们当前已经实现的能力

基于 [PRODUCT.md](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/PRODUCT.md)、[系统架构总结](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/architecture-summary.md)、[README.md](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/README.md) 和 [当前进度](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/vibe/PROGRESS.md) 的代码与文档事实，当前能力如下：

| 能力域 | 当前实现 | 结论 |
| --- | --- | --- |
| 自然语言生成 | 标准模式和 LangGraph4j 工作流模式；支持 HTML、原生多文件、Vue 3 工程 | 已实现 |
| 生成过程 | SSE 流式输出、任务快照、工具调用进度、取消、幂等、租约和后台继续执行 | 已实现；任务信息与幂等记录有 24 小时保留边界 |
| Agent 管线 | 图片素材收集、提示词增强、智能路由、代码生成、质检重试、Vue 构建 | 已实现 |
| 可靠交付 | HTML / 多文件完整性检查；Vue `npm install`、`npm run build`、浏览器真实挂载检查；失败时保留最近成功版本 | 已实现，仍需扩大测试覆盖 |
| 交互式修改 | 多轮对话、iframe 元素点选、定向 Prompt 注入、预览 / 代码切换 | 已实现，属于当前特色 |
| 预览 | 静态托管预览，桌面 / 平板 / 手机视口模拟，发布后异步生成封面 | 已实现 |
| 发布与交付 | 一键部署访问链接、源码 ZIP 下载、代码复制 | 已实现，发布能力较轻 |
| 平台账号 | 注册、登录、Redis Session、普通用户 / 管理员角色、应用归属校验 | 已实现，但仅是平台自身权限 |
| 应用管理 | 应用 CRUD、我的应用、精选广场、聊天历史 | 已实现 |
| 安全与用量 | 输入护栏、用户级限流、任务总时限、部分模型调用的 Token 汇总 | 已有基础；无完整费用账本和项目预算 |
| 生成应用运行时 | 应用自身的数据库、登录注册、角色权限、文件存储、支付等 | 未实现为平台能力，需生成代码后自行补齐 |
| 版本与环境 | 独立任务草稿、最近成功版本、固定版本资源地址、独立部署目录；未发现版本选择 / 回滚和业务数据多环境 | 部分实现；不能写成完全没有版本保护 |
| 团队协作 | 只有应用所有者和管理员，没有工作空间、成员、项目角色、评论和协作审阅 | 未实现 |
| 外部连接 | 当前主要是图片搜索等生成工具，没有通用 API / OAuth / Webhook / 连接器中心 | 未实现 |

这里要特别区分两个概念：平台有用户登录和应用权限校验，并不等于平台能为“生成出来的应用”提供登录、业务角色和数据行级权限。后者是企业应用能否真正上线运营的关键缺口。

### 2.4 本地证据索引

以下链接指向本地实际源码；行号为调研时定位点，代码后续变化时以符号和文件为准。

| 编号 | 源码或文档 | 支持的判断 |
| --- | --- | --- |
| L01 | [数据库结构](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/sql/create_table.sql)、[实体目录](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/entity) | 业务持久化对象为平台 user、app、chat_history；未发现生成应用的数据模型、租户和业务用户模型 |
| L02 | [任务接口](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/controller/GenerationTaskController.java:24)、[任务服务](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/generation/GenerationTaskService.java)、[Redis 任务存储](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/generation/RedisTaskStore.java) | 创建、订阅、恢复、停止、所有权、时限和任务保留逻辑 |
| L03 | [生成工作区](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/generation/GenerationWorkspace.java:25)、[固定版本资源](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/controller/StaticResourceController.java:62) | 任务目录、成功版本指针和固定版本资源；没有用户可选回滚接口 |
| L04 | [点选桥接](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/ai-code-frontend/src/utils/visualEditor.ts)、[输入组件](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/ai-code-frontend/src/components/AppChatComposer.vue:91) | 元素选择和文本发送；没有样式属性面板、附件上传闭环 |
| L05 | [代码展示逻辑](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/ai-code-frontend/src/pages/app/AppChatPage.vue:415) | 提取最近 AI 消息中的首个代码块或正文，不是实际工作区文件浏览器 |
| L06 | [Vue 构建](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/core/builder/VueProjectBuilder.java:22)、[预览检查](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/generation/PreviewVerifier.java:63) | 构建、挂载及部分错误检查；没有按需求验证点击、提交等功能路径 |
| L07 | [部署服务](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/service/impl/AppServiceImpl.java:305)、[下载服务](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/service/impl/ProjectDownloadServiceImpl.java)、[截图服务](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/service/impl/AppScreenshotServiceImpl.java) | 发布复制成功产物、ZIP 下载、真实封面截图 |
| L08 | [生成图编排](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/langgraph4j/CodeGenWorkflow.java:206) | 固定生成节点；不等于可配置业务审批 / 定时流程 |
| L09 | [任务用量字段](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/generation/GenerationTask.java:26)、[流式用量记录](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/generation/UsageTrackingStreamingModel.java:38)、[用量汇总](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/generation/GenerationExecution.java:39) | 已有部分真实 Token 记录；不应写成零用量统计 |
| L10 | [前端路由](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/ai-code-frontend/src/router/index.ts)、[后端入口](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/controller)、[应用请求模型](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/model/dto/app) | 未发现团队、连接器、Git、数据设计器操作闭环 |
| L11 | [本机构建进程](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/core/builder/VueProjectBuilder.java:58)、[浏览器参数](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/generation/PreviewVerifier.java:51)、[资源接口](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/src/main/java/com/xhl/aicodegenerate/controller/StaticResourceController.java:37) | 未发现任务级容器隔离和预览访问策略配置；外部基础设施未核验 |
| L12 | [进度](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/vibe/PROGRESS.md)、[README](/Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/README.md) | 区分既有修复、历史验证和粒度过粗的截图待办 |

README 中“生成结果自动截图 & 效果对比”仍未勾选，但部署后封面截图已有实现；当前缺口是效果比较和测试报告，不能把截图整体列为未实现。OpenAI 兼容配置也已经允许接入兼容供应商，缺的是供应商差异适配、用户选择与质量 / 成本治理，而不是完全无法换模型。[L07、L09、L12]

## 三、国内竞品

### 3.1 百度秒哒 MIAODA：与我们最接近的直接竞品

秒哒定位为基于自然语言和智能体协作的生成式应用开发平台。官方文档已经覆盖应用编辑、AI 质检、数据库维护、登录注册、源码导出、后端多环境、回滚、微信小程序和 App 打包等完整链路。

已公开的关键能力包括：

- **生成应用自带后端能力**：数据库维护页支持生成数据库的增删改查、CSV 导入导出和筛选；开发者可直接维护业务数据。
- **生成登录与用户体系**：支持用户名、手机号、邮箱等登录组合，并有 profiles、用户管理和超管角色。
- **开发 / 线上环境隔离**：多环境模式将开发环境和线上环境的数据库隔离，支持首次全量发布、后续增量发布和开发环境版本回滚。
- **应用级质量检查**：AI 质检会依据需求文档自动访问应用、模拟真实用户行为，并输出问题和自动修复建议。
- **多端与运营能力**：官方更新记录显示，秒哒已持续扩展 iOS / Android 打包、定时任务、多应用共享后端、SEO Agent、模板中心、应用跨空间迁移和企业协作等能力。
- **可视化编辑**：支持页面管理、组件级 GUI 修改、自然语言修改、撤销 / 恢复和本地图片上传。

对我们的直接差距是：我们在生成任务可靠性和前端工程交付上更聚焦，但秒哒已经把生成结果扩展成有数据库、用户体系、环境隔离和运营入口的应用产品。其后端多环境、登录注册和 AI 质检文档可作为我们下一阶段的需求参考。

来源：[秒哒产品文档](https://cloud.baidu.com/doc/MIAODA/index.html)、[数据库维护页](https://cloud.baidu.com/doc/MIAODA/s/kmhiia8g4)、[登录注册功能](https://cloud.baidu.com/doc/MIAODA/s/kmhtyk1mn)、[后端服务多环境](https://cloud.baidu.com/doc/MIAODA/s/emrm8ossc)、[AI 质检](https://cloud.baidu.com/doc/MIAODA/s/Xmo6tpc20)、[编辑应用指南](https://cloud.baidu.com/doc/MIAODA/s/tmcit6fc7)、[2026 年更新日志](https://cloud.baidu.com/doc/MIAODA/s/Zmm32qp8x)。

**适用边界**：GUI 修改只覆盖部分应用；源码导出不覆盖所有历史应用，复杂后端应用导出后还需配置服务。AI 质检也明确不覆盖多角色全量验证、真实支付等场景。数据库维护文档较早的“共用环境”表述与 2026-09-09 更新的多环境文档存在版本差异，环境能力按较新的专门文档描述；不能把开发环境回滚扩大解释为任意生产数据回滚。[源码导出限制](https://cloud.baidu.com/doc/MIAODA/s/Xmewgmsq7)、[质检限制](https://cloud.baidu.com/doc/MIAODA/s/Xmo6tpc20)、[多环境说明](https://cloud.baidu.com/doc/MIAODA/s/emrm8ossc)

### 3.2 腾讯云微搭 WeDa：企业级运行底座和云生态

微搭采用拖拽式开发，支持 PC Web、H5、小程序、表单、管理系统和企业内部应用。官方产品页明确提供数据模型、工作流、用户权限、微信生态集成、私有化部署、外部数据库 / API 接入、多租户和 SSO 等能力。

微搭的核心优势不是单次生成效果，而是把应用放进腾讯云运行环境：

- 可视化组件和页面设计器覆盖多端应用。
- 数据源和数据模型支撑增删改查、内容管理和运营后台。
- 支持工作流、表单、消息推送和企业微信等生态能力。
- 可直连外部数据库和 API，不强制迁移数据。
- 支持公有云构建后迁移到本地服务器，提供设计态和运行态私有化部署。
- 面向 ISV 提供多租户和数据隔离，企业能力包括 RBAC 和 SSO。

对我们的差距主要在后端和交付治理：我们目前部署的是静态 / 构建产物，微搭交付的是带数据、权限、流程和企业连接的应用系统。

来源：[腾讯云微搭产品页](https://cloud.tencent.com/product/weda)、[微搭数据模型文档](https://cloud.tencent.com/document/product/1301/86772)。

**适用边界**：本节参考产品级能力说明，未验证私有版套餐或部署环境；产品页所述能力不代表免费版全部提供，也不能据此推断其 AI 生成质量。

### 3.3 钉钉宜搭：表单、流程、权限和连接器成熟

宜搭将应用拆成表单、流程表单、报表、门户、DataV 大屏、自定义页面和外部链接等模块。官方文档显示，宜搭支持按成员、角色、部门和数据条件配置权限，连接器支持钉钉应用、企业自有系统和第三方应用，并通过触发器和节点进行自动化编排。

它的竞争壁垒有三层：

1. **数据结构标准化**：表单、视图、聚合表和数据服务形成低代码数据层。
2. **业务流程标准化**：申请、审批、工单等流程通过流程表单直接配置。
3. **组织与生态标准化**：钉钉通讯录、待办、群通知、日程和考勤等连接器可以直接复用。

我们当前只有生成过程工作流，没有面向用户的业务流程设计器；只有平台账号权限，没有生成应用的细粒度数据权限；只有图片搜索等内部工具，没有可配置连接器。

来源：[宜搭页面类型与能力](https://docs.aliwork.com/docs/yida_support/poq66i)、[宜搭权限设置](https://docs.aliwork.com/docs/yida_support/wtwabe/ckyvwt/ulqab1)、[宜搭连接器](https://developers.aliwork.com/docs/guide/concept/connector)、[宜搭设计器](https://developers.aliwork.com/docs/guide/designer)。

**对标边界**：宜搭是企业流程和数据管理参照，不是与本项目完全等价的源码生成产品；它的设计器撤销能力不等同于任意 Git 版本回滚。[宜搭开发者介绍](https://developers.aliwork.com/docs/guide/about)、[设计器操作](https://developers.aliwork.com/docs/guide/designer)

### 3.4 飞书低代码 / aPaaS：协同入口、模板生态和开放平台

飞书低代码强调组织协同和飞书生态。官方介绍显示，平台支持从飞书通讯录同步成员和部门、配置飞书卡片消息、对接审批、发布到飞书企业自建应用，并通过方案中心提供解决方案、应用模板和组件库。飞书 aPaaS 进一步强调低代码和全代码结合、企业应用开发以及 AI 能力。

对我们的启示是：应用生成平台不能只有一个编辑器，还需要有 **模板 / 组件 / 方案分发机制** 和 **组织工作台入口**。我们目前的精选广场更像展示页，还没有“复制模板、安装组件、在团队空间内复用”的产品机制。

来源：[认识飞书低代码平台](https://www.feishu.cn/content/126935747291)、[飞书 aPaaS](https://www.feishu.cn/paid/lowcode)、[飞书开放平台介绍](https://open.feishu.cn/document/client-docs/intro?from=op_develop_app)。

**对标边界**：本节仅使用 aPaaS / 低代码文档；飞书多维表格与 Aily 是相关但不同产品，不能把它们的全部能力自动计入 aPaaS。与 Aily 集成的 AI 助手会按对象、字段和记录权限取数，是值得借鉴的权限边界。[AI 助手集成说明](https://www.feishu.cn/content/519389270017)

### 3.5 华为云 Astro：零代码、流程、大屏和企业治理

AstroZero 覆盖零码、低码、流程、大屏、智能助手和高低码结合，并把对象、流程、连接器、数据接入、角色权限、环境和部署纳入同一平台。官方资料将其定位为可快速构建行业和大型企业应用的端到端平台。

它说明企业平台的完整形态不仅是页面搭建，还包括：对象模型、流程编排、实时大屏、外部数据接入、权限和应用治理。对我们而言，这些能力属于中长期企业版方向，不适合在当前个人开发者定位下全部一次性实现。

来源：[华为云 Astro 能力介绍](https://www.huaweicloud.com/special/astro-from.html)、[AstroZero 用户指南](https://support.huaweicloud.com/astrozero/index.html)。

**对标边界**：Astro 是产品家族，Zero、Flow 和大屏能力各有范围；不能把家族能力当作单一免费产品的默认能力。零代码轻应用明确支持工作表、角色权限和自动化，本文据此判断我们的业务建模缺口。[AstroZero 零代码流程](https://support.huaweicloud.com/intl/zh-cn/usermanualnocode-astrozero/astrozero_05_1451.html)

## 四、国际竞品

### 4.1 Google AI Studio + Firebase：从原型直接走向全栈

Google AI Studio 当前已支持全栈应用开发：应用可以包含 Node.js 服务端运行时、npm 包、服务端密钥、第三方 API、外部数据库，并可通过 Firebase 集成自动配置 Firestore 和 Firebase Authentication。官方文档明确将其定位为超越纯客户端原型的全栈开发路径。

需要注意产品入口变化：Firebase Studio 官方公告显示，2026 年 6 月 22 日起停止新建工作区和新用户注册，Firebase Studio 将于 2027 年 3 月 22 日停止服务，Google 将能力迁移到 Google AI Studio 和 Google Antigravity。因此，对标对象应写成“Google AI Studio + Firebase / Antigravity 生态”，不应继续把 Firebase Studio 当作长期稳定产品。

与我们的差距：我们能够生成 Vue 工程并在服务端构建，但没有一键为生成应用配置数据库、认证、密钥管理和第三方 API 的产品化能力。

来源：[Google AI Studio 全栈开发](https://ai.google.dev/gemini-api/docs/aistudio-fullstack)、[Firebase Studio 迁移公告](https://firebase.google.com/docs/studio/migrating-project)。

### 4.2 Vercel v0：视觉生成、运行时、Git 和项目协作

v0 以生成 UI 和全栈 Web 应用为核心，支持 Prompt、截图和 Figma 输入；Design Mode 可以在运行中的预览里选中元素、调整样式并将修改写回代码。官方文档还提供数据库集成、GitHub 导入、项目级文件系统、环境变量和多聊天协作等能力。

v0 的关键优势是把三个环节放在同一条链路上：

- 设计输入和视觉修改；
- 真实 Node.js / Next.js 运行时和数据库连接；
- GitHub、项目、分支和部署协作。

我们的点选编辑已经具备相似交互，但只会生成定向 Prompt，没有完整的属性面板、代码编辑器、项目文件系统和 Git 分支协作。

来源：[v0 Design Mode](https://v0.app/docs/design-mode)、[v0 Quickstart](https://v0.app/docs/quickstart)、[v0 Projects](https://v0.app/docs/projects)、[v0 Git Import](https://v0.app/docs/git-import)。

**适用边界**：Design Mode 要求支持的预览运行时和当前版本，且不支持只读聊天及移动视口；数据库属于服务集成，不能写成 v0 自建数据库。其全栈默认路径偏 Next.js，不应作为我们必须更换 Vue 技术栈的理由。[Design Mode 限制](https://v0.app/docs/design-mode)、[全栈支持](https://v0.app/docs/full-stack-apps)

### 4.3 Figma Make：设计协作向可运行应用延伸

Figma Make 将设计文件、生成代码和运行应用放在同一协作空间。官方帮助文档显示，Make 可以通过对话生成 Payload 后端，包含数据库、文件存储、认证和管理后台，也可以连接 Supabase；Figma 还提供发布、模板化复用和团队协作路径。

它代表设计工具进入应用生成市场的路线：先从高保真视觉和协作切入，再补后端、数据和发布。对我们最有价值的参考是 **截图 / 设计稿导入、资产管理、可视化编辑、多人评审和模板复制**。

来源：[Figma Make 后端能力](https://help.figma.com/hc/en-us/articles/32640822050199-Add-a-backend-to-a-functional-prototype-or-web-app)、[Figma Make 应用生成](https://www.figma.com/solutions/ai-app-builder/)、[Figma Make 发布](https://help.figma.com/hc/en-us/articles/31304586129559-Publish-update-or-unpublish-a-Figma-Make-file)。

**适用边界**：Make 的 Supabase 集成文档说明其当前使用键值存储，不应笼统等同于完整 SQL 建模设计器。本文把后端能力记为“原生生成或外部集成”，没有验证其对复杂企业权限的支持程度。[后端说明](https://help.figma.com/hc/en-us/articles/32640822050199-Add-a-backend-to-a-functional-prototype-or-web-app)

### 4.4 Microsoft Power Apps：AI 生成企业解决方案

Power Apps 的 AI 能力不是只生成一个页面，而是根据自然语言生成一套 Power Platform 解决方案，包含 Dataverse 表、Canvas / Model-driven App、Power Automate 流程、Power Pages 站点和 Copilot Studio Agent。官方文档同时提供环境管理、连接器、自定义 API、虚拟表、Webhooks、嵌入和按表 / 记录级别的角色安全。

这类平台与我们的差距不主要在 UI，而在企业应用的底座：统一数据模型、流程自动化、连接器、环境、治理和权限。它适合作为我们未来企业版的上限参考，不适合作为当前版本的全部建设范围。

来源：[Power Apps Maker 文档](https://learn.microsoft.com/en-us/power-apps/maker/)、[Power Apps 概览](https://learn.microsoft.com/en-us/power-apps/powerapps-overview)、[Power Platform 开发者介绍](https://learn.microsoft.com/power-platform/developer/get-started)。

**适用边界**：上述是 Power Platform 组合能力，Power Pages、Power Automate、Copilot Studio 并非所有 Power Apps 许可默认包含。跨环境交付有专门的 Solutions 和 Pipelines，不应只因工具支持“发布”就判断为同等生命周期治理。[Solutions](https://learn.microsoft.com/power-apps/maker/data-platform/solutions-overview)、[Pipelines](https://learn.microsoft.com/power-platform/alm/pipelines)

### 4.5 Google AppSheet：数据驱动、离线和自动化

AppSheet 是 Google 体系中更成熟的零代码业务应用平台。官方定价页列出表格、图库、图表、地图、仪表盘、表单、签名、照片、离线同步、登录和用户管理等能力；Gemini for App Creation 可以根据自然语言生成建议的数据表、字段、视图和动作，Gemini 也能在自动化中处理图片、PDF 和文本分类。

AppSheet 对我们的启示有两点：一是业务应用的最低能力包含表单、数据视图、离线和行级安全；二是 AI 不只生成界面，还要生成数据结构和自动化步骤。我们当前可以生成类似页面，但没有平台级离线同步、行级安全和自动化运行时。

来源：[AppSheet 定价与能力](https://about.appsheet.com/pricing/)、[Gemini in AppSheet](https://support.google.com/appsheet/answer/16106353?hl=en)、[AppSheet 安全过滤器](https://support.google.com/appsheet/answer/10104488?hl=en)。

### 4.6 AWS App Studio：自然语言生成企业内部应用

AWS App Studio 将自然语言生成定位到库存管理、理赔处理和复杂审批等企业场景，强调生成应用由 AWS 托管、具备安全和运维能力。官方 FAQ 说明可通过连接器接入 200 多项 AWS 服务；首次配置使用 IAM Identity Center 用户组管理访问。

这说明国际大厂的 AI 应用生成产品通常从“企业数据和连接器”切入，而不是只比较代码输出长度。我们当前缺少连接器中心、密钥托管、组织访问和运行态运维。

来源：[AWS App Studio 概览](https://docs.aws.amazon.com/appstudio/latest/userguide/welcome.html)、[AWS App Studio FAQ](https://aws.amazon.com/appstudio/faqs/)、[用户访问配置](https://docs.aws.amazon.com/appstudio/latest/userguide/setting-up.html)。

**当前产品线补充**：AWS 官方公告说明 Amazon Quick 的自然语言应用构建能力从 **2026-09-01** 起面向 Plus、Professional、Enterprise 客户提供。它以连接业务数据、构建和共享应用为入口，应与 App Studio 一并跟踪。本次未发现官方依据证明 App Studio 已被 Quick 替代，因此分别列出，不写成停服或改名。[Amazon Quick 官方公告](https://aws.amazon.com/about-aws/whats-new/2026/09/amazon-quick-custom-apps-natural-language/)

## 五、能力矩阵

下表按可核查的能力比较，不给不同产品合并打分。竞品列只列代表证据，不表示未列出的厂商不具备该能力。

| 能力 | 本项目状态与证据 | 代表竞品的产品化能力 | 差距判断 |
| --- | --- | --- | --- |
| 多轮生成、预览、下载 | 已有生成与交付路径，L02、L07 | 秒哒支持迭代和源码导出，v0 支持生成与导出，见 3.1、4.2 | 基础闭环已有；质量领先与否待同题实测 |
| 真实数据 / CRUD | 未发现生成应用的数据管理入口，L01、L10 | 秒哒数据库维护、微搭数据模型、AppSheet 表与视图 | 核心应用能力缺失 |
| 生成应用认证 / 数据权限 | 只有平台 user/admin 和所有权，L01、L10 | 秒哒用户体系、AppSheet 行级过滤、Dataverse 记录权限 | 核心应用能力缺失 |
| 样式和内容直接编辑 | 点选后发 Prompt，L04 | 秒哒部分应用 GUI、v0 属性编辑 | 已有定位能力，缺确定性的直接修改和撤销 |
| 文件树 / 真实代码查看 | 展示 AI 消息提取内容，L05 | v0 Git Import 提供项目文件和运行时 | 当前“代码”视图不足以查看完整 Vue 项目 |
| 版本 / 回滚 | 已有草稿和成功版本保护，缺用户操作，L03 | 秒哒开发后端版本快照；v0 项目与 Git | 需要产品化；代码恢复与数据恢复应分开 |
| 数据环境 / 持续发布 | 有独立静态部署目录，L07；无业务数据环境 | 秒哒多环境、Power Platform Pipelines | 静态目录隔离不能替代数据环境隔离 |
| API / 密钥 | 未发现用户可配置闭环，L10 | Google AI Studio 服务端密钥、宜搭连接器、AWS 连接器 | 缺凭据、连接配置和调用观测 |
| 业务审批 / 定时自动化 | 固定生成图，L08 | 宜搭流程、AstroZero 自动化、AppSheet 自动化 | 是另一类运行时，当前未实现 |
| 模板与复制 | 有精选展示，无模板安装 / 复制入口，L10 | 飞书方案中心、微搭模板 | 可从可复制示例开始，无需先建市场 |
| 输入素材 / 设计稿 | 主要是文本，L04 | v0 截图 / Figma 输入，秒哒图片修改 | 缺用户资产输入与稳定引用 |
| 多人协作 | 以所有者为中心，L01、L10 | 微搭多人协作、飞书组织集成 | 缺项目共享和角色，审计需单独设计 |
| 自动质量验收 | 构建、资源、挂载与部分控制台检查，L06 | 秒哒基于需求的浏览器功能质检 | 缺可复现业务路径，不能算完全没有检查 |
| 用量 / 成本 | 已有部分 Token 汇总，L09 | AppSheet 区分许可与 AI 用量；秒哒区分生成和后端资源 | 缺长期账本、费用映射和预算，不是从零采集 |
| 多端 / 离线 | Web 加视口模拟，L04、L10 | 微搭 Web/H5/小程序；AppSheet 离线同步 | 真正小程序交付、离线一致性仍缺失 |
| 安全运行边界 | 本机构建 / 浏览器进程，L11 | 本次未实测竞品底层隔离机制 | 我方应专项验证，不根据营销页面推断竞品实现 |

由这些证据可以得出：当前缺口集中在真实业务运行、确定性修改与持续交付。不能据此得出与大厂相差某个百分比，或需要多少人月才能追平。

## 六、我们的核心差距清单

### P0：直接影响“能否把应用交付给真实用户”

这里的 P0 是调研建议：第 1、4 项以“承接有真实数据的业务应用”为前提；第 2、3 项对现有前端工作台也有直接价值。它不是已有 `p0-reliability` Release 的追加范围。

#### 1. 生成应用缺少可配置后端运行时

当前系统的数据库只保存平台用户、应用和聊天历史，生成项目主要是 HTML / 多文件 / Vue 前端。用户要做 CRM、问卷、预约、商城或管理后台时，仍需要自己接数据库和 API。

建议补齐最小可用运行时：

- 结构化数据表 / 字段 / 关系；
- CRUD API 或受控数据 SDK；
- 文件上传和对象存储；
- 生成应用的登录、用户资料和基础角色；
- 生成代码时同步写入环境变量和服务端密钥引用。

优先级最高的原因是：没有数据和认证，很多生成结果只能演示，无法成为真正可运营的应用。

源码依据：L01、L10。最低验证样例是“报名表提交后，换浏览器仍能查看记录；普通用户只可读取自己的记录；管理员可管理全部报名”。模型生成的假数据或 `localStorage` 演示不能算通过。初期可以评估受控 BaaS 集成或轻量业务后端，尚不决定自研数据库平台。

#### 2. 缺少用户可见的版本、环境和回滚

当前已有任务级快照、独立草稿、成功版本指针和旧版本保护，这解决了生成失败时不污染线上预览的问题，但用户看不到版本列表，也不能将某个版本标记为开发版 / 测试版 / 生产版，更不能用一键回滚处理线上问题。

建议建立最小发布模型：

- `draft`、`preview`、`published` 三种状态；
- 每次成功生成形成可追溯版本，记录 Prompt、模型、文件摘要和检查结果；
- 发布前展示差异和检查报告；
- 支持回滚到最近稳定版本；
- 后续再扩展开发 / 测试 / 生产环境和增量发布。

源码依据：L03、L07。现有 `published` 指针指向通过交付检查的生成产物，和用户点击“部署上线”后复制的静态发布目录是两个层次，不能直接当成完整的生产版本模型。最低验收应覆盖：连续生成 A/B/C，选择 B 恢复，预览和下载一致；发布失败不覆盖旧线上版本。引入数据库后，代码回滚不得隐式回滚或删除线上业务数据。

#### 3. 缺少面向应用的自动化测试与运行诊断

当前已经有代码完整性检查、Vue 构建、浏览器挂载检查和部署封面截图，但主要回答“代码能否构建、页面能否加载”，还不能回答“核心业务功能是否按需求工作”，也不能提供版本间的视觉 / 行为差异报告。

建议增加基于需求的测试 Agent：

- 从初始需求提取可执行验收场景；
- 在预览环境中模拟点击、输入、提交、登录和关键路径；
- 采集控制台异常、网络失败、截图和关键状态；
- 输出失败步骤和可回放的测试记录；
- 允许用户确认后自动修复，再次执行同一场景。

这会把当前的“构建可靠性”提升为“功能可靠性”。

源码依据：L06、L07。建议先建立固定场景集及确定性断言，再考虑开放式测试 Agent。现有质检模型和浏览器挂载检查可继续复用，不需要重新实现一套。最低验收是对已知的无效按钮、无法提交表单和移动端溢出样例给出可复现失败证据，而不只是模型的“通过”结论。

#### 4. 缺少通用连接器与密钥管理

当前工作流里的图片搜索是平台内部工具，不能让用户配置“调用一个 CRM API”“接入支付”“提交表单后发企业微信通知”这类业务动作。

建议先提供四类连接能力：

- HTTP / REST 连接器；
- Webhook 触发器；
- 常用数据库或 BaaS 连接器；
- 消息、邮件和对象存储连接器。

同时要有项目级密钥、权限范围、调用日志和失败重试，否则连接器会成为新的安全风险。

源码依据：L08、L10。最低验收是让一个表单通过服务端连接器访问受控测试 API，密钥不出现在浏览器源码和下载包，失败能查到脱敏日志。写入型调用要设计幂等与重复提交处理，不能统一盲目重试。

### P1：决定产品能否从个人工具扩展到团队使用

#### 5. 缺少团队空间、成员角色和协作审阅

现在应用以用户所有者为中心，管理员主要是平台管理角色。需要补充工作空间、成员邀请、项目角色、应用共享、评论 / 任务、只读预览和审阅记录。团队能力应与应用数据权限分开设计，不能把平台管理员直接等同为业务超级管理员。

#### 6. 缺少真正的代码协作和外部仓库连接

ZIP 下载能完成一次性交付。更直接的当前问题是：代码面板读取最近 AI 消息的首个代码块，不展示成功工作区的完整文件，Vue 工程文件甚至可能不在消息中。应先提供真实文件树、只读源码和改动差异，再根据开发者需求接入 GitHub / GitLab 导入导出、分支和协作。[L05、L07]

#### 7. 缺少模板中心、组件库和应用复制

当前精选广场能展示应用，但还没有模板安装、复制后脱离原作者独立演进、组件复用和版本依赖。建议将广场升级为三层资产体系：应用模板、UI / 业务组件、可调用 Skill / 连接器，并记录版本、来源、许可证和兼容范围。

#### 8. 缺少多端发布能力

当前有桌面 / 平板 / 手机视口模拟，但这属于预览适配，不等于发布到小程序或原生 App。若目标用户明确需要微信内交付，先评估一条小程序路径；若主要是浏览器网站，应先验证 PWA 是否有实际需求，再考虑原生打包。多端发布必须同时考虑登录、支付、文件上传、审核和运行环境差异。[L04、L10]

#### 9. 缺少运行态监控和产品分析

当前有健康检查和部署封面截图，没有生成应用的访问量、错误率、接口耗时、用户行为和版本健康度。没有这些信息，发布后无法判断应用是否可用，也无法为 AI 自动修复提供生产反馈。

### P2：提高效率和商业化能力

#### 10. 缺少截图 / 设计稿 / 文件作为首轮输入

当前主要输入是文本 Prompt，工作流会收集图片素材，但还没有完整的截图、Figma、需求文档和本地文件导入链路。设计到代码竞品已经把截图和 Figma 作为标准入口，建议补充图片上传、OCR / 视觉结构解析和文件上下文索引。

#### 11. 缺少多模型与成本治理

当前以 OpenAI 兼容接口为主，任务对象已经记录调用次数和部分 Token 汇总，但缺少用户可见的模型选择、按任务路由、价格换算、项目预算、超额保护和模型效果对比。需要把“模型调用”从配置项升级为平台资源管理能力。

#### 12. 缺少 SEO、域名、分享和运营工具

当前部署复制成功产物并返回链接，未发现域名配置、证书状态、发布访问控制、独立下线与回收站等产品入口；这不等于部署环境一定没有 HTTPS。生成代码可能含 SEO 标签，也不等于平台能配置、校验和持续管理 SEO。[L07、L10] 秒哒的更新记录可用于参考 SEO、应用回收站等运营入口，不能据此推断我们必须立即实现付费投放。[秒哒更新日志](https://cloud.baidu.com/doc/MIAODA/s/Zmm32qp8x)

### 补充：不能被“已有沙盒 / 工作流”名称掩盖的差距

#### 13. 面向公开多用户的执行隔离尚无充分证据

`VueProjectBuilder` 使用本机 `ProcessBuilder` 执行构建，`npm install --ignore-scripts` 是已有保护，但随后仍执行项目提供的 build 脚本；`PreviewVerifier` 使用带 `--no-sandbox` 参数的本地 Chrome。任务工作目录隔离不能直接证明 CPU、内存、进程、网络和环境变量隔离。静态资源 Controller 也没有可配置的私密预览或过期分享校验入口。[L11]

这是代码层面的边界识别，本次没有做攻击验证，也未检查外部网关或宿主容器配置。若计划公开承接多用户生成，应把构建 / 浏览器隔离、资源额度和预览访问策略列为上线前的 P0；仅本地自用时可作为后续工程事项。最低验证包括任务间文件不可互读、构建不能读取平台密钥、资源超额可终止、私密预览拒绝未授权请求。

#### 14. 确定性的可视化修改尚未形成闭环

已有点选功能是“定位元素 → 补充 Prompt → 再生成”，不是“选中 → 直接改文字 / 颜色 / 间距 → 源码更新 → 撤销”。缺少结构化属性面板、稳定的元素到源码映射和用户操作级撤销。[L04] 短期可只支持文本、图片和少量样式属性，要求修改后刷新、构建、下载仍保持一致；不要先建设通用拖拽设计器。对现有工作台，这项可提高到 P1。[v0 直接视觉编辑](https://v0.app/docs/design-mode)

#### 15. 业务流程、报表与离线是独立的产品方向

未发现可供用户保存和执行的审批定义、定时任务、Webhook 触发器、流程实例、报表数据源或离线同步服务。[L01、L08、L10] 生成一个流程图、仪表盘页面或依赖浏览器存储的待办列表，均不能替代这些运行能力。只有进入团队业务应用方向后，才建议先做“表单提交 → 通知”或“定时提醒”这一条最小链路；完整审批、BI 与离线冲突合并放后。[宜搭自动化](https://developers.aliwork.com/docs/guide/concept/connector)、[AppSheet 同步机制](https://support.google.com/appsheet/answer/10108301?hl=en)

## 七、优先级建议

优先级取决于产品是否要从“AI 前端生成工作台”扩展为“AI 业务应用平台”。如果暂时保持前端工作台定位，先做版本、文件树、素材输入、截图对比和发布治理；如果确认要承接 CRM、问卷、预约等真实业务场景，才进入下面的运行时优先路线。数据、认证、连接器和环境隔离会改变架构与安全边界，应单独通过产品和技术决策门禁。

### 第一阶段：把“生成前端”变成“可运行应用”

目标是让用户能生成一个带真实数据的可登录应用，而不是只生成静态页面。

1. 建立生成应用的最小数据层：表、字段、记录、文件。
2. 生成应用支持基础认证和简单角色。
3. 增加项目级密钥和 HTTP 连接器。
4. 将数据库 / 认证 / 连接器配置纳入生成提示和代码模板。
5. 保留当前 HTML / 多文件模式，Vue 模式优先接入运行时。

### 第二阶段：把“能运行”变成“可发布、可恢复”

1. 用户可见版本列表、差异和发布记录。
2. 草稿、预览、生产三态和一键回滚。
3. 需求驱动的浏览器测试 Agent、截图和控制台日志。
4. 自定义域名、访问控制、部署健康检查和错误日志。
5. GitHub 导出 / 导入和环境变量管理。

### 第三阶段：把“单人工作台”变成“团队平台”

1. 工作空间、成员、项目角色和应用共享。
2. 模板、组件、Skill、连接器市场。
3. 小程序 / PWA 等明确的多端发布路径。
4. 使用量、Token、费用和模型效果分析。
5. 审计日志、数据隔离、私有化和企业 SSO。

### 依赖、投入和最小验收

以下投入表示相对复杂度，不是工期估算：小＝复用现有单一能力；中＝新增前后端闭环；大＝引入新数据、权限、运行时或跨环境契约。

| 候选项 | 建议顺序 / 触发条件 | 投入 | 主要依赖 | 能证明有价值的最低结果 |
| --- | --- | --- | --- | --- |
| 真实文件树与文件差异 | 当前定位即可先做 | 中 | 成功工作区、只读文件接口和权限 | Vue 的全部源码与 ZIP 一致，能查看本次改动 |
| 版本列表与恢复 | 当前定位即可先做 | 中 | 现有任务工作区、版本保留策略 | A/B/C 中恢复 B，失败不破坏已发布版本 |
| 素材上传与视觉输入 | 当前定位即可先做 | 中 | 文件存储、任务附件引用 | 指定 Logo 被正确使用，后续修改不会丢失 |
| 有限属性编辑 | 前三项后 | 中至大 | 稳定源码映射、版本 | 改色 / 改文案可撤销，源码和预览一致 |
| 固定场景功能回归 | 与以上能力持续建设 | 中 | 可重放需求、受控浏览器 | 能发现预置交互缺陷，保存失败步骤 |
| 数据、认证与权限 | 决定承接真实业务应用后 | 大 | 运行时方案、身份边界和数据模型批准 | 跨浏览器持久化，A 用户无法读取 B 的记录 |
| 执行隔离与私密预览 | 公开多用户上线前 | 大 | 部署架构、资源与网络边界 | 隔离验证通过，私密预览拒绝未授权访问 |
| HTTP 连接器与密钥 | 应用运行时方案确定后 | 中至大 | 服务端执行、密钥与项目权限 | 表单接入测试 API，不泄露密钥，重复提交可控 |
| 自定义域名、下线与监控 | 对外网站交付需求明确后 | 中至大 | 部署服务、域名 / 证书和观测系统 | 自有域名可访问；下线与故障可观测 |
| 团队、业务自动化、多端 | 前面场景验证成立后 | 大 | 租户、运行时和持续交付 | 至少一个多人业务场景可持续运行 |

前端工作台路径和业务应用路径都需要用户需求验证。本文不直接确定供应商、不迁移 Schema、不增加付费服务，也不改变现有 Release 状态。

## 八、暂不建议优先建设的能力

以下能力在竞品中确实存在，但不应在当前阶段直接复制：

- 完整的拖拽式 BPMN / 表单设计器；
- 覆盖所有行业的报表、门户和大屏组件；
- 同时支持 iOS、Android、微信小程序、支付宝小程序和鸿蒙原生打包；
- 完整的企业计费、ISV 多租户和复杂组织树；
- 大规模组件市场和商业化开发者生态。

这些能力会把产品带入传统企业低代码平台的长期竞争，显著增加数据模型、权限、安全、运维和售后成本。当前更合理的路线是：先用 AI 生成和真实代码交付建立差异，再以可插拔运行时、连接器和发布治理逐步向企业应用延伸。

## 九、建议跟踪指标

仅有“生成成功”不足以判断产品是否接近竞品，建议从以下指标建立产品基线：

| 指标 | 建议定义 |
| --- | --- |
| 首次生成可用率 | 首次生成后无需人工改代码即可完成核心路径的应用占比 |
| 需求到发布时长 | 从首次 Prompt 到第一个可分享生产链接的中位时间 |
| 真实功能通过率 | AI 测试 Agent 验证通过的核心验收场景占比 |
| 生成后人工改码率 | 用户下载后必须手工修改代码才能上线的占比 |
| 发布回滚率 | 发布后因功能或运行问题回滚的版本占比 |
| 连接器成功率 | 外部 API / 数据库调用的成功率和 P95 延迟 |
| 资产复用量 | 模板、组件、Skill 被复制或复用的次数 |
| 生成成本 | 单个成功应用的模型 Token、构建和浏览器检查成本 |
| 7 日留存 | 首次生成后 7 天内再次修改或发布的用户比例 |

## 十、后续同题实测建议

公开文档足以确认能力范围，不能比较最终生成质量。若继续做产品选型或路线决策，建议用相同需求分别测试本项目、秒哒、Google AI Studio 和 v0；企业流程场景另选宜搭或 AppSheet，不把两类产品混在一个总分中。

| 场景 | 必须观察的结果 |
| --- | --- |
| 带指定 Logo 的中文企业官网 | 素材是否正确、手机是否溢出、导航和 CTA 是否可用 |
| 三轮修改已有页面 | 只改指定内容，原有交互和其他页面保持可用 |
| 预约 / 活动报名 | 跨浏览器仍有记录，必填校验和重复提交正确 |
| 双角色任务管理 | 普通用户只能访问自己的任务，管理员操作有效 |
| 发布失败与版本恢复 | 失败后旧链接仍可用，恢复版本与源码下载一致 |
| 提交后发通知 | 密钥不暴露，超时可追踪，重试不会重复执行业务动作 |

建议每场景至少重复 3 次，固定输入附件、账号套餐、允许修改轮次和总时限；记录产品 / 模型版本、首次可用耗时、实际费用、失败原因和核心路径证据。3 次仅作探索样本，不足以宣称统计显著。未支持场景记为“不支持”，基础设施故障、模型生成失败与用户操作错误分别记录。本次未执行上述实测，报告中的指标均为建议口径，没有测量值。

## 十一、研究判断与限制

我们已有任务、构建、预览和失败保护机制，短期可以围绕这些基础改善持续修改体验。数据、认证和外部连接则决定能否进一步承接业务应用；团队、自动化和多端能力应由具体用户需求推动。上述是基于代码和官方资料的研究判断，不是生成效果排名。

最应该优先验证的产品假设是：**用户是否愿意用我们的自然语言生成能力，继续完成一个带真实数据和登录的应用，并把它发布给真实用户使用。** 如果这个假设成立，后续的版本治理、连接器、协作和多端发布都有明确价值；如果不成立，继续扩展企业低代码能力只会增加复杂度，无法改善核心留存。

本次仅做公开资料调研、源码核查和文档检查，没有启动业务服务、调用付费模型、实施新功能或改变发布状态。未提交修复的线上可用性、真实模型成功率、竞品套餐权益与部署后的安全隔离仍需按各自环境验证。文档检查结果保存于 `.evidence/competitor-research-20261022-validation.log`；它只证明文档结构和本地引用检查，不是产品验收证据。
