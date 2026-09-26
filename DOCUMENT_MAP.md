# Document Map

- Last Audited: `2026-09-26`
- Map Owner: `DOCUMENT_MAP.md`

| 职责 | 实际路径或外部位置 | 状态 | 定位依据/备注 |
|---|---|---|---|
| Agent 规则 | [AGENTS.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/AGENTS.md) | `已核验` | 统一行为入口，索引并保留 [CLAUDE.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/CLAUDE.md) 规则 |
| 产品范围与定位 | [PRODUCT.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/PRODUCT.md) | `当前实现待确认` | 描述系统核心定位、用户、端到端闭环与硬性约束；尚未冻结新 Release 需求 |
| 当前系统架构 | [docs/architecture-summary.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/architecture-summary.md) | `已核验` | 后端分层、数据流、LangChain4j/LangGraph4j 及存储全景，辅以 [langgraph4j-codegen-workflow.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/langgraph4j-codegen-workflow.md) |
| 当前执行进度 | [docs/vibe/PROGRESS.md](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/vibe/PROGRESS.md) | `已建立待核验` | 追踪当前 State、未提交改动、Last Stable Commit 与下一 Gate |
| 历史技术设计 | [docs/superpowers/specs/](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/superpowers/specs/) | `历史封存` | 2026-08-16 精选缓存设计、平台安全稳定性设计，已落地 |
| 历史实施计划 | [docs/superpowers/plans/](file:///Users/daiyifei/Documents/code/zero-code-generate/ai-code-generate/docs/superpowers/plans/) | `历史封存` | 工作流、缓存与安全分阶段计划，已执行完毕 |
| 决策记录 (ADR) | `docs/vibe/decisions/` | `未建立` | 历史技术决策分散于 specs，后续架构/破坏性决策在此单独建立 |
| 缺陷记录 (Bugs) | `docs/vibe/bugs/` | `未建立` | 暂无存量阻断性缺陷台账，局部问题由单测覆盖 |
| 测试与验证证据 | `src/test/`、`.evidence/` | `已核验` | 后端 28 个单测套件与前端构建命令，测试证据按规范存放于 `.evidence/` |

本文件只作导航，不复制需求、设计或验证结论。一个仓库只保留一个权威地图。
