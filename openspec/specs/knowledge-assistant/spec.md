# knowledge-assistant

面向「学习知识 → 回答问题 → 必要时追问补全 → 自检与沉淀」的通用知识库助手；检索底座为 Qdrant、Neo4j、MySQL，生成底座为 MiniMax。

### Requirement: 原始文档与轻量结构化学习

系统 SHALL 支持用户通过 API 上传或提交**文本类知识**（如 Markdown、纯文本），并将其纳入可检索知识（向量 / 图谱 / 文档通道按实现配置）。

系统 SHALL 支持接入**简单的结构化数据**（行数在部署配置的上限内，默认不超过 10000 行），用于受控 SQL/表检索，而不在提示词中假设某一固定行业 schema 名称。

#### Scenario: 上传文本学习

- **WHEN** 用户提交合法文本学习内容  
- **THEN** 系统将其持久化或索引到已配置的知识槽位，并可在后续问答中被检索引用  

#### Scenario: 结构化数据在上限内

- **WHEN** 管理员配置的 MySQL schema 中存在业务表且行数在配置上限内  
- **THEN** 系统允许通过受控 SQL / 表检索参与证据组装  

---

### Requirement: MySQL `assistant` schema（部署默认）

本地与推荐默认部署 SHALL 使用独立 MySQL 库 **`assistant`**（与 `qa.assistant.mysql-url` 默认库名、`qa.assistant.mysql-schema` 一致），以隔离问答助手相关表与示例业务表。

系统 SHALL 在 `assistant` 中维护至少：

- **`qa_active_knowledge`**：主动学习写入与关键词检索；索引建议包含 `(scope, created_at)`、`created_at`。
- **`qa_pending_knowledge`**：未知意图 / 证据不足时的待沉淀队列（与 jsonl 并行）。
- **`qa_user_feedback`**：点赞/点踩等反馈持久化（与文件日志并行）。
- **`employee`**、**`company`**（默认物理表名；可通过 `qa.assistant.mysql-person-role-employee-table` / `qa.assistant.mysql-person-role-company-table` 覆盖）：`SqlQueryService` 人员-角色预检与部分 SQL 生成路径依赖；`company` 表 SHALL 含可读的 `company_name`、若干角色外键列（如 `legal_rep_id` 等，与实现中 `ROLE_COLUMN_LABELS` 可映射列一致）、可选 `deleteflag`。
- 其它业务表由部署方按需扩展；**`qa_` 前缀表**视为系统表：行级 MySQL 扫表与发给 LLM 的 schema 摘要 SHALL 排除这些表，避免与专用读写路径重复、降低误生成 DML 的风险。

初始化脚本 SHALL 由仓库提供：`data/sql/mysql/assistant_bootstrap.sql`（可重复执行；**基线表与示例数据**以此为主）。

可选增量 DDL：应用 MAY 在 `qa.assistant.flyway-enabled=true` 时于启动执行 `classpath:db/migration/assistant`（当前脚本与 `qa_pending_knowledge` / `qa_user_feedback` 等扩展表对齐）；与手工脚本的职责划分见 `openspec/AGENTS.md`。

#### Scenario: 新环境初始化 MySQL

- **WHEN** 运维在实例上已创建空库 `assistant`  
- **THEN** 执行 `assistant_bootstrap.sql` 后应用即可连接并完成主动学习落库、人员角色预检与通用扫表/SQL 证据检索（在表结构与权限满足的前提下）  

#### Scenario: 结构化表行数接入前校验

- **WHEN** 运维或流水线在接入业务表前调用 `POST /qa/structured/row-audit` 并提交表名列表  
- **THEN** 系统返回各表 `COUNT(*)` 及是否超过 `qa.assistant.max-structured-ingest-rows`；`qa_` 前缀系统表不得参与审计  

#### Scenario: 结构化接入能力边界（当前阶段）

- **WHEN** 部署方判断除行数审计外是否还存在「强制入库流水线」  
- **THEN** 本规格 SHALL 提供 `POST /qa/structured/ingest-gate` 与 `POST /qa/structured/job/*` 及可选定时任务，对表集合做行数门禁并返回 **`allowedToProceed`**；超限时 SHALL 拒绝「继续入库编排」语义（`allowedToProceed=false`）  
- **THEN** **对 MySQL 业务表的 DML/LOAD 写入** SHALL **不在**本应用默认实现范围内，由外部 ETL 在门禁通过后执行；**CSV 经行数门禁后写入主动学习通道**见下条 Scenario；编排说明见 `openspec/design/structured-ingest-gate.md`

