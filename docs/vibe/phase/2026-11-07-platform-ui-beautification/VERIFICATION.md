# 平台 UI 美化验收记录

- 验证日期：2026-11-08。
- 需求：FROZEN；设计已批准，实施完成。验证结论：VERIFIED（受控前端范围）；独立复审结论 ship，代码同步结果待本轮收尾补记。
- 范围：首页、生成工作台、用户／应用／对话管理页，以及共享主题和导航。
- 有效方案：[PROPOSED_DESIGN.md](PROPOSED_DESIGN.md)；实施顺序：[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)。

## 参考图与实际适配

用户认可的原始参考图为唯一视觉依据。本轮没有可用的内置生图能力，未配置生图 API 密钥，因此没有生成新的设计图；先分析已有图片，再按其布局、比例、色彩和表格层级实现。该过程是现有业务的视觉适配，不声明像素级复刻。

| 页面 | 参考依据 | 已落实的特征 |
|---|---|---|
| 首页 | v0／Bolt 的创作入口 | 760px 集中输入、模式与知识库控件就近放置、蓝色文字主按钮；真实作品封面，缺图明确提示 |
| 工作台 | Cobalt 与 v0 预览组织 | 中性双栏、初始 36% 对话宽度、工具放在预览侧；生成记录覆盖抽屉，手机对话／预览／代码切换 |
| 管理页 | Ink 与 Almanac | 平面页面、共享管理页签、身份合列、较重表头线与细行分隔；保留完整 ID、优先级和消息详情 |
| 深色 | 同一语义颜色源 | 页面 CSS 和 Ant Design Vue 同步响应系统主题；生成作品的 iframe 保留作品自己的颜色 |

原图位置：`.evidence/ui-design-20261107/reference-{v0,bolt}.png`、`.evidence/console-design-20261108/hallmark-cobalt.jpg`、Hallmark `references/themes/{ink,almanac}.jpg`。

## 验证结果

原始证据目录：`.evidence/ui-implementation-20261108/`。

| 检查 | 结果 | 证据 |
|---|---|---|
| 现有前端测试 | 16 项通过 | `tests.log` |
| 类型检查与生产构建 | 通过；保留既有大包体警告 | `build.log` |
| 修改文件 ESLint | 通过 | `lint.log` |
| 开发服务 Chrome 验收 | 24 项通过，无未捕获脚本异常 | `browser-results.json`、`browser.log` |
| 生产构建 Chrome 验收 | 26 项通过，无未捕获脚本异常 | `production/browser-results.json`、`production-browser.log` |
| Hallmark 静态检查 | 本轮主要 11 个样式／页面文件无 FAIL／REFLEX；3 项提示经检查排除 | `hallmark-render.json` |
| Hallmark 自带 render | 未运行：环境缺少 puppeteer-core；不记为通过 | 同上；真实截图与交互由 Playwright 驱动的 Chrome 验证 |
| 其他任务改动保护 | 4 个已有修改文件 SHA-256 与开工基线完全一致 | `protected-files.json`、`baseline-hashes.json` |

浏览器覆盖：首页创建模式和参数、失败保留输入、私有／精选列表重试；管理筛选、重置、分页、编辑与删除确认、封面放大预览、精选优先级、长正文详情、失败与空状态；工作台抽屉高度不变、关闭恢复焦点与草稿、键盘分栏、折叠、手机分区与全宽抽屉、恢复生成任务后停止并保留成功预览；320px 长应用名、390px 手机、1440px 桌面、系统深色弹窗与页面、管理操作链接对比度至少 4.5:1；未登录与普通用户的管理路由拦截。

浏览器全部 API 通过测试拦截，封面、用户与预览作品均为明确标注的验收样例。未调用真实模型、未修改业务数据，未验证真实后端／供应商端到端；本次没有后端改动，未运行后端全量测试。

静态提示排除依据：作品封面位于创作输入下方，保留延迟加载；管理页的可点击消息／提示词是两行正文摘要，完整正文由详情抽屉呈现，不强行压成普通单行按钮。

## 可复现命令

在前端目录运行 `yarn test`、`yarn build`，再对本轮修改的 Vue／TypeScript 文件运行 `eslint`（不自动修复无关文件）。生产预览运行 `yarn preview --host 127.0.0.1 --port 4175 --strictPort`。

在仓库根目录运行：

```sh
UI_TEST_URL=http://127.0.0.1:4175 \
UI_EVIDENCE_DIR=.evidence/ui-implementation-20261108/production \
PLAYWRIGHT_MODULE=file:///Users/daiyifei/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright/index.mjs \
node scripts/verify-platform-ui.mjs
```

`PLAYWRIGHT_MODULE` 可替换为环境提供的 Playwright 模块；默认尝试普通模块解析。本轮复用机器现有浏览器和运行时，没有向项目引入新依赖。

## 运行截图

均为生产构建的真实 Chrome 截图，包含受控示例数据。

- [首页](../../../../.evidence/ui-implementation-20261108/production/home-desktop.png)
- [工作台](../../../../.evidence/ui-implementation-20261108/production/workbench-desktop.png)
- [用户管理](../../../../.evidence/ui-implementation-20261108/production/admin-users-desktop.png)
- [应用管理](../../../../.evidence/ui-implementation-20261108/production/admin-apps-desktop.png)
- [对话管理](../../../../.evidence/ui-implementation-20261108/production/admin-messages-desktop.png)
- [手机工作台](../../../../.evidence/ui-implementation-20261108/production/workbench-mobile-chat.png)
- [深色工作台](../../../../.evidence/ui-implementation-20261108/production/workbench-dark.png)

## 复审与同步

独立最终复审结论为 **ship**，记录见 `.evidence/ui-implementation-20261108/final-review.md`。发现的深色操作链接对比度问题已修复：ConfigProvider 的链接正常、悬停和按下颜色显式接入共享主题，深色实际对比度 7.016:1，新增 DOM 对比度断言通过。抽屉和深色编辑弹窗曾在入场动画首帧截图，已统一等待动画结束后重拍；封面放大预览也已保留并通过验收。无遗留发现。

按项目持续授权仅提交本轮前端、验收脚本和文档并推送 `origin/main`，其他任务的后端／配置改动保留。代码同步结果在本轮收尾补记。未部署。
