# 平台 UI 美化实施计划

> 执行方式：当前会话依照 writing-plans／executing-plans 的任务与证据原则直接实施。已有用户授权覆盖实施及项目约定的远程同步，不重复确认；仓库文档统一维护在本阶段目录。

**目标**：将已批准的首页、生成控制台与管理页参考方向落实为可操作的 Vue 界面。

**架构**：复用现有页面、路由、状态与 API 客户端；共享主题由一个颜色源驱动 Ant Design Vue 和 CSS，管理页复用轻量页面外壳。

**技术栈**：Vue 3.5／TypeScript 5.8／Ant Design Vue 4.2.6／Vite 7／yarn。

**方案**：[PROPOSED_DESIGN.md](PROPOSED_DESIGN.md)，第 9 节为本轮批准与参考图拆解。

## 共同约束

- 保留生成模式、知识库、SSE、停止／重试、旧版本、下载／部署及权限语义。
- 不改变后端、Schema、API；不安装组件库或生图依赖。
- 当前 main → origin/main，完成验证后显式暂存本次文件提交推送；其他已修改文件保留。
- 样式以原截图对应的层级、间距与材质为基线；营销主题只借视觉语言。
- 生图工具不可用，本轮参考图均为已有素材；如实记录视觉适配而非声称像素复刻。

## 文件与任务

| 任务 | 文件 | 交付与检查 |
|---|---|---|
| T1 主题及首页 | src/App.vue、assets/ui-theme.css、main.ts、layouts/BasicLayout.vue、components/GlobalHeader.vue、components/AppCard.vue、pages/HomePage.vue | 统一深浅颜色；模式移至输入；真实封面与明确缺图状态；首页加载／失败／登录状态；桌面／手机截图 |
| T2 工作台 | pages/app/AppChatPage.vue、components/AppChatComposer.vue、components/GenerationTracePanel.vue | 预览侧工具栏、覆盖抽屉、手机切换；抽屉开关不改变工作区尺寸和输入；保留分栏与生成恢复 |
| T3 管理界面 | components/AdminPageShell.vue、pages/admin/{UserManagePage,AppManagePage,ChatHistoryManagePage}.vue、assets/ui-theme.css | 三页共享导航、身份列、数据层级、长内容详情及请求失败重试；筛选、分页和已有编辑／删除功能保留 |
| T4 验证与交付 | tests/、本阶段 VERIFICATION.md、PROGRESS.md、DOCUMENT_MAP.md | 现有测试、类型／构建、修改文件 lint、真实浏览器受控交互与视觉检查、独立复审、提交推送核验 |

## 顺序与执行记录

- [x] 读取有效方案、源码与原始参考图，确认范围，保存已有改动基线。
- [x] T1：实现主题／导航／首页／作品卡，验证模式与列表状态。
- [x] T2：调整双栏、工具栏和手机视图，验证草稿与抽屉焦点。
- [x] T3：重组管理页，验证长内容、筛选、分页、状态与行操作。
- [x] T4：执行上述检查，修复发现的问题，更新验收与进度，提交并核对远程哈希。

## 审查重点

1. 未登录不请求私人列表；失败不能显示成空列表。
2. 手机切换与诊断抽屉不得丢失输入、对话滚动或生成状态。
3. 系统深色时弹窗、表格、输入与正文需同色系且可读。
4. 长 ID／提示词／应用名不得挤掉操作按钮或造成整页横向溢出。
5. 请求快速切换与重试、删除确认和角色权限应保留原业务边界。

验证命令：在 ai-code-frontend 运行 yarn test、yarn build、仅修改文件 eslint（不自动修复无关文件）。浏览器证据存 .evidence/ui-implementation-20261108/，受控数据与真实后端验证严格区分。回退仅恢复本次显式提交涉及的前端文件；不触碰已有后端改动。

交付结果：功能提交 `a2d34c7` 已同步 `origin/main`，完整远程哈希核对一致；本计划的完成状态随文档收尾提交同步。
