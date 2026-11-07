# 生成追踪 API 调用说明

本文供工作台和本地维护者查询创建／生成追踪。只读查询、创建请求编号与后台采集已实现；当前处于开发验证阶段，未部署。完整交付与未验证范围见[阶段验收记录](phase/2026-11-07-generation-observability-evaluation/VERIFICATION.md)。

## 1. 接入与适用范围

- 默认本地后端为 `http://localhost:8123/api`。后端启动前，维护者需要在目标环境执行 `sql/migrations/20261107_generation_trace.sql`；脚本只增加两张追踪表，不回填旧历史。
- 复用现有登录 Session Cookie。前端请求携带 Cookie；管理员不能查看其他用户的追踪。请求编号只是关联键，不能替代登录。
- 契约入口：[控制器](../../src/main/java/com/xhl/aicodegenerate/controller/GenerationTraceController.java)、[查询服务与响应类型](../../src/main/java/com/xhl/aicodegenerate/service/GenerationTraceService.java)、现有 `/v3/api-docs`（相对 `/api`）。本文件解释调用流程和业务含义，不复制完整字段表。
- 追踪记录保留 7×24 小时；删除应用后不可查询。查询不依赖 Redis 的 24 小时任务快照。
- 配置 `generation.trace.enabled=false` 同时停用新采集、查询和清理，保留新表，正常生成继续按原规则执行。

## 2. 接口与调用方式

所有接口沿用 `{code,data,message}`。业务失败通常仍返回 HTTP 200，调用方必须检查 `code`。所有 Long／BIGINT 值（含应用、用户编号和用量汇总）为 JSON 字符串；未上报的 Token 为 `null`，不能转成 0。

| 方法与路径 | 输入 | 成功结果 |
|---|---|---|
| `GET /app/traces` | 必填 `appId`；可选 `cursor`；`pageSize` 默认 20，范围 1～20 | `{records,nextCursor}`；按开始时间和追踪编号倒序；无记录返回空数组 |
| `GET /app/traces/{traceId}` | 非零的 32 位小写十六进制追踪编号 | `{trace,recordingStatus,message}` |
| `GET /app/traces/{traceId}/steps` | `cursor` 默认 0、非负；`pageSize` 默认 100，范围 1～100 | `{records,nextCursor}`；步骤按 `stepNo` 正序 |
| `GET /app/traces/by-task/{taskId}` | UUID 格式的任务编号 | 与详情相同；原任务快照过期后仍按追踪鉴权 |
| `GET /app/traces/by-request/{requestId}` | UUID 格式的服务端创建请求编号 | 与详情相同；创建没有 appId 时仍检查发起用户和关联状态 |

创建接口 `POST /app/add` 的正文及 `data` 中的应用 ID 保持原格式。响应头新增服务端生成的 `X-Request-Id`，失败响应也保留。客户端不能通过自带同名请求头指定这个编号；只在认证及业务校验后开始实际创建追踪，因此有编号不保证一定有可读追踪。

调用顺序：

1. 创建应用时保存响应头编号；创建失败后，可用 `by-request` 查询诊断。
2. 启动生成后，使用可选的任务 `traceId` 查询详情；没有这个字段时，可通过 `by-task` 定位。异步入库尚未完成时可能暂时查不到。
3. 历史入口通过应用列表读取，不从聊天记录推算历史追踪；读取下一页时原样传回游标。
4. 详情与步骤分别读取。生成终态已经出现但追踪仍为 COLLECTING 时，继续短期刷新追踪；不能把任务成功等同于记录已写全。

创建关联状态由服务端处理：BOUND 必须关联仍有效且属于当前用户的应用；NO_APP 表示确认在尝试保存应用前失败；UNRESOLVED 表示结果或关联未确认。最后一种情况下，详情返回 `trace: null, recordingStatus: "UNAVAILABLE"`，步骤接口拒绝返回诊断，不能按“无应用失败”展示。

记录完整性与业务执行结果分别展示：COLLECTING 为采集中，COMPLETE 为无已知采集缺口，PARTIAL 为存在缺失，UNAVAILABLE 为不可读取。COMPLETE 不代表 Token 全量上报，也不代表业务质量断言通过。用量展示同时保留 `modelCalls`、输入／输出上报次数和已知 Token 汇总。

示例命令，需替换应用 ID 和登录 Cookie：

```bash
curl --get 'http://localhost:8123/api/app/traces' \
  --cookie '<浏览器中的登录 Cookie>' \
  --data-urlencode 'appId=<自己的应用 ID>' \
  --data-urlencode 'pageSize=20'
```

没有记录时的成功响应：

```json
{"code":0,"data":{"records":[],"nextCursor":null},"message":"ok"}
```

失败示例：把 `appId` 换成其他用户应用的 ID，预期 `code=40101`；把 `pageSize` 改成 21，预期 `code=40000`。这些预期由服务和 HTTP 契约测试覆盖，命令示例尚未在实际 HTTP 服务器重放。

| 业务码或状态 | 含义 | 调用方处理 |
|---|---|---|
| 40100 | 未登录 | 先登录，再按原入口查询 |
| 40101 | 非发起用户或非当前应用所有者 | 显示无权限，不展示缓存详情 |
| 40400 | 应用／追踪不存在、已删除或已过期 | 显示不可查询，不恢复旧记录 |
| 40000 | 编号或分页范围不合法 | 修正参数，不重复相同错误请求 |
| 50001 | 追踪存储不可用、停用或创建关联无法确认 | 保留原生成／创建结果，提示追踪暂不可用 |
| `code=0` 且 `recordingStatus=UNAVAILABLE` | 本人的创建关联未确认 | 展示提示，不尝试从原文或 appId 空值推断结果 |

