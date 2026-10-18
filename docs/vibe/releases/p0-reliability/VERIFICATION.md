# P0 可靠性验收

- 日期：2026-10-18；范围为 [SPEC.md](SPEC.md) 的 REQ-01～10。
- 验证状态：VERIFIED，指本 Phase 冻结行为已由下列适用证据覆盖，不代表所有供应商、网络故障或业务项目均已穷举。
- 同步范围：当前仓库 `origin/main`；不包含线上部署、Tag 或 GitHub Release。
- 原始证据：仓库忽略目录 `.evidence/p0-reliability/`；可重建测试保存在 `src/test/` 和 `ai-code-frontend/tests/`。

## 自动化结果

| 检查 | 结果与边界 | 证据 |
|---|---|---|
| 适用后端回归 | 42 个测试类、139 项通过，失败／错误／跳过均为 0；排除带真实外部调用的 `@SpringBootTest` | `backend-final-test-classes.txt`、`backend-final-regression.log` |
| 发布后兼容检查 | 补充修改后，任务服务、两种模式 Vue 交付、封面服务 12 项通过 | `publish-cover-regression.log` |
| 成功来源与封面 | 2 项通过，部署只复制成功 dist，发布后封面 URL 固定到成功版本 | `delivery-source-green.log` |
| 最新 SSE 历史主键 | 1 项通过，应用及历史 Snowflake ID 均以字符串传输 | `history-snapshot-green.log` |
| 前端行为 | 9 项通过，覆盖快照恢复、停止状态、请求重试、历史去重和工作流状态 | `frontend-final-test.log` |
| 前端生产构建 | 类型检查与 Vite 构建通过；保留既有大体积依赖分包提示 | `frontend-final-build.log` |
| 精确提交树编译 | 在临时目录导出暂存树，`./mvnw -DskipTests test` 编译主代码及 49 个测试源文件通过；该命令不执行测试 | `staged-compile.log` |
| 演示页 | 脚本语法检查通过；改用稳定请求 ID 和 task-snapshot | `src/main/resources/static/workflow-sse.html` |

适用回归使用扫描 `src/test/java` 后的测试类清单调用 `./mvnw -Dtest=<清单> test`。前端命令为 `yarn test`、`yarn build`。最后两个后端补充检查复用此前未受影响的回归证据，不将数量重复累计。

## 需求与验收证据

| 需求 | 结果与可重现接缝 |
|---|---|
| REQ-01 幂等 | `RedisTaskStoreTest`、`GenerationTaskServiceTest`：并发 20 次相同请求只执行一次，重复输入返回原任务，ID 冲突拒绝。真实 API 验证同 ID 复用、不同内容冲突及忙碌拒绝。前端响应丢失重试只保留一对临时消息。 |
| REQ-02 应用互斥 | 真实 Redis 验证所有者续期／释放／提交；错误所有者和迟到任务不能发布。清理未完成时继续忙碌，不排队。 |
| REQ-03 连接独立 | 服务测试断开订阅后仍完成、再次订阅返回聚合结果。真实模型任务首包后断线仍 SUCCEEDED；浏览器刷新恢复同应用结果和正式历史，无重复生成请求。 |
| REQ-04 停止与终态 | `GenerationExecutionTest`、`GenerationTaskServiceTest`：停止真实进程、禁止迟到写入、清理失败继续 STOPPING、超时／取消区分。真实 UI 停止任务返回 CANCELLED；保留中断标记的模型异常专门回归。停止后输入可再次发送。 |
| REQ-05 总预算及用量 | `VueDeliveryServiceTest`：修复共享剩余预算、重复诊断停止、最多两轮。`TaskChatModelTest`、`UsageTrackingStreamingModelTest`：生成及结构化调用统计实际 usage，并取消底层流。真实标准 Vue 样例报告 2 次模型调用、5983 输入和 1885 输出 token；不将该样例外推为费用或成功率目标。 |
| REQ-06 成功版本 | `GenerationWorkspaceTest`、Redis 及任务服务测试：独立草稿、成功指针长期保留、失败不替换。真实取消后预览仍固定到前次成功版本，下载 ZIP 也包含前次源码且不包含取消修改。部署代码读取同一成功来源，不再次构建。 |
| REQ-07 构建诊断 | `VueProjectBuilderDiagnosticsTest`：真实 npm／Node 进程、大量输出不阻塞、stdout／stderr 限长、新 dist 判定；交付测试区分代码错误与环境故障，环境故障不触发盲目修复。 |
| REQ-08 两模式共享修复 | `VueReliabilityIntegrationTest` 参数化执行 standard／workflow；受控模型边界，真实图编排、Redis、Vite／Vue 编译器和 Chrome。故意生成错误闭合标签，捕获真实编译错误，修复一轮后构建与挂载成功。标准 UI 无详细工作流列表，工作流保留真实节点及修复步骤。 |
| REQ-09 预览及文件约束 | Chrome 测试验证入口、资源、运行错误及真正 Vue 挂载，拒绝仅伪造 DOM 占位。`FileToolSafetyTest` 验证绝对路径、越界、符号链接和歧义替换。静态路由回归覆盖固定版本、旧 dist 前缀与封面越界。真实预览可交互，两项产物资源 HTTP 200。 |
| REQ-10 权限及中断 | 控制器与任务服务测试覆盖非所有者查询／停止拒绝；订阅也经过同一所有者验证。真实 Redis 模拟租约丢失恢复为 INTERRUPTED，不启动模型；未为验收强杀用户正在运行的后端进程。 |

