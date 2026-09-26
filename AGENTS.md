# Agent Guidelines

本文件为本仓库中各 AI Agent（Claude Code / Antigravity / Cursor 等）的核心引导入口与长期行为准则。

## 事实源地图 (Document Map)

本仓库的唯一权威治理索引为根目录的 [DOCUMENT_MAP.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/DOCUMENT_MAP.md)。进入工程任务前，必须首先按照地图定位当前事实：

1. **当前进度与状态**：优先读取 [docs/vibe/PROGRESS.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/vibe/PROGRESS.md)。
2. **产品基线**：查看 [PRODUCT.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/PRODUCT.md)（宏观定位）以及当前 Release 的有效 SPEC（若有）。
3. **真实系统架构**：查阅 [docs/architecture-summary.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/architecture-summary.md) 与 [docs/langgraph4j-codegen-workflow.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/langgraph4j-codegen-workflow.md)。
4. **历史设计与计划**：查阅 [docs/superpowers/specs/](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/superpowers/specs/) 与 [docs/superpowers/plans/](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/superpowers/plans/)。
5. **历史指引**：沿用 [CLAUDE.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/CLAUDE.md) 中关于代码规范、层级划分与生成管线的技术约定。

## 常用工程命令

### 后端 (Java 21 / Spring Boot 3.5 / Maven)

```bash
# 启动本地服务（默认端口 8123，context-path /api）
./mvnw spring-boot:run

# 运行全量单元测试
./mvnw test

# 运行单类测试
./mvnw test -Dtest=ClassName

# 打包 JAR
./mvnw package -DskipTests
```

### 前端 (Vue 3.5 / Vite / TypeScript)

```bash
cd ai-code-frontend
# 安装依赖
yarn install
# 启动本地热重载服务
yarn dev
# 类型检查与生产构建
yarn build
# 仅类型检查
yarn type-check
# 代码风格校验与格式化
yarn lint
yarn format
# 根据后端 OpenAPI 重新生成前端 TypeScript API 客户端
yarn openapi2ts
```

## Vibe Workflow 核心工作准则

1. **需求先行 (Frozen Scope)**：未经用户明确决策并冻结需求（`Requirement Status == FROZEN`），严禁静默扩充或更改产品行为。
2. **最小充分上下文**：仅加载完成当前 Task 必需的文件与证据，避免盲目遍历整仓历史。
3. **证据驱动 (Verification Evidence)**：任何宣称完成的 Task 必须附带最新可重现的运行与测试证据（日志存于 `.evidence/`），代码已写或主观报告不等于完成。
4. **决策关卡 (Human Decision Gates)**：遇到涉及数据库 Schema 变动、Public API 破坏性变更、架构方向锁定、鉴权安全边界或对外发布动作时，必须暂停并向用户提交清晰方案获取明确批准。
