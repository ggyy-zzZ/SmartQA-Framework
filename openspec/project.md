# 项目上下文

本仓库实现一个**通用知识库问答助手**：从用户提供的文档与适量结构化数据中学习，再基于 **MiniMax 大模型**、**向量库（Qdrant）**、**知识图谱（Neo4j）**、**MySQL** 做多路检索与回答。

## OpenSpec 文档索引

| 文档 | 用途 |
|------|------|
| [`AGENTS.md`](./AGENTS.md) | AI/开发者在仓库内工作的约定（规格优先、提示词、数据边界、安全、`assistant` 与 Neo4j 脚本路径） |
| [`specs/knowledge-assistant/spec.md`](./specs/knowledge-assistant/spec.md) | **产品与技术规格**：能力需求、场景、`assistant`/Neo4j 约定、**实现模块化边界** |
| [`backlog.md`](./backlog.md) | **后续任务排队**（P0/P1/P2 与技术债）；大项实施时拆到 `changes/<id>/` |
| [`changes/<change-id>/`](./changes/) | 单次变更的 proposal / tasks，归档后能力描述合并回 `spec.md` |
| [`design/structured-ingest-gate.md`](./design/structured-ingest-gate.md) | 结构化入库门禁、清单 JSON、定时任务与「不写业务表 DML」边界 |

- **不与**某一固定商业产品（例如历史上对接过的「同道查」类系统）在提示词或产品话术上强绑定；业务 schema 名（如 `tdcomp`）仅作为**可配置的部署参数**，不得写进面向用户的系统提示或假设用户意图的硬编码文案中。

## 技术栈

| 能力       | 技术 |
|------------|------|
| 大模型     | MiniMax（chatcompletion） |
| 向量检索   | Qdrant |
| 图谱检索   | Neo4j（Bolt） |
| 结构化检索 | MySQL（只读 SELECT / 受控生成 SQL） |
| 文档知识   | 本地/上传文本，编译为检索用文本 |

## 与代码的映射

当前实现按模块分布在 `com.qa.demo.qa` 下子包（Spring 从 `com.qa.demo` 扫描即可）：

| 子包 | 内容 |
|------|------|
| `core` | `ContextChunk`、`IntentDecision`、`CompanyCandidate`；`QaScopes`（scope 常量与归一化） |
| `config` | `QaAssistantProperties` |
| `intent` | `IntentRouterService`、`CompanyClarificationAdvisor` |
| `retrieval` | `GraphContextService`、`VectorContextService`、`MysqlContextService`、`SqlQueryService`、`DocumentContextService`、`QaRetrievalOrchestrator`、`QaRetrievalPipeline`（按意图多路检索与合并） |
| `learning` | `ActiveLearningService`、`ChatLearningCommandParser`、`LearningResponseBuilder`；**`StructuredTableRowAuditService`**（`POST /qa/structured/row-audit`）；**`StructuredCsvIngestService`**（`POST /qa/structured/csv-ingest`）；**`StructuredIngestJobService`**、**`StructuredIngestScheduler`**（可选 Cron；`POST /qa/structured/ingest-gate`、`/structured/job/run`；见 `openspec/design/structured-ingest-gate.md`）；**`MysqlSchemaCatalogService`**、**`MysqlSchemaCatalogAssessmentService`**（`POST /qa/mysql/schema-catalog`，可选 `assess` + `persist`；见 `openspec/design/mysql-schema-active-learning-pipeline.md`） |
| `answer` | `MiniMaxClient`、`QaAnswerFallbackService` |
| `orchestration` | `QaAskOrchestrator`（同步问答、SSE 流式、学习意图分支与检索-生成-落库闭环）、`QaSseStreamSupport`（SSE 事件封装） |
| `response` | `QaConversationService`、`QaLogService` |
| `web` | `QaController`（HTTP、DTO；含 `/structured/*`、`/mysql/schema-catalog`、`/sedimentation/pending`、`/feedback` 等） |
| `alignment` | `EvidenceAlignmentService`（关键词重合度与启发式告警） |
| `sedimentation` | `SedimentationQueueService`、`FeedbackPersistenceService` |

提示词集中维护于 `com.qa.demo.knowledge.KnowledgeAssistantPrompts`。
