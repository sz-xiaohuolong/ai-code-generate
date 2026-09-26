# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
- **开发者与技术学习者**：希望直观体验大模型全栈代码生成、Agent 工作流（LangGraph4j 图编排、工具调用逐文件落盘、质检构建闭环）与多轮流式对话生成的工程师与学习者。
- **Web 创作者与独立开发者**：希望通过一句话自然语言快速原型化可交互、可直接预览、可下载、可一键部署的单页 HTML、多文件或完整 Vue 3 Web 应用的用户。

## Product Purpose
输入一句话，AI 从零写出一个可运行、可预览、可部署、可下载的前端项目。
核心价值在于解决传统“大模型仅输出代码文本片段，需要人工复制粘贴、手动配置环境与运行”的断点问题，打造从自然语言创意到线上可访问 Web 应用的端到端全自动闭环。
成功标志：用户输入需求后，系统高成功率自动完成代码生成/构建，用户可在工作台中无缝进行多端视口预览、可视化局部点选微调、一键打包下载源码并快速部署上线。

## Positioning
基于真实大模型工程化体系（LangGraph4j 图状态机 + LangChain4j 声明式动态代理 + Reactor SSE 全链路流式）的全自动前端代码生成平台。
与市面上普通“对话式代码补全”或“仅单文件静态页面生成”工具相比，平台具备：
1. **多生成模式自适应**：支持原生单页 HTML、原生多文件工程以及完整 Vue 3 + TypeScript 工程化项目生成；
2. **Agent 闭环能力**：内置图片搜索收集、提示词增强、5 种文件读写工具调用、代码质量自动检查与打回重试、以及服务端 npm install + build 自动构建流程；
3. **沉浸式交互工作台**：支持 SSE 逐 Token 实时打字机渲染、工具调用进度透明化、可拖拽分栏、桌面/平板/手机多端拟态预览与 iframe 可视化点选反选编辑。

## Operating Context
- **前端工作台**：Vue 3.5 + TypeScript + Vite + Ant Design Vue 现代响应式 Web 端，核心工作流为“首页灵感生成/精选广场 → 工作台对话生成 → 实时多端预览/代码查看 → 可视化微调/对话迭代 → 部署发布或源码下载”。
- **后端服务**：Spring Boot 3.5 (Java 21)，运行于 8123 端口（`/api`），负责 Controller / Service / Core（Facade、Parser、Saver、Builder）/ AI（LangChain4j、LangGraph4j）以及 SSE 流式推送。
- **存储与缓存**：MySQL 8.x（持久化 user、app、chat_history，复合索引支撑游标分页），Redis（分布式 Session 30 天与对话多轮记忆缓存）。
- **外部环境**：OpenAI 兼容的大语言模型（Chat / Streaming / Reasoning）、Node.js/npm 本地构建环境。

## Capabilities and Constraints
- **核心能力**：
  - 应用（App）CRUD 管理与用户权限隔离体系；
  - SSE 流式代码生成与多轮对话记忆（DatabaseLoadingChatMemoryStore：DB 事实源 + Redis 缓存一致性）；
  - 原生 HTML / 原生多文件 / Vue 3 工程三种模式自适应路由；
  - 静态资源实时托管预览（`/api/static/**`）与多端设备视口模拟（Desktop / Tablet / Mobile）；
  - iframe 跨域安全通讯协议的可视化元素点选高亮与定向 Prompt 注入；
  - 一键部署生成独立访问链接，一键打包 zip 源码下载；
  - LangGraph4j 6 节点有状态工作流编排（图片素材收集 → 提示词增强 → 智能路由 → 代码生成 → 质检重试 → 项目构建）。
- **硬性约束**：
  - **前端完全适配现有后端接口**：严禁随意更改后端已有接口契约与数据结构，确保前端变更与现有 Spring Boot 服务保持 100% 稳定兼容；
  - **技术栈一致性**：前端必须基于 Vue 3 + TypeScript + Ant Design Vue 规范开发，保持 Design Tokens 一致与高阶审美；
  - **安全与限流**：严格遵循分布式令牌桶限流与输入安全护栏（防 Prompt 注入、防超长）。

## Brand Commitments
- **产品名称**：AI-Code-Generate
- **品牌调性**：专业、极简、高密度、现代化全栈开发工作台；
- **视觉世界**：克制、精致的现代深浅模式与专业工程质感，拒绝廉价感模板化堆砌。

## Evidence on Hand
- **代码仓库**：
  - 前端：`ai-code-frontend/`（已配置现代化 Design Tokens、响应式工作台、可拖拽分栏与多端视口外壳）；
  - 后端：`src/main/java/com/xhl/aicodegenerate/`（完整 Spring Boot + LangChain4j + LangGraph4j 实现）；
  - 数据库与文档：`sql/create_table.sql`，`README.md`，`docs/` 架构与设计文档。

## Product Principles
1. **端到端闭环优先**：从自然语言输入到可运行代码、实时预览、一键部署，始终保持零断点的完整闭环体验。
2. **前后端接口严格契约**：以既有稳定后端服务为基准，前端 UI/UX 的升级与重构严格对齐后端已有 REST/SSE 接口规范。
3. **透明可感知的 AI 过程**：拒绝黑盒等待，流式 Token 打字机、工具调用步骤（Thinking / ToolCall / ToolResult）与构建状态必须清晰透明呈现。
4. **所见即所得的高效迭代**：预览与代码随时双向切换，支持可视化点选精准反馈，降低用户二次修改代码的认知负担。

## Accessibility & Inclusion
- 遵循 WCAG 2.1 AA 基础可访问性标准；
- 保障主要交互元素（工作台折叠按钮、视口切换、输入框、提交按钮）具备明确的 ARIA 标签、焦点态可见性及键盘可操作性；
- 确保代码编辑器、高亮区域及预览外壳具备足够的色彩对比度。
