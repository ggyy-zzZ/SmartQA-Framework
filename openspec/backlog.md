# 后续工作 backlog

与 `specs/knowledge-assistant/spec.md` 中 **实现模块化边界** 及尚未完全落地的需求对应。实施某条时建议在 `openspec/changes/<change-id>/` 新建 proposal/tasks，完成后将稳定行为写回 `spec.md` 并在此更新状态。

**状态说明**：`待办` | `进行中` | `已完成`（可将已完成行移至变更目录 tasks 或删除，避免重复）

---

## P0（与规格强相关，建议优先）

| 状态 | 模块 | 条目 |
|------|------|------|
| 已完成 | 学习与接入 | **结构化接入流水线（本应用范围）**：`POST /qa/structured/row-audit`、`POST /qa/structured/csv-ingest`；**`POST /qa/structured/ingest-gate`**、**`POST /qa/structured/job/run`**、**`POST /qa/structured/job/run-from-config`**；可选 **`qa.assistant.structured-ingest-schedule-*`** 定时清单门禁 + 作业日志（见 `openspec/design/structured-ingest-gate.md`）。**对业务表的 DML/LOAD 自动入库**不在本应用 SHALL 内，由外部 ETL 在 `allowedToProceed=true` 后执行；若需本进程内写业务表，另起变更与规格 |
| 已完成 | 沉淀与反馈 | **待沉淀队列** MySQL 表 `qa_pending_knowledge`、写入与 `GET /qa/sedimentation/pending`；与 jsonl 并行。**消费/审核/再学习状态机**可后续迭代 |
| 已完成 | 意图与路由 / 检索编排 / 回答与追问 | 主流程已迁至 `orchestration.QaAskOrchestrator`；检索管道 `retrieval.QaRetrievalPipeline`；学习指令 `learning.ChatLearningCommandParser`；澄清 `intent.CompanyClarificationAdvisor`；兜底 `answer.QaAnswerFallbackService`；`web.QaController` 仅 HTTP。SSE 事件封装在 `orchestration.QaSseStreamSupport` |

---

## P1（体验与可运维）

| 状态 | 模块 | 条目 |
|------|------|------|
| 已完成 | 沉淀与反馈 | **点赞/点踩**：`qa_user_feedback` 表 + `FeedbackPersistenceService`（与 jsonl 并行）；**分析看板/去重**可后续迭代 |
| 进行中 | 学习与接入 | **Flyway 可选迁移**已接入（`qa.assistant.flyway-enabled`、`db/migration/assistant`）；与 `assistant_bootstrap.sql` 的职责划分见 `openspec/AGENTS.md` 与 `spec.md`（基线+样例以 bootstrap 为主）。Liquibase 等价物不设为强制 |
| 已完成 | 检索编排 | `SqlQueryService` 人员-角色预检表名可配：`qa.assistant.mysql-person-role-employee-table` / `mysql-person-role-company-table`（默认 `employee`/`company`）；**多租户 / 全元数据驱动**仍为后续 |
| 已完成 | Neo4j 与部署 | 版本口径：推荐 **Neo4j 5.x**、4.x 须自行核对，已写入 `spec.md` 与 `AGENTS.md`。**业务主键/companyId 跨库命名规范**若需单列 Requirement 可另起 backlog |

---

## P2（质量与产品化）

| 状态 | 模块 | 条目 |
|------|------|------|
| 待办 | 对齐与自检 | 证据对齐加强：提示词 / 后处理 / 可选轻量二次 LLM；**无证据路径**集成测试与固定用例（当前已有 `evidenceAlignment` 关键词启发式） |
| 待办 | 回答与追问 | 追问策略 **可观测**：结构化记录「为何追问」（缺哪类槽位、哪路检索为空） |
| 待办 | OpenSpec | 已归档变更在 `proposal.md` 中标记说明；保持 **`spec.md` 为能力单一事实来源**，backlog 仅作排队视图 |

---

## 技术债（按需插队）

| 状态 | 条目 |
|------|------|
| 待办 | 向量集合等命名（如 `enterprise_*`）与「通用助手」定位统一为可配置前缀 |
| 待办 | **Testcontainers**（或同类）对 MySQL + Neo4j 最小链路的集成测试 |
| 待办 | 安全审计：SQL 白名单、schema 级权限、敏感列脱敏是否在 spec 中单列 Requirement 并落实 |

---

## 已完成参考（避免重复立项）

- 默认 MySQL 库/schema `assistant`、`assistant_bootstrap.sql`、`qa_` 表在扫表与 schema 摘要中排除（见 `changes/2026-05-11-assistant-schema/`）。
- 提示词集中化、助手名可配、`QaAssistantProperties` 中 `assistantName` / `maxStructuredIngestRows`（见 `changes/2026-05-11-neutral-knowledge-assistant/`）。
- 问答主流程编排迁至 `com.qa.demo.qa.orchestration.QaAskOrchestrator`，检索管道 `QaRetrievalPipeline`，`QaController` 仅保留 HTTP（2026-05-11 迭代）。
- 待沉淀队列 `qa_pending_knowledge`、反馈表 `qa_user_feedback`、行数审计 `POST /qa/structured/row-audit`、CSV 门禁学习 `POST /qa/structured/csv-ingest`、schema 目录 `POST /qa/mysql/schema-catalog`、响应体 `evidenceAlignment`（2026-05-11 起迭代）。
