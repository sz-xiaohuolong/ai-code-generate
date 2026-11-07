# 验收记录：个人知识库辅助代码生成 MVP

**当前为 PARTIAL：代码链路通过受控验证，中文检索质量未通过，真实供应商生成质量尚未验收。** 默认 `knowledge.enabled=false`；不能将代码同步解释为功能已经可直接启用。2026-11-08 用户已批准并完成本地 BGE-M3 切换，最新结果见第 9 节；此前 Nomic 记录保留为历史，不代表当前默认模型。

## 1. 被测范围与环境

- 验证日期：2026-11-07；基线 `6fcf5d2`。交付树为 `.evidence/private-knowledge-mvp-20261107/release-tree`，剥离主工作区并行追踪／评测及既有修复，不包含其未完成代码。
- 后端 Java 21、项目 Maven 依赖；隔离 MySQL `127.0.0.1:33317/knowledge_mvp_test`，Chroma `1.1.0` 位于 `127.0.0.1:33318`。迁移仅执行于测试库，未迁移用户业务库。
- Ollama 使用既有服务与 `nomic-embed-text:latest`，digest 为 `0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f`、维度 768。未调用外部生成供应商进行效果验收。
- 页面运行于任务专用 Vite 15173，Playwright 驱动真实前端，API 使用受控拦截；不是前后端完整 HTTP 验收。另一个项目的 5173 未用作本功能证据。
- 原始证据目录：`.evidence/private-knowledge-mvp-20261107/`。此目录含测试日志和截图，不随业务源码提交；下面保留可复现命令与证据适用范围。

## 2. 已执行检查

| 检查 | 结果 | 证据与边界 |
|---|---|---|
| 独立交付树后端定向测试 | 18 类、65 项，0 失败／错误／跳过，退出码 0 | `release-backend-final.log`、该树 Surefire XML；涵盖数据库、真实索引、生成回归和下述新增测试 |
| 独立交付树前端测试 | 11 项通过 | `release-tree/release-frontend-tests.log`；不混入主树追踪测试的计数 |
| 独立交付树类型检查＋生产构建 | 通过 | `release-tree/release-frontend-build.log`；保留既有大 chunk 提示 |
| 新知识文件 lint | 通过 | `release-knowledge-lint.log`；受影响既有 AppChatPage 有 7 处原有未使用变量，与 HEAD 对照一致，见下节 |
| 真实向量链路 | 通过 | KnowledgeRealIndexTest：真实 Ollama、Chroma、MySQL；阈值设为 0，只证明上传／发布／隔离／替换，不证明相关性 |
| 六组合实际接入 | 6 项通过 | KnowledgePipelineIntegrationTest：真实 runner、工厂、解析／文件工具、工作流图、VueRepairService；代码模型、素材调用、构建和预览边界受控 |
| 图片规划隔离 | 通过 | KnowledgeModelProjectionTest 执行实际 TaskChatServices；图片规划请求不含资料，不标记来源提交 |
| 容器超限上传 | 通过 | KnowledgeMultipartLimitTest 启动真实内嵌 Tomcat，发送超限 multipart；不是预构造 MultipartFile；返回容量参数错误 |
| 浏览器管理与选择 | 通过所列场景 | 上传、替换失败保留旧版、重试、首页向首次任务传 true、刷新关闭、未确认请求恢复原 true、删除后来源片段隐藏；`browser-delete.log`、截图等 |
| 界面独立审查 | ship，限界面范围 | 6 张 desktop/mobile 截图；知识管理、首页、工作台无本次新增溢出或遮挡；documenter 确认沿用现有 tokens，未改设计系统 |

六组合接入测试中，工作流 HTML／多文件经历质检失败后重生成；Vue 两模式经历受控构建失败后真实修复调用与文件工具续轮。首轮生成之后把检索依赖改为“原文已删除”，后续请求仍带原快照且检索只发生一次。构建／预览在此测试中是替身，不把它称为真实页面业务验收。

## 3. 逐项验收与剩余范围

