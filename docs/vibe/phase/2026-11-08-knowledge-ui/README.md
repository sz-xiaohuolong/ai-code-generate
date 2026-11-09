# 知识库页面 UI 优化

## 范围与依据

用户于 2026-11-08 明确批准：① Aivia 的整体结构、② Document Management 的文档行层级、⑥ Storage Notification 的容量提示；只优化知识库页面 UI，不拓展后端。随后明确要求“用截图作为视觉依据，不需要生图”。采用 image-to-code 的截图分析与忠实实现路径，不声明生成了设计图。

技术基线：现有 Vue 3.5、TypeScript、Vite、Ant Design Vue、Yarn；沿用 `KnowledgePage.vue`、`api/knowledge.ts`、现有主题变量与原生文件输入。API、上传幂等标识、版本并发控制、轮询、删除通知均沿用；选择文档只影响本页展示。图标从 Iconify 的 Lucide 集合提取为本地 SVG，附带原始许可，不新增运行时依赖。

## 截图分析与设计映射

原始截图保存在 `.evidence/knowledge-ui-20261108/references/`。

| 来源 | 截图中的可用设计 | 本页适配 |
| --- | --- | --- |
| [Aivia](https://dribbble.com/shots/27178382-Aivia-AI-Customer-Support-Dashboard-Knowledge-Base-Page) | 浅色工作台、中央宽列表、右侧窄详情、细分隔线、选中行浅色背景 | 保留平台导航，页面采用主列表与 304px 侧栏。点击文档展示现有字段；不添加来源同步、标签、统计、搜索、分类或预览。 |
| [Document Management](https://dribbble.com/shots/26792610-Document-Management-Dashboard) | 文档名、状态、操作的列层级与清晰表头 | 文件名 14px 半粗，类型/大小/版本为次级信息，状态使用文字与色点，失败说明直接可读，列表明确标注操作列。 |
| [Storage Notification](https://dribbble.com/shots/26838198-AI-Knowledge-Base-Storage-Notification) | 深色容量提示、分段进度、已用/总量/剩余与百分比 | 容量区常驻侧栏，读取真实 quota；90% 起提醒、满额提示清理。去掉升级套餐；份数与文件上限保持真实。 |

页面标题 30px，正文 14px，辅助信息 11–12px；主侧栏间隔 30px，文档行约 90px 高。以项目成功色承担绿色强调，容量区使用深绿渐变与分段刻度，背景只加轻微渐变。细边线组织内容，避免嵌套卡片和额外三卡片统计。

800px 以下改为纵向阅读，540px 以下详情和容量纵向排列。空、加载、请求失败、未启用、处理中、处理失败、旧版本仍可用和提交结果未知均保留。键盘可选文档；图标不依赖外网；减少动画偏好下取消行过渡。

参考检索：按 cix-workspace 在 local/my-vault 检索并读取 `pai-study/PaiSmart/frontend/src/views/knowledge-base/index.vue`。其 Naive UI、分片上传、组织权限与预览能力超出本任务；已检索，未采用外部实现。当前项目已有 API 与状态处理足够。

## 实施与验收

1. 截取三个已选案例并分析布局、排版、状态和容量视觉。
2. 仅修改知识库页面及其本地图标/许可，保留原有业务操作。
3. 执行页面 lint、前端测试、类型与构建检查，再用真实 Chrome、受控 API 验证正常与异常路径，并保存桌面/手机/深色截图。
4. 按仓库长期授权提交与推送本次范围；保留开工前已有的其他改动，不部署。

验收入口：`scripts/verify-knowledge-ui.mjs`；通过 `PLAYWRIGHT_MODULE` 指定现有 Playwright 安装，通过 `UI_TEST_URL` 指定前端生产预览地址。所有业务 API 拦截，未声明的外部请求阻断。

## 验证结果

### 2026-11-09：知识库持续加载修复

后端 `JsonConfig` 将 `Long` 与 `long` 序列化为字符串。知识库详情将字符串毫秒时间戳直接传入 `new Date()`，日期格式化抛出 `RangeError: Invalid time value`，Vue 更新失败后保留加载中的旧画面。仅将响应中的时间戳换成数字即可恢复，排除了容量字符串与加载状态复位问题。

修复在日期显示处先执行 `Number()`，同时修正知识库参考记录的同类问题及时间戳类型。验收数据改用后端真实的字符串时间戳：修改前同一浏览器验收在等待容量出现时失败，修改后 18 项通过；17 项现有单测、类型检查和生产构建通过。证据位于 `.evidence/knowledge-loading-20261109/`。验证使用本地真实 Chrome、受控接口，未读取用户浏览器会话。

按仓库要求完成 cix-workspace `my-vault` 检索，读取 `pai-study/paicoding/paicoding-miniapp/utils/date.js`；未采用外部实现，使用 JavaScript 标准数值转换处理本项目接口契约。

验证状态：VERIFIED（受控前端范围）；交付状态：RELEASED（代码同步）。功能提交 `bc1075a` 已推送 `origin/main`，并核对完整哈希 `bc1075afd4913f30de9f39fda91992998c387fd6` 一致；未部署。

- `yarn build`：类型检查与生产构建通过。仅保留既有 Ant Design Vue 大 chunk 提示。
- `eslint src/pages/knowledge/KnowledgePage.vue`：通过；现有前端单测 17/17 通过。
- `scripts/verify-knowledge-ui.mjs`：真实 Chrome 18/18 通过，包含键盘选择、轮询刷新、旧版本保留、重试幂等、替换并发条件、文件校验、未知上传结果恢复、删除确认与取消、切换文档关闭旧确认、容量边界、未启用、空/加载/失败与深色/响应式。
- 截图检查覆盖 1440px 桌面、1366/768/390/320px 布局与长名称。手机选择文档后自动定位并聚焦详情，深色删除按钮沿用语义危险色。
- 生产构建曾将 SVG sprite 内联，导致图标未绘制。补充实际 SVG 绘制断言后先失败，再通过 `?no-inline` 保留独立本地资源修复；日志为 `icon-red.log`，最终浏览器结果含回归检查。
- 工作区同时存在其他任务的全局主题改动。最终使用 `f676f3e` 的已提交前端加本次页面/资源构建隔离快照，未引入那些未提交改动；源码与交付差异对应 `verified-base.txt` 和 `verified-tree/`。业务请求全部由测试拦截，未调用真实模型、修改业务数据或部署。

原始证据位于 `.evidence/knowledge-ui-20261108/`：`build.log`、`unit-tests.log`、`browser.log`、`browser-summary.json`、三张原始参考截图，以及 `desktop.png`、`dark-inspection-before.png`、`mobile.png`、`mobile-selected.png`、`capacity-warning.png`、`empty.png`、`loading.png` 和 `error.png`。截图使用明确的验收样例，不是用户真实知识库内容。

针对性自审已核对：前后端 API 文件未改动；新增选择状态仅控制展示；删除弹窗按文档 ID 重建，避免旧确认作用于新选择；上传幂等和版本条件保持原样；未引入 React、Tailwind、运行时图标请求或无功能的搜索/分类入口。
