# Implementation Plan — app-cover-screenshot

## Control

- Requirement Baseline: [PROJECT_BRIEF.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/vibe/releases/app-cover-screenshot/PROJECT_BRIEF.md)
- Effective SPEC: [SPEC.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/vibe/releases/app-cover-screenshot/SPEC.md)
- Current Architecture: [docs/architecture-summary.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/architecture-summary.md)
- Proposed Design: [PROPOSED_DESIGN.md](file:///Users/daiyibei/Documents/code/zero-code-generate/ai-code-generate/docs/vibe/releases/app-cover-screenshot/PROPOSED_DESIGN.md)
- Status: `PLANNED`

## Global Constraints

- 只实现当前冻结范围（双触发、渐进式、静默降级）。
- 不破坏现有 SSE 流式生成和部署核心接口契约。
- 截图服务全程异步，任何截屏异常一律捕获并记日志，不影响主流程。

## Slice Map

| Slice | Business Loop | REQ/AC | Dependencies | Exit Evidence | Status |
|---|---|---|---|---|---|
| SLICE-01 | Selenium 依赖集成与静态封面服务 | REQ-003/AC-003 | 无 | Maven 构建通过，静态封面接口单元测试通过 | `NOT_STARTED` |
| SLICE-02 | AppScreenshotService 核心实现与异步线程池 | REQ-001/AC-001, ERR-001 | SLICE-01 | 服务单测通过，能够静默降级并生成本地封面 | `NOT_STARTED` |
| SLICE-03 | 生成管线与部署流程双触发集成与全链路验证 | REQ-001, REQ-002 | SLICE-02 | 生成与部署后成功落盘截图，数据库更新，前端正常展示 | `NOT_STARTED` |

## Tasks

### TASK-001 — Maven 依赖与静态封面资源控制器扩展

- Slice: `SLICE-01`
- Goal: 在 `pom.xml` 中引入 `selenium-java`，并在 `StaticResourceController` 中支持 `/api/static/cover/{fileName}` 路径。
- REQ/AC: `REQ-003 / AC-003`
- Files/Modules: `pom.xml`, `StaticResourceController.java`, `AppConstant.java`
- Planned Test/Evidence: `./mvnw test-compile` 及针对静态资源的单元测试。
- Status: `NOT_STARTED`

### TASK-002 — 实现 AppScreenshotService 异步无头截图服务

- Slice: `SLICE-02`
- Goal: 基于 Selenium 4 Headless Chrome 封装 `AppScreenshotService`，提供异步截图、文件落盘、更新 DB `app.cover` 与清除 Redis 缓存能力，并做好异常静默降级。
- REQ/AC: `REQ-001 / AC-001`, `ERR-001`
- Files/Modules: `AppScreenshotService.java`, `AppScreenshotServiceImpl.java`
- Planned Test/Evidence: `AppScreenshotServiceTest.java`
- Status: `NOT_STARTED`

### TASK-003 — 生成管线与部署流程挂载与端到端验证

- Slice: `SLICE-03`
- Goal: 在 `AiCodeGeneratorFacade`（代码保存与构建完成后）与 `AppServiceImpl.deployApp`（部署完成后）挂载异步截图调用，完成全链路验证。
- REQ/AC: `REQ-001`, `REQ-002 / AC-002`
- Files/Modules: `AiCodeGeneratorFacade.java`, `AppServiceImpl.java`, `CodeGenWorkflow.java`
- Planned Test/Evidence: 运行生成和部署流程，核验 `tmp/cover/` 生成结果与数据库字段。
- Status: `NOT_STARTED`

## Integration and Review Gates

- 单元测试运行无错误。
- 模拟 Chrome 未启动或页面异常时测试静默降级。
- 收集验证证据放入 `.evidence/app-cover-screenshot/`。
