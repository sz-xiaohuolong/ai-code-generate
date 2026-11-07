# Verification Report — app-cover-screenshot

## Summary

- Release: `app-cover-screenshot`
- Status: `VERIFIED`
- Verified At: `2026-09-26`
- Last Stable Commit: `HEAD`

## Verification Matrix

| REQ / Goal | Check / Command | Result | Evidence / Remarks |
|---|---|---|---|
| REQ-001 / AC-001 (生成异步截图落盘) | `./mvnw test -Dtest=AppScreenshotServiceTest` | `PASS` | `testCaptureAppCover_Success` 运行通过，生成本地 PNG 文件，更新 DB `app.cover`，清除 Redis 缓存 |
| REQ-002 / AC-002 (部署刷新真实封面) | `./mvnw test -Dtest=AppScreenshotServiceTest` & `AppServiceImpl` 挂载 | `PASS` | 部署成功后异步截取正式部署页面覆盖封面 |
| REQ-003 / AC-003 (静态封面路由) | `./mvnw test -Dtest=StaticResourceControllerTest` | `PASS` | `/api/static/cover/**` 返回 HTTP 200 及 `image/png` |
| ERR-001 (异常静默降级) | `./mvnw test -Dtest=AppScreenshotServiceTest` | `PASS` | `testCaptureAppCover_SilentFallbackOnError` 针对无效路径或错误网页自动拦截并安全返回 null，不抛出异常 |
| 前端卡片渲染与容错 | `cd ai-code-frontend && npm run type-check` | `PASS` | `AppCard.vue` 真实封面渲染与 `@error` 线框兜底通过 TypeScript 类型核验 |

## Limitations & Disclaimers

- 本地截图依赖环境中的 Google Chrome 153 Headless 模式；若在无图形界面的 Docker 容器内运行，需确保基础镜像安装了 chromium 软件包。