#### Scenario: 结构化入库门禁与作业日志

- **WHEN** 运维调用 `POST /qa/structured/ingest-gate` 或 `POST /qa/structured/job/run` 并提交表名列表或清单  
- **THEN** 系统 SHALL 返回与行数上限对齐的审计结果及统一拒绝原因码；可选将运行结果以 JSON 行追加到配置的日志路径  
- **THEN** 当 `qa.assistant.structured-ingest-schedule-enabled=true` 且已配置 manifest 路径时，系统 SHALL 按 Cron 触发同等门禁逻辑并写日志（实现为 `StructuredIngestScheduler`）

#### Scenario: CSV 结构化学习与行数拒绝

- **WHEN** 客户端上传 `.csv` 并调用 `POST /qa/structured/csv-ingest`（可选用首行表头）  
- **THEN** 系统 SHALL 按非空数据行计数与 `qa.assistant.max-structured-ingest-rows` 比对；超限时 SHALL 返回 `rejected` 及原因，**不得**调用主动学习持久化  
- **THEN** 未超限时 SHALL 将 CSV 包装为可学习文本并走与手动学习相同的多路持久化（`StructuredCsvIngestService` → `ActiveLearningService`）；**不**将 CSV 直接写入 MySQL 业务表；对含引号内换行等复杂 RFC4180 形态 **不作**完整解析保证（以实现类说明为准）

#### Scenario: MySQL 元数据目录与可选主动学习

- **WHEN** 客户端调用 `POST /qa/mysql/schema-catalog` 且仅依赖已配置的 `qa.assistant.mysql-*`（**不得**在请求体中传入任意 JDBC）  
- **THEN** 系统 SHALL 只读查询 `information_schema`，生成 Markdown 目录（表数受 `qa.assistant.max-schema-export-tables` 约束，正文受 `max-schema-export-chars` 约束，可截断）；`qa_` 前缀系统表 SHALL 排除在业务表清单之外  
- **THEN** 当请求体 `persist=true` 时，系统 SHALL 将上述 Markdown 作为文本知识调用 `ActiveLearningService` 写入主动学习通道；`persist=false` 时 SHALL 仅返回目录不持久化；详细设计见 `openspec/design/mysql-schema-active-learning-pipeline.md`
- **THEN** 当请求体 `assess=true` 时，系统 SHALL 调用已配置的大模型 API 生成沉淀评估，并体现在 `combinedMarkdown` 与 `modelAssessment` 等响应字段中；模型失败时 SHALL 降级为仅目录写入（若同时 `persist=true`）

#### Scenario: 结构化沉淀方案 JSON 与按路写入

- **WHEN** 客户端调用 `POST /qa/mysql/sedimentation/pipeline`，`source` 为 `configured`（仅 `qa.assistant.mysql-*`）或 `dynamic`（与 `/qa/mysql/connect` 同形的 host/port/database/username/password）  
- **THEN** 系统 SHALL 只读导出 schema 目录（与 `schema-catalog` 同源规则：排除 `qa_` 系统表、受导出表数与字符上限约束）  
- **THEN** 系统 SHALL 调用大模型生成**单一 JSON 对象**，字段含 `feasible`、`feasibilityRationale`、`confidence`、`planSummaryMarkdown`、`sinks`（mysql/qdrant/neo4j 的 `enabled` 与 `rationale`，neo4j 含 `keywordLimit`）、`ingest`（`bodyStrategy`、`titleHint`）；解析失败时 SHALL 返回 `ok: false` 与可读 `message`  
- **THEN** 当 `feasible=false` 或三路 `enabled` 均为 false 时，系统 SHALL **不**调用主动学习持久化  
- **THEN** 当 `persist=true` 且方案可行且至少一路 `enabled=true` 时，系统 SHALL 按 `ingest.bodyStrategy` 选择正文：`model_digest` 为二次模型生成 Markdown，`catalog_as_is` 为原始目录（可截断）；再调用 `ActiveLearningService` 按方案**选择性启用** MySQL / Qdrant / Neo4j 写入（物理形态仍为应用内置白名单：表 `qa_active_knowledge`、既有 Qdrant collection、既有 LearnedKnowledge 子图），详细设计见 `openspec/design/schema-sedimentation-plan-pipeline.md`