## 3. 调用限制与兼容

查询没有生成、发布或恢复执行副作用；不能通过重新查询启动任务。新建生成仍使用原来的 `clientRequestId` 幂等协议，追踪保留期不会延长该协议。

分页的 `nextCursor=null` 表示当前已经读完；列表游标与步骤游标不可混用，不接受任意排序字段。实时刷新应节流，避免每个输出 Token 发一次追踪请求。

错误诊断采用固定分类和安全说明，不返回完整提示词、模型正文、推理、文件正文或异常原文。`safeMetadata` 是白名单 JSON 字符串，展示前按 JSON 解析；字段缺失保持未知。

## 4. 示例核验入口

`GenerationTraceControllerTest` 覆盖五条路由、所有者权限、Long 字符串和数据库不可用响应；`AppCreationTraceTest` 覆盖请求编号、关联状态和采集故障。`TraceWorkbenchBrowserTest` 已通过真实 Chrome、Cookie／CORS、MySQL 验证分页、所有者权限、创建失败编号、迟到响应与删除后清空；其应用／任务入口使用隔离夹具。独立 CLI 另验证真实创建／生成与追踪，不能将两者合称真实供应商端到端验证。具体证据及未覆盖范围见阶段验收记录。

## 个人知识库 API

供知识管理页、输入区和应用来源面板使用。此为本地已实现契约，未部署；实际证据及未验证范围见 [知识库验收记录](phase/2026-11-07-private-knowledge-generation/VERIFICATION.md)。默认地址 `http://localhost:8123/api`，使用现有登录 Session Cookie。先执行 `sql/migrations/20261107_private_knowledge.sql`，完成模型质量验证后才开启 `knowledge.enabled`。

契约源为 [KnowledgeController](../../src/main/java/com/xhl/aicodegenerate/controller/KnowledgeController.java) 的请求／响应 records 与 [前端类型](../../ai-code-frontend/src/api/knowledge.ts)。以下路径均相对 `/api`。响应使用 `{code,data,message}`，须检查业务 `code`；ID 为字符串，容量、revision、版本号及毫秒时间戳为数字。用户身份由登录态绑定，不接受 ownerId 或 collection 扩大范围，管理员非所有者也不可访问。

| 方法与路径 | 输入 | 成功结果与约束 |
|---|---|---|
| GET /knowledge/documents | pageNum 默认 1；pageSize 默认 20，1～50 | `{records,total,quota,enabled}`；仅本人未删除文档；quota 含文件/总字节和份数上限 |
| POST /knowledge/documents | multipart：file、clientRequestId（UUID） | `{documentId,versionId,operationId,status}`；接收操作不等于已可检索，轮询列表状态 |
| POST /knowledge/documents/{documentId}/versions | 同上传，另需 expectedRevision | 显式替换；版本冲突需刷新；处理中／失败保留旧可用版本 |
| POST /knowledge/documents/{documentId}/retry | JSON：versionId 字符串、clientRequestId UUID | 只重试当前失败候选；旧批次不能发布 |
| DELETE /knowledge/documents/{documentId} | 本人文档 ID | true；立即阻止新检索、隐藏历史片段，物理索引异步清理 |
| GET /knowledge/references | appId；pageNum 默认 1；pageSize 默认 20，1～50 | 按时间倒序的记录数组，列表不载片段；不足 pageSize 时到末页 |
| GET /knowledge/references/{taskId} | 任务 UUID | 名称、版本和实际片段；删除文档后 text=null；应用已删除拒绝 |
| POST /app/generation/tasks | 既有 appId、message、clientRequestId，新增 useKnowledge 布尔值 | 省略／false 沿用普通生成；true 必须完成有命中检索才生成；同一编号不能变更开关或需求 |

默认只接受 UTF-8 `.md`／`.txt`，单文件 2 MiB、50 份、20 MiB。格式、空白内容、非法编码、容量超限返回参数错误；容器 multipart 超限也返回容量提示。未登录／越权按现有错误码；协议错误、无命中、依赖不可用停止对应操作或任务，返回安全中文说明，不返回供应商正文。处理中的具体分类在文档 `errorCode` 中，不能依赖 HTTP 200 判断成功。

上传／替换／重试按“用户＋操作类型＋clientRequestId”持久化幂等。同一次网络重试复用原编号和全部参数；明确失败后用户重新处理使用新编号。不能将同名文件当自动覆盖。文档处理状态 PROCESSING／READY／FAILED 与操作状态 PROCESSING／SUCCEEDED／FAILED 含义不同；界面以文档状态及 activeVersionNo 判断可用版。

来源状态 REQUIRED、PREPARED、SUBMITTED、SEND_UNCONFIRMED、INCOMPLETE、FAILED 分别表示待准备、已取得未确认提交、已提交、提交未知、记录不完整、准备失败。来源长期保留，随应用删除；与任务缓存或追踪期限无关。

正常示例（使用测试账号 Cookie；以下命令是调用说明，不冒充业务环境实测）：

```bash
curl -b /tmp/test-session.cookie \
  -F 'file=@订单接口.md' \
  -F 'clientRequestId=11111111-1111-4111-8111-111111111111' \
  http://localhost:8123/api/knowledge/documents
curl -b /tmp/test-session.cookie \
  'http://localhost:8123/api/knowledge/documents?pageNum=1&pageSize=20'
```

预期上传返回 `code:0` 和操作标识，列表完成处理后为 READY；失败示例将文件改为 PDF，预期参数错误并且无新增可用版本。第一次上传结果未确认时重放原请求，不生成重复版本。参考 MockMvc 的用户绑定／ID 契约测试、真实数据库事务测试及真实 multipart 超限测试；完整业务登录 HTTP 链路仍需按使用指南验收。