下表“受控通过”指列出的组件或接口接缝，不表示已部署或供应商质量通过。

| AC | 状态 | 证据与剩余项 |
|---|---|---|
| 01 | 受控通过 | Store 双用户管理／来源所有权、Controller 登录用户绑定；完整登录与非所有者管理员 HTTP 待人工验收 |
| 02 | 受控通过 | 真实 Chroma 用户 collection／build 过滤、检索后复核、工具拒绝额外 scope；模型上下文双用户真实生成待验收 |
| 03 | 受控通过 | 两用户相同内容上传，A 替换／删除不影响 B；真实数据库和索引测试 |
| 04 | 受控通过 | 真实入库、读回核对后发布；处理／失败版本不进入 activeVersion |
| 05 | 受控通过 | 格式、UTF-8、空白、Embedding 故障、索引不完整及重试；空白子块跳过但保留原文 |
| 06 | 受控通过 | 替换失败保留旧版、重试成功原子切换、同名不覆盖、版本名称稳定 |
| 07 | 受控通过 | 处理中删除、迟到发布拒绝、版本复核及固定上下文；完整远程检索竞态需持续回归 |
| 08 | 受控通过 | 字节／份数等额和超额、重复并发 10 次；真实 multipart 超限提示。并发使用缩小额度验证同一算法 |
| 09 | 受控通过 | 两入口传参及六组合实际生成接入；真实供应商六组合仍待人工验收 |
| 10 | 受控通过 | 关闭路径原有回归、默认字段兼容、刷新关闭、已发请求固定、相同请求号禁止更改选择 |
| 11 | 受控通过 | 六组合中固定片段跨工具轮／重生成／Vue 修复；删除后同次继续使用捕获快照；不代表真实模型会正确遵守资料 |
| 12 | 逻辑通过，质量未通过 | 空库／故障／受控无命中停止、协议最多纠正一次；现有模型对中文无关需求可能错误命中 |
| 13 | 受控通过 | Embedding／索引失败不发布、无静默替代、生成前门禁；各真实服务断连的完整 HTTP 场景待人工验收 |
| 14 | 受控通过 | 请求投影、片段持久化、来源提交状态及补写恢复；浏览器展示来自受控 API |
| 15 | 受控通过 | 将记录时间设为 30 天前仍可查询，版本替换不改变已存快照；无 TTL |
| 16 | 受控通过 | 数据库清空片段、迟到保存继续隐藏，浏览器显示名称版本及已删除提示 |
| 17 | 受控通过 | 删除应用后立即拒绝查询／提交来源，维护清理头和明细且个人库保留 |
| 18 | 受控通过 | 本地 Embedding 真实请求、UI 资料流向说明、原始日志启用时门禁、图片规划不接收片段；外部生成实际调用待验收 |
| 19 | 未执行／前置质量失败 | 中文检索校准失败；未运行真实生成模型＋模拟接口＋浏览器业务断言 |
| 20 | 部分通过 | 工具参数不能扩大用户范围，资料以数据投影、旧版替换生效；真实恶意文档／矛盾资料生成尚未验收，不承诺冲突裁决 |

## 4. 历史：Nomic 中文相关性结果（2026-11-07）

`chinese-calibration.json` 固定三份资料：商城订单接口、员工报销、花店积分；五条中文查询。使用当前模型前缀，分数按 `(cosine+1)/2` 计算。

| 查询 | 对应资料／得分 | 判断 |
|---|---|---|
| 明确提及星海商城订单接口 | 订单资料 0.85317 | 正例命中 |
| 五千元以上报销审批 | 报销资料 0.85479 | 正例命中 |
| 宠物疫苗预约 | 报销资料 0.86282 | 无关负例得分更高 |
| 泛化商城订单列表需求 | 订单 0.79672，花店 0.8314 | 排名不正确 |

因此不存在一个简单阈值同时保留这些正例、拒绝全部负例。示例 minScore=0.65 不具备已校准含义。保持默认关闭，等待用户对其他本地模型对比的决定；不能通过只测正例或降低阈值宣布完成。