---

### Requirement: Neo4j 与 `assistant` 对齐的图谱资产

系统 SHALL 在 Neo4j 中维护与 `GraphContextService` 查询一致的节点与关系类型（如 `Company`、`Person`、`Shareholder`、`ProductLine`，关系 `HAS_ROLE_IN`、`HOLDS_SHARES_IN`、`BELONGS_TO_PRODUCT`），以及主动学习写入的 **`LearnedKnowledge` / `LearnedKeyword`** 子图。

**部署约定**：推荐运行 **Neo4j 5.x**（Community 即可）；若使用 4.x，部署方 SHALL 自行核对 `assistant_bootstrap.cypher` 与驱动 API 的语法/行为差异（与 `openspec/AGENTS.md` 一致）。

约束与示例子图脚本 SHALL 由仓库提供：`data/neo4j/assistant_bootstrap.cypher`（按需执行；示例数据与 MySQL 示例在业务语义上对齐即可，不要求跨库主键相等）。

#### Scenario: 新环境初始化图谱

- **WHEN** 部署需要可运行的图谱检索与主动学习图写入  
- **THEN** 可按文档执行 `assistant_bootstrap.cypher` 创建约束/索引及可选示例节点，使公司名提示检索与关系展开查询可用  

---

### Requirement: 意图分析

系统 SHALL 对用户每轮自然语言问题进行**意图与检索路径**判定（例如文档、向量、图谱、结构化、统计 SQL、混合），且路由提示 SHALL 保持领域中立，不绑定单一商业产品话术。

#### Scenario: 路由失败时兜底

- **WHEN** 大模型路由不可用或返回非法 intent  
- **THEN** 系统使用与具体业务产品无关的规则兜底并完成检索  

---

### Requirement: 回答组织与追问

系统 SHALL 在证据基础上生成回答，并在信息不足时**优先通过追问**引导用户补充关键信息，而非假设某一固定业务字段（如特定 ERP 字段名）。

#### Scenario: 证据不足

- **WHEN** 检索结果无法支撑可靠结论  
- **THEN** 系统返回中性、可操作的补充信息请求或澄清，而非写死某一类客户对象称谓  

---

### Requirement: 结果整理

系统 SHALL 对检索证据与模型输出进行统一封装（如 turnId、证据列表、路由、置信度），便于日志与前端展示。

#### Scenario: 多轮会话

- **WHEN** 客户端携带 `conversationId` 发起追问  
- **THEN** 系统将上文与本轮问题结合用于检索与生成，并在响应中回传会话标识  

---

### Requirement: 回答与提问对齐（自检）

系统 SHALL 在能力范围内判断回答是否被证据支持；若模型偏离证据，应通过提示词约束与降级模板减少幻觉。

系统 SHALL 在问答响应中附带**轻量自检元数据** `evidenceAlignment`，供前端展示或离线审计；字段 SHALL 至少包含：`keywordOverlap`（数值型重合度信号）、`lowOverlap`（布尔）、`warnings`（启发式告警字符串列表）。实现以 `EvidenceAlignmentService` 为准；该信号 **SHALL NOT** 单独作为唯一安全闸门，须与提示词与降级路径配合使用。

#### Scenario: 无证据时的生成

- **WHEN** 证据为空或不足  
- **THEN** 系统不得伪造事实；应明确不确定性或触发追问 / 待学习队列（按实现）  

---

### Requirement: 自主沉淀与用户行为学习

系统 SHALL 支持将「未知意图 / 证据不足」等问题样本写入待沉淀队列，并支持用户显式「记住/学习」类指令写入个人或企业知识（与 scope 配置一致）。

系统 SHALL 为后续扩展保留**用户习惯与反馈**（如点赞/点踩）接口；在 MySQL 启用时 SHALL 同步写入 `qa_user_feedback`（与文件日志并行），未启用时仍可仅依赖文件事件。

#### Scenario: 用户显式教学

- **WHEN** 用户使用学习触发语并提供可解析的事实文本  
- **THEN** 系统写入配置的知识存储通道并返回各通道结果状态  

#### Scenario: 待沉淀样本进入 MySQL 队列

