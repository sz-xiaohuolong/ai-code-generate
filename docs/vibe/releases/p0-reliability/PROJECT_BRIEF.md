# P0：生成与 Vue 交付可靠性

- 需求状态：FROZEN；2026-10-18 用户确认 Q1～Q7，并明确授权一个 Phase 的文档、实现及远程同步。
- 目标：同一应用只有一个后台生成任务；Vue 在有限预算内完成构建、定向修复和预览验证；失败保留上次成功版本。
- 执行范围：标准／工作流模式，HTML／多文件／Vue 的任务可靠性，以及两种模式共享的 Vue 交付闭环。
- 发布范围：本仓库提交并推送 origin/main；不含线上部署、Tag 或 GitHub Release。
- 既有未提交的路由回退、推理配置与相关修复文档保留，不混入本 Phase 提交。
- 权威行为与验收：[SPEC.md](SPEC.md)；目标设计：[PROPOSED_DESIGN.md](PROPOSED_DESIGN.md)。
