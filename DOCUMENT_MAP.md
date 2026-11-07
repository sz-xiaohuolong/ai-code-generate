# 文档地图

- 文档索引更新日期：`2026-11-08`
- 唯一导航入口：`DOCUMENT_MAP.md`

本文件只作导航；需求、设计、进度与验收事实分别由对应文档维护。

## 通用文档

| 职责 | 路径 | 说明 |
|---|---|---|
| Agent 规则 | [AGENTS.md](AGENTS.md)、[CLAUDE.md](CLAUDE.md) | 行为入口与工程技术约定 |
| 产品范围与定位 | [PRODUCT.md](PRODUCT.md) | 宏观定位、用户、端到端闭环与硬性约束；各阶段需求见下表 |
| 当前系统架构 | [架构摘要](docs/architecture-summary.md)、[工作流说明](docs/langgraph4j-codegen-workflow.md) | 当前系统分层、数据流、生成管线与存储 |
| 当前进度 | [PROGRESS.md](docs/vibe/PROGRESS.md) | 当前阶段状态、授权边界、未提交改动与历史交付记录 |
| API 调用说明 | [API.md](docs/vibe/API.md) | 追踪查询、认证、兼容性与当前核验范围 |
| 领域术语 | [GLOSSARY.md](GLOSSARY.md) | 生成、追踪与评测术语的唯一含义来源 |
| 竞品调研与功能差距 | [research.md](research.md) | 调研完成，候选建议尚未冻结；不直接改变已批准需求 |
| 决策记录 | [decisions/](docs/vibe/decisions/) | [ADR-0001](docs/vibe/decisions/0001-generation-trace-storage.md) 记录追踪业务存储与独立评测证据的已批准取舍 |
| 缺陷记录 | `docs/vibe/bugs/`（尚未建立） | 当前没有独立缺陷台账，已有修复记录见进度文档 |
| 测试与验证证据 | `src/test/`、`.evidence/` | 适用范围与结论见阶段验收文档；原始日志与截图保留原路径 |
| 架构与上手知识库 | [wiki/00-index.md](wiki/00-index.md) | 系统架构与快速上手说明 |

## Phase 阶段目录

需求开发统一归档到 `docs/vibe/phase/`。按建立日期倒序排列；目录日期不代表发布日期，阶段状态以各自文档和进度记录为准。