## 5. 审查修正与已知限制

本次独立审查发现并修正：图片规划误投影私有片段、明细补写将已提交状态倒退、容器超限上传只报系统错误。另修正替换期间旧版文件名漂移、空白子块入库失败。各修正均有复现失败与通过日志：`final-privacy-*`、`final-reference-*`、`final-multipart-*`、`final-edge-*`；最终以交付树完整回归为准。

- 测试日志仍有 HEAD 既有的关闭竞态：服务关闭 historyMonitor 后任务收尾可能提交历史补记任务而触发 RejectedExecutionException。测试断言通过；任务终态及待补记事实已先写 Redis，下次启动通过 pendingHistory 扫描补记。本需求未改该生命周期，不把异常隐去或声称日志完全干净。
- 受影响既有 AppChatPage 的 7 处 unused-vars 在 HEAD 已存在；新增知识文件单独检查。构建有既有包体积提示，Mockito 有 JDK 动态加载提示。
- 首期管理页一次读取 50 份，当前使用说明限定 maxDocuments 不高于 50；单文件上限还受容器请求总量 24 MB 约束。不支持只提高业务配置就任意扩大容量。
- 完整知识专用追踪指标未接入；使用任务阶段事件与实际模型用量。长期来源独立于追踪。
- 文档语法级分块、模型切换的批量迁移工具不在本次实现内。模型 profile 改变不能混用原索引。

## 6. 复现命令与人工验收

先启动独立测试数据库和 Chroma，不连接业务库。在独立交付树执行：

```bash
./mvnw -q \
  '-Dtest=Knowledge*Test,OllamaKnowledgeEmbeddingTest,ChromaKnowledgeIndexTest,GenerationTaskServiceTest,UsageTrackingStreamingModelTest,FileToolSafetyTest' \
  '-Dknowledge.test.mysql.url=jdbc:mysql://127.0.0.1:33317/knowledge_mvp_test?useSSL=false&allowPublicKeyRetrieval=true' \
  -Dknowledge.test.chroma.url=http://127.0.0.1:33318 \
  -Dknowledge.test.real=true test
cd ai-code-frontend
yarn test
yarn build
```

数据库测试会清空指定测试库的知识表与 app 夹具；必须使用专用隔离库。未运行包含真实模型的全量 Maven 测试。

业务环境启动、迁移、两用户操作、真实生成和每步预期见 [USER_GUIDE.md](USER_GUIDE.md)。其中中文质量、真实供应商生成、完整登录 HTTP 与页面业务交互仍需执行；它们不计入上述通过数量。

## 7. 代码同步

功能提交 `2e3dc3f1c7ab3fce8236d269cf36c213bd0a953c` 已正常推送 `origin/main`，远端引用核对一致。未部署，未迁移业务数据库。同步范围为本需求 64 个文件，不包含主工作区并行追踪／评测和既有修复。后续文档同步不改变上述被测代码。当时产品验收保持 PARTIAL、默认关闭；后续模型决定及执行结果见第 9 节。

## 8. 历史：模型对比准备（2026-11-07，当时尚未下载／切换）

2026-11-07 再次核对本地 `/api/tags`，仍仅有原 nomic 模型。新建本地校准脚本 `model-research/compare_embedding.py`，复用原三份文档／五条查询，以同一前缀规则、`truncate=false`、模型 digest 与余弦分数计算重新执行；结果可复现：正例对应最低分 0.79672、无关负例最高分 0.86282，泛化订单需求排名错误，门槛差为 -0.06610。本工具只接受已安装的本地模型；对未安装 BGE 的调用以参数错误退出，未执行下载。原始资料与结果均在既有 `.evidence/private-knowledge-mvp-20261107/model-research/`。

