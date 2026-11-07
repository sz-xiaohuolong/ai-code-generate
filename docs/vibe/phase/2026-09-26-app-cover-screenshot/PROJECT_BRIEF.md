# PROJECT BRIEF — app-cover-screenshot

## Requirement Control

- Requirement Version: `1`
- Requirement Status: `FROZEN`
- Current Release: `app-cover-screenshot`
- Approved By: `用户明确决策确认 (A1 双触发 / A2 渐进式 / A3 静默降级)`
- Approved At: `2026-09-26`

## Problem

当前系统中的应用封面均为预设的矢量科技感线框图（`blueprint-canvas`）或空封面。用户在应用广场（`HomePage.vue`）和管理端无法直观看到由 AI 生成的前端实际网页视觉效果，缺乏所见即所得的真实感。

## Target User

- **普通用户与开发者**：在应用广场浏览各类应用时，能直接看到 AI 生成的真实网页效果图。
- **创作者**：生成或部署应用后，系统自动生成专属真实封面，无需手动上传图片。

## Core Scenario

1. 用户在工作台对话生成网页（HTML / 多文件 / Vue 项目）完成后，或者点击“一键部署”成功后；
2. 系统在后台自动、异步启动 Selenium 无头浏览器截取该网页首屏渲染效果；
3. 截图落盘并更新至应用数据表的 `cover` 字段；
4. 用户在首页卡片和应用列表中实时看到真实的页面截图封面。

## In Scope

- 引入 Selenium 依赖与 Headless Chrome 驱动封装。
- 新增 `AppScreenshotService` 负责异步截图、文件保存与数据库 `app.cover` 更新。
- 触发时机双覆盖：
  - **触发点 1**：代码生成/构建完成（静态预览就绪）；
  - **触发点 2**：应用一键部署完成（正式部署地址就绪）。
- 静态资源支持：扩展 `StaticResourceController`，支持 `/api/static/cover/{fileName}` 访问。
- 渐进式更新策略：仅在新生成或新部署时更新，历史应用暂不批量跑任务。
- 容错保护：截图超时或异常时静默降级并记录日志，绝不影响主响应与正常业务流程。
- 缓存联动：更新封面时主动清理精选应用 Redis 缓存。

## Out of Scope

- 用户自定义上传封面图片的前端裁剪组件。
- 针对历史全部老应用的全量自动化扫描批处理脚本（按需保留后续扩展能力）。

## Constraints

- 操作系统环境：macOS / Linux，依赖已安装的 Chrome 浏览器。
- 必须异步非阻塞，不拖慢核心 SSE 流式输出与部署接口吞吐。

## Acceptance Goals

| Goal ID | 可观察目标 | 验证方式 | 状态 |
|---|---|---|---|
| GOAL-001 | 代码生成落盘后自动异步生成该项目的真实首屏封面图 | 观察 `tmp/cover/` 生成截图文件 | `UNVERIFIED` |
| GOAL-002 | 应用部署成功后自动刷新真实封面图 | 部署后查看 `app.cover` 与静态 URL | `UNVERIFIED` |
| GOAL-003 | 访问 `/api/static/cover/app_{id}.png` 可正确加载图片 | HTTP GET 接口测试 | `UNVERIFIED` |
| GOAL-004 | 前端应用卡片（`AppCard.vue`）优先展示真实截图封面 | 前端页面渲染与视觉核查 | `UNVERIFIED` |
| GOAL-005 | 页面有错误或本地无法拉起 Chrome 时静默降级不抛异常 | 模拟异常测试单测 | `UNVERIFIED` |

## Open Questions

- 无（已全部在决策关卡中闭环确认）。