| 阶段 | 需求入口 | 设计、计划与验收 | 状态及边界 |
|---|---|---|---|
| [2026-11-07-private-knowledge-generation](docs/vibe/phase/2026-11-07-private-knowledge-generation/)：个人知识库辅助代码生成 | [PRD](docs/vibe/phase/2026-11-07-private-knowledge-generation/PRD.md) | [方案设计](docs/vibe/phase/2026-11-07-private-knowledge-generation/PROPOSED_DESIGN.md) | 需求 FROZEN；工具检索方案 APPROVED；MVP 受控链路通过；验收 PARTIAL，中文质量未通过、默认关闭 |
| [2026-11-07-generation-observability-evaluation](docs/vibe/phase/2026-11-07-generation-observability-evaluation/)：生成追踪与独立质量评测 | [PRD](docs/vibe/phase/2026-11-07-generation-observability-evaluation/PRD.md) | [方案设计](docs/vibe/phase/2026-11-07-generation-observability-evaluation/PROPOSED_DESIGN.md)、[实施计划](docs/vibe/phase/2026-11-07-generation-observability-evaluation/IMPLEMENTATION_PLAN.md)、[验收](docs/vibe/phase/2026-11-07-generation-observability-evaluation/VERIFICATION.md) | 本地验收完成；需求 FROZEN，设计 IMPLEMENTED；受控范围 VERIFIED，READY_TO_SHIP；代码同步已授权，正在补做 |
| [2026-10-20-tool-context-pruning](docs/vibe/phase/2026-10-20-tool-context-pruning/)：工具调用上下文裁剪 | [综合记录](docs/vibe/phase/2026-10-20-tool-context-pruning/README.md) | 同一文档包含方案、验收条件、实施顺序与验证记录 | 已交付；离线冻结范围 VERIFIED |
| [2026-10-18-p0-reliability](docs/vibe/phase/2026-10-18-p0-reliability/)：生成任务与 Vue 交付可靠性 | [需求概要](docs/vibe/phase/2026-10-18-p0-reliability/PROJECT_BRIEF.md)、[SPEC](docs/vibe/phase/2026-10-18-p0-reliability/SPEC.md) | [方案设计](docs/vibe/phase/2026-10-18-p0-reliability/PROPOSED_DESIGN.md)、[实施计划](docs/vibe/phase/2026-10-18-p0-reliability/IMPLEMENTATION_PLAN.md)、[验收](docs/vibe/phase/2026-10-18-p0-reliability/VERIFICATION.md) | 已交付；冻结范围 VERIFIED，外部边界见验收 |
| [2026-10-18-generation-modes](docs/vibe/phase/2026-10-18-generation-modes/)：标准与工作流生成模式 | [SPEC](docs/vibe/phase/2026-10-18-generation-modes/SPEC.md) | [方案设计](docs/vibe/phase/2026-10-18-generation-modes/PROPOSED_DESIGN.md)、[实施计划](docs/vibe/phase/2026-10-18-generation-modes/IMPLEMENTATION_PLAN.md)、[验收](docs/vibe/phase/2026-10-18-generation-modes/VERIFICATION.md) | 已交付；历史验证 PARTIAL，保留外部验证缺口 |
| [2026-09-26-app-cover-screenshot](docs/vibe/phase/2026-09-26-app-cover-screenshot/)：应用封面截图 | [需求概要](docs/vibe/phase/2026-09-26-app-cover-screenshot/PROJECT_BRIEF.md)、[SPEC](docs/vibe/phase/2026-09-26-app-cover-screenshot/SPEC.md) | [方案设计](docs/vibe/phase/2026-09-26-app-cover-screenshot/PROPOSED_DESIGN.md)、[实施计划](docs/vibe/phase/2026-09-26-app-cover-screenshot/IMPLEMENTATION_PLAN.md)、[验收](docs/vibe/phase/2026-09-26-app-cover-screenshot/VERIFICATION.md) | 历史 VERIFIED，仅适用于原验收记录范围 |
| [2026-08-16-platform-safety-stability-cost](docs/vibe/phase/2026-08-16-platform-safety-stability-cost/)：平台安全性、稳定性与成本优化 | 未单独建档 | [方案设计](docs/vibe/phase/2026-08-16-platform-safety-stability-cost/PROPOSED_DESIGN.md)、[实施计划](docs/vibe/phase/2026-08-16-platform-safety-stability-cost/IMPLEMENTATION_PLAN.md) | 历史资料归档；本次仅整理文档，实施与验收状态需结合源码及历史证据核查 |
| [2026-08-16-featured-app-redis-cache](docs/vibe/phase/2026-08-16-featured-app-redis-cache/)：精选应用 Redis 缓存 | 未单独建档 | [方案设计](docs/vibe/phase/2026-08-16-featured-app-redis-cache/PROPOSED_DESIGN.md)、[实施计划](docs/vibe/phase/2026-08-16-featured-app-redis-cache/IMPLEMENTATION_PLAN.md) | 历史资料归档；本次仅整理文档，实施与验收状态需结合源码及历史证据核查 |
| [2026-06-22-langgraph4j-codegen-workflow](docs/vibe/phase/2026-06-22-langgraph4j-codegen-workflow/)：LangGraph4j 代码生成工作流 | 未单独建档 | [实施计划](docs/vibe/phase/2026-06-22-langgraph4j-codegen-workflow/IMPLEMENTATION_PLAN.md) | 历史资料归档；本次仅整理文档，实施与验收状态需结合源码及历史证据核查 |

## 目录与文件约定

1. **统一组织**：每项有独立范围的需求开发使用 `docs/vibe/phase/YYYY-MM-DD-功能名/`。日期取阶段建立或确认日期，功能名采用简短英文小写连字符形式；后续编辑沿用原目录日期。
2. **按需建档**：新需求使用 `PRD.md`，存量 `SPEC.md` 继续沿用；按阶段需要补充 `PROJECT_BRIEF.md`（概要）、`PROPOSED_DESIGN.md`（方案）、`IMPLEMENTATION_PLAN.md`（实施顺序）和 `VERIFICATION.md`（验收证据与边界）。小范围任务可以仅维护一份 `README.md` 综合记录，无需补齐整套模板。
3. **公共事实归位**：`PROGRESS.md` 维护当前状态，`decisions/` 保存决策，根目录 `GLOSSARY.md` 维护术语；阶段文档通过链接引用公共事实。本地图统一导航，不另建重复索引。
4. **保留历史含义**：历史阶段统一归入 `phase/`，归档整理只调整文档组织、引用和执行入口提示。历史 Release 名称、提交、批准状态和验收结论保留原含义；`.evidence/` 与运行产物路径保持原样。Phase 目录的存在不代表已获准实施或已发布。