## 真实本地验收

后端 `localhost:8123/api`，本轮前端 `localhost:5180`。使用本地合成测试账号，凭据仅位于临时文件，不纳入仓库。

- 应用 `469318520992325632`，标准 Vue 初次任务 `3fd3442c-8ea5-4af5-b12b-93de7c646c65`：真实模型生成、npm 构建、Chrome 检查完成；首次 SSE 后断开连接未取消任务。见 `live-task.log`、`live-task-result.json`。
- 后续真实修改成功，浏览器计数按钮可交互。成功版本 `88b679d3-e908-46d7-aba2-334476206c6a` 包含计数与待办组件。
- 任务 `139b81be-1e0f-48eb-9cd1-428125fe3f1e` 主动停止后为 CANCELLED；预览和下载仍读取上述成功版本，未包含被取消的标题修改。数据库仅一对正式主键，页面刷新为 10 条记录，标准模式详细步骤数量为 0。见 `live-history-and-preview.json`、`cancelled-preserved-preview.jpg`。
- 新历史 SQL 在真实数据库成功插入成功／失败／取消结果；稳定主键补记的短暂故障与重复重试由自动化覆盖。

## 审查与修复记录

独立只读审查未发现 Critical，发现 4 项 Important，均已修复并补针对性红绿：旧快照覆盖终态、正式历史失败后遗漏、提交响应丢失重复展示、静态占位冒充 Vue 挂载。对应日志为 `review-race-red.log`、`review-history-preview-red.log`、`review-ui-red.log`、`review-fixes-green.log`。

浏览器验收追加发现并完成红绿修复：旧 Vue dist 预览前缀（`preview-compat-red/green.log`）；模型保留中断标记使取消误判失败（`stop-interruption-red/green.log`）；数据库时间截断至秒导致历史重复（`history-time-red.log`、最终前端日志）。最后一次浏览器刷新已确认去重。

## 明确边界

1. 未运行所有含真实付费模型调用的 Spring Boot 测试；两模式自愈集成使用受控模型，不能据此宣称真实复杂 Vue 项目成功率达到 90%。
2. 未新增金额扣款系统或供应商精确一次计费承诺；停止已取消本地模型流，但供应商是否立即停止计费取决于其实现。
3. 工作目录和工具路径约束不是容器级隔离；现有 npm 脚本执行、浏览器网络访问、极短命后代进程不构成完整恶意代码沙箱保证。
4. Redis 任务与幂等保留 24 小时；成功指针和版本目录需共同备份，历史待记内容直到成功补记才清除。暂不自动删除成功版本或加入版本管理界面。
5. 未执行线上部署；部署来源已按实现和适用测试验证为成功版本，本轮不通过点击部署按钮扩大发布范围。
6. 既有未提交的路由回退、推理配置、对应测试及原有文档差异保持原样，排除于本 Phase 提交；基线见 `preexisting.patch`。

## 代码同步

功能提交 `59310a9f3eadd7528e8af3a9ebf5a180f63e65cd` 已推送 `origin/main`，远程引用核对一致。既有 5 个跟踪文件差异的增删内容与执行前补丁完全一致，原有未跟踪 `RoutingFallbackTest.java` 继续保留；未将凭据或原始运行日志提交。进度与同步收尾文档随后单独提交，最终引用以仓库 HEAD 和远程 main 为准。