- **WHEN** 系统因未知意图或证据不足触发知识沉淀标记  
- **THEN** 样本 SHALL 写入 `qa_pending_knowledge`（MySQL 已启用且连接可用时），并保留原有 jsonl 候选日志；客户端或运维可通过 `GET /qa/sedimentation/pending` 拉取待处理条目（分页与状态机扩展可后续迭代）  

---

### Requirement: 实现模块化边界（代码组织）

为实现与上文各条 **Requirement** 一一对应的演进路线，代码库 SHALL 按**能力域**划分模块边界；**当前阶段**允许在单体（单 Spring Boot 应用）内以 **Java 子包 + 独立 Spring Bean** 落实边界，不强制拆分为多进程或多仓库。

#### 模块列表与职责

| 模块 | 职责摘要 | 主要对应规格章节 |
|------|----------|------------------|
| **学习与接入**（Learning & Ingest） | 文本类知识接入与索引；结构化数据接入流水线；行数上限与分页/索引策略（与配置 `maxStructuredIngestRows` 等一致） | 原始文档与轻量结构化学习；MySQL `assistant` |
| **意图与路由**（Intent & Routing） | 每轮意图与检索路径判定；路由失败时的规则兜底；意图结果的可观测字段 | 意图分析 |
| **检索编排**（Retrieval Orchestration） | 文档 / 向量 / 图谱 / MySQL 扫表 / 生成 SQL 等多路调度、融合、截断与降级顺序 | 意图分析；MySQL；Neo4j（检索侧） |
| **回答与追问**（Answer & Clarification） | 基于证据的生成；信息不足时的追问策略与用户可见话术（须走集中提示词与配置） | 回答组织与追问 |
| **结果整理**（Response Assembly） | turnId、证据列表、路由、置信度、各通道状态；多轮 `conversationId` 与上文合并 | 结果整理 |
| **对齐与自检**（Evidence Alignment） | 回答与证据一致性约束；无证据时的降级与反幻觉 | 回答与提问对齐（自检） |
| **沉淀与反馈**（Sedimentation & Feedback） | 待沉淀队列；显式「记住/学习」写入多通道知识；点赞/点踩等从日志到持久化 | 自主沉淀与用户行为学习 |

#### 横切关注点

以下能力 SHALL 跨模块复用并保持单一事实来源：

- **配置与中立的助手话术**：`qa.assistant.*`、`KnowledgeAssistantPrompts`（及后续等价集中配置）。  
- **安全**：MySQL 只读 SELECT、生成 SQL 校验、运行时账号最小权限。  
- **OpenSpec 流程**：较大功能在 `openspec/changes/<change-id>/` 跟踪，稳定后合并回本 `spec.md`；后续任务清单见 `openspec/backlog.md`。

#### 落地建议（非规范强制）

- 推荐 Java 包级前缀（**已实现**）：`core`、`config`、`intent`、`retrieval`、`learning`、`answer`、`orchestration`、`response`、`web`、`alignment`、`sedimentation`。其中 `alignment`（如 `EvidenceAlignmentService`）、`sedimentation`（如 `SedimentationQueueService`、`FeedbackPersistenceService`）已有 Bean 落地；名称可按团队习惯调整，但 SHALL 与上表职责一一对应。  
- **控制器**仅负责 HTTP 与 DTO 适配（`QaController` 已精简）；主流程在 `QaAskOrchestrator`；SSE 事件封装在 `QaSseStreamSupport`。其它可选拆分见 `openspec/backlog.md`。

#### Scenario: 新功能归属模块

- **WHEN** 需要新增或修改某一用户可见能力  
- **THEN** 实现者先在本文档模块表中定位职责域，再改对应包与 Bean，并在 `openspec/changes/<change-id>/tasks.md` 中记录；若跨多域，则拆分为多条任务或先补 `proposal.md` 说明依赖关系  

---

### Requirement: OpenSpec 变更管理

功能级变更 SHALL 在 `openspec/changes/<change-id>/` 下维护 `proposal.md` 与 `tasks.md`，并在实施完成后将稳定能力合并描述回 `specs/`。

较大范围的后续工作清单 SHALL 维护于 `openspec/backlog.md`，与本 `spec.md` 同步更新优先级与状态（由维护者在迭代中勾选或迁移至具体 `changes/<id>/tasks.md`）。