推荐先对比 `bge-m3:latest`，而不是直接把它设为合格默认值。理由是官方说明其支持多语种、1024 维、8192 Token，并且查询无需额外指令；与当前适配器对非 Nomic 模型不加前缀的处理一致。Ollama 模型库显示约 1.2 GB；本机 24 GiB 内存、可用磁盘约 195 GiB，仅说明下载容量具备条件，不等于实际推理性能已测。

备选 `qwen3-embedding:0.6b` 在 Ollama 库约 639 MB；官方建议查询使用任务指令。当前适配器未实现该前缀策略，因此不能仅修改模型名便声称遵循其推荐用法。若选择它，应先明确查询指令、更新 profile 身份并增加相应契约检查。首轮优先保持接入简单的 BGE；若其保留样本效果不足，再评估备选，不引入重排或混合检索扩展。

当时比较仍须先取得用户对下载／模型选择的决定。预定获准后依次执行：隔离对比 → 新增独立保留集与困难负例 → 阈值校准 → 新 profile 重建隔离索引 → 真实生成与业务验收。五条既有样本即使通过，也只能作为必要检查，不能单独批准启用。默认关闭与 PARTIAL 状态保持不变。

官方依据（核对日期 2026-11-07）：[Ollama BGE-M3 模型库](https://ollama.com/library/bge-m3)、[BAAI 模型卡](https://huggingface.co/BAAI/bge-m3)、[Ollama Qwen3 Embedding 模型库](https://ollama.com/library/qwen3-embedding)、[Qwen 0.6B 模型卡](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B)、[Ollama API](https://github.com/ollama/ollama/blob/main/docs/api.md)。本次公开页面检索工具未返回内容，以上来自直接读取官方页面及 Context7 官方 API 资料；未用排行榜替代本机测试。

## 9. BGE-M3 切换与实测（2026-11-08）

用户批准使用 Ollama 安装 BGE-M3 并替换嵌入模型。已执行 `ollama pull bge-m3:latest`，原 Nomic 模型保留；Java 默认值、配置示例与本机未跟踪的 `application-local.yml` 已指向 BGE。`knowledge.enabled=false` 保持不变，后端重新启动时加载新配置。本次没有启动或重启业务后端，没有迁移业务数据库，也未进行真实外部生成调用。

### 9.1 模型与资源

- 模型：`bge-m3:latest`，F16，566.70M 参数，实际输出 1024 维。
- digest：`7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`。不能只用可变的 latest 标识实验版本。
- 主机：Apple M4 Pro、24 GiB 统一内存。Ollama 模型磁盘大小为 1,157,672,605 字节，约 1.16 GB／1.08 GiB。
- 首次调用前 `/api/ps` 无已加载模型；首次向量化实测约 1.588 秒。加载后 `/api/ps` 的 `size` 与 `size_vram` 均为 673,049,476 字节，约 642 MiB；这是 Ollama 报告的模型加载分配值，**不是整个进程常驻内存或系统峰值**，统一内存下也不应把二者相加。
- 17 条预热后短查询：端到端 HTTP 耗时中位约 28 ms，最慢约 45 ms。
- 10 次 512 字符文档向量化、两个并发 worker：单次 HTTP 耗时中位约 77 ms，最慢约 169 ms；不包含 MySQL、Chroma 写入或代码生成。该小样本不是容量或持续负载测试。
- 模型元数据上下文上限为 8192；此次 `/api/ps` 显示运行上下文 4096。只验证了当前短查询和 512 字符分块，没有据此宣称实测完整 8192 Token 输入。

原始证据：`.evidence/bge-m3-switch-20261108/pull.log`、`measurement.json`、`measurement.log`。测量全程使用本机 `127.0.0.1:11434`，模型 digest 前后相同。

### 9.2 中文检索质量：有改善，仍未完全通过

固定原三份文档与五条校准查询，先由校准集选定候选阈值，再检查预先编写的十二条保留查询。分数仍为 `(cosine + 1) / 2`。校准集正例最低 0.84453、无关负例最高 0.71549，区间中点取三位小数为 **0.780**；默认 minScore 与配置示例同步采用该候选值。没有用保留集反复寻找恰好全过的阈值。

| 集合 | 结果 | 含义 |
|---|---|---|
| 原校准集 | 5/5；3 条正例第一名均正确，2 条负例被拒绝 | 原 Nomic 的错排与高分无关命中在这些样本上得到改善 |
| 独立查询保留集 | 11/12；6 条正例通过、6 条负例通过 5 条 | 只覆盖同三份短文档的新查询，不代表跨文档或完整用户分布 |
| 未通过查询 | “展示企业员工的年假余额与请假审批流程。”匹配报销文档，得分 0.81362 | 审批语义相近导致误匹配，不能认为资料支持该需求 |

保留集中订单分页正例仅为 0.81580，与上述负例很接近。直接提高阈值可能损失有效查询，不能将其视为稳健解决方案。当前不增加重排、混合检索或新的判别模型，仍按既有范围保留默认关闭；后续扩大语料并评估明确的相关性判定方案，才能解除质量限制。

可提交、可复现的样本为 `src/test/resources/knowledge/embedding-quality.json`，工具为 `scripts/check_knowledge_embeddings.py`：

```bash
python3 scripts/check_knowledge_embeddings.py \
  --model bge-m3:latest --threshold 0.78 \
  --output /tmp/bge-quality-check.json
```

输出路径必须不存在。最新运行 `reproducible-quality.json`／`.log` 与初次测量一致，退出码 **1** 是实际质量未通过；不是执行或模型安装失败。此脚本直接验证向量排名与门槛，不代表真实生成模型编写代码的质量。

### 9.3 自动验证与索引兼容性

- 协议测试先出现预期红灯：默认仍选旧模型，在仅提供 BGE 的本地 HTTP 夹具中不能向量化；修改默认值后通过。两种模型分别验证原文／专用前缀、禁止截断及稳定 profile，见 `protocol-red.log`、`protocol-green.log`。
- 真实 BGE 测试使用原隔离 MySQL `33317/knowledge_mvp_test` 与 Chroma `33318`，新建测试用户空间，验证 1024 维、三份中文资料完整发布、正确订单／报销命中、两种无关需求无命中。已有真实索引测试也以新默认模型再次验证用户和版本隔离。
- 另建 Nomic 测试空间，BGE 查询返回 `EMBEDDING_PROFILE_CHANGED`。尝试用 BGE 替换文档也不能发布到旧空间，旧 activeVersion 与原 profile 保持有效。没有自动清空或强制改写旧空间；本次并未实现批量迁移功能。
- 定向后端回归 **15 类、53 项，0 失败／错误／跳过，退出码 0**。证据 `backend.log`、`backend-summary.json`。包含知识库全部现有测试及新增真实模型测试；六组合代码模型仍为受控实现，不能升级为真实供应商验收。
- 日志仍出现已记录的 generation-task 关闭后 historyMonitor 提交拒绝，以及受控工作流缺少截图 Bean 的警告；本次未修改这些路径，所有测试断言通过。没有执行会访问外部供应商的全量 Maven 测试；没有前端改动，不重复运行浏览器验收。

复现后端定向验证（测试库必须是隔离库，Store 测试会清空其中夹具）：

```bash
./mvnw -q \
  '-Dtest=BgeKnowledgeRealIndexTest,Knowledge*Test,OllamaKnowledgeEmbeddingTest,ChromaKnowledgeIndexTest' \
  '-Dknowledge.test.mysql.url=jdbc:mysql://127.0.0.1:33317/knowledge_mvp_test?useSSL=false&allowPublicKeyRetrieval=true' \
  -Dknowledge.test.chroma.url=http://127.0.0.1:33318 \
  -Dknowledge.test.real=true test
```

上述测试要求本地 BGE 与 Nomic 均存在，后者仅用于验证旧索引兼容性边界。真实业务环境还需按 [USER_GUIDE](USER_GUIDE.md) 完成登录、上传、六种生成组合与页面业务断言；质量失败项必须如实保留。
