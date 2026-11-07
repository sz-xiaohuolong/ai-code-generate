# API 调用说明

本文记录个人知识库 MVP 新增接口；既有生成任务接口继续沿用原有契约。

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
