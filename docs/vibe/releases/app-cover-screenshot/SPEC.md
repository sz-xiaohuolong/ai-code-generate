# Effective SPEC — app-cover-screenshot

| 范围 | 冻结的产品事实 | 待澄清/边界 | 验收入口 |
|---|---|---|---|
| Release `app-cover-screenshot` | 项目生成与部署完成后，自动异步使用 Selenium 截取真实首屏并更新应用封面 | 历史存量数据暂不自动全量批刷 | [REQ-001](#req-001--代码生成完成后自动异步截图封面) / [REQ-002](#req-002--应用部署成功后刷新封面) |
| 核心能力 | 真实页面首屏截图、静态 URL 托管、卡片真实渲染 | 截图执行超时 10 秒上限 | [AC-001](#ac-001) / [AC-002](#ac-002) / [AC-003](#ac-003) |
| 异常与边界 | 截图失败静默记录 warn 日志，保留现有线框兜底，主流程正常返回 | 本地无 Chrome 驱动时不阻断服务 | [ERR-001](#err-001) |

## Control

- Release: `app-cover-screenshot`
- Requirement Version: `1`
- Requirement Baseline: [PROJECT_BRIEF.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/vibe/releases/app-cover-screenshot/PROJECT_BRIEF.md)
- Status: `FROZEN`

## Product Behaviors

### REQ-001 — 代码生成完成后自动异步截图封面

- Source: `PROJECT_BRIEF §Core Scenario`
- Actors: 核心生成管线 (`AiCodeGeneratorFacade` / `CodeGenWorkflow`)
- Preconditions: 代码成功生成，本地目录文件落盘完毕（Vue 项目需本地 npm build 成功产出 dist）。
- Trigger: 代码生成流结束或工作流构建成功。
- Expected Behavior:
  1. 系统异步触发无头 Chrome 访问该应用的静态预览地址（`http://localhost:8123/api/static/{codeGenType}_{appId}/index.html`）。
  2. 窗口分辨率为 1280x800，等待页面加载与 JS 渲染（默认 2 秒）。
  3. 捕获视口截图，写入本地 `tmp/cover/app_{appId}.png`。
  4. 更新数据库表 `app` 的 `cover` 字段为 `/api/static/cover/app_{appId}.png?v={timestamp}`。
  5. 主动清除该应用相关的精选列表 Redis 缓存。
- Resulting State: 数据库 `app.cover` 有效更新，前端刷新后展示真实封面。
- Explicit Non-goals: 阻塞等待截图完成才返回 SSE 流结束事件。

#### Acceptance Criteria

- AC-001: 代码生成完成后，`tmp/cover/app_{appId}.png` 文件成功落盘，且 `app.cover` 指向该静态路径。

---

### REQ-002 — 应用部署成功后刷新封面

- Source: `PROJECT_BRIEF §Core Scenario`
- Actors: `AppServiceImpl.deployApp`
- Preconditions: 用户触发一键部署，代码已复制至 `tmp/code_deploy/{deployKey}/`。
- Trigger: 部署成功写入 `deployKey` 并即将返回部署 URL。
- Expected Behavior:
  1. 异步触发无头 Chrome 访问正式部署访问地址（`http://localhost:8123/api/static/{deployKey}/index.html`）。
  2. 捕获最新正式页面截图，覆盖保存为 `tmp/cover/app_{appId}.png`。
  3. 更新 `app.cover` 并在 Redis 中清理缓存。
- Resulting State: 封面图更新为正式部署版本的截图。

#### Acceptance Criteria

- AC-002: 调用部署接口后，封面文件被更新，应用封面在首页展示正式部署内容。

---

### REQ-003 — 静态封面访问与前端展现

- Source: `PROJECT_BRIEF §Core Scenario`
- Actors: 静态资源控制器 (`StaticResourceController`)、前端应用卡片 (`AppCard.vue`)
- Trigger: 浏览器请求封面图片 `/api/static/cover/app_{appId}.png`。
- Expected Behavior:
  1. 后端正确识别并返回 `image/png` 媒体流。
  2. 前端 `AppCard.vue` 判断 `app.cover` 存在，优先渲染真实图片，不再显示默认线框图。

#### Acceptance Criteria

- AC-003: 静态资源接口对 `/api/static/cover/**` 返回 HTTP 200 及有效 PNG 字节；前端卡片展示该背景图。

#### Error Scenarios

- ERR-001: 当浏览器由于网络超时、JS 语法错误崩溃或 Chrome 未安装导致截屏抛出任何异常时，系统记录 `log.warn`，不向上抛出，保留 `app.cover` 为原值（或 null），前端自动保持 `blueprint-canvas` 几何线框图兜底，主流程正常运行。

#### Boundary Conditions

- BND-001: 截图等待时间硬上限 10 秒；图片视口建议为 1280x800，保持 16:10 / 16:9 比例适配前端卡片。

## 验收索引

| REQ | AC | Source |
|---|---|---|
| REQ-001 | AC-001 | PROJECT_BRIEF §Acceptance Goals GOAL-001, GOAL-002 |
| REQ-002 | AC-002 | PROJECT_BRIEF §Acceptance Goals GOAL-002 |
| REQ-003 | AC-003 | PROJECT_BRIEF §Acceptance Goals GOAL-003, GOAL-004 |

## Limitations & Disclaimers

- 本地执行需要操作系统支持 Chrome Headless。若在容器中运行，需保证容器内预装 chromium 或相应依赖。
