# Tasks: 通用知识库助手

- [x] 建立 `openspec/` 目录与 `knowledge-assistant` 规格  
- [x] 新增 `KnowledgeAssistantPrompts` 集中管理路由 / SQL 生成 / 兜底话术  
- [x] `MiniMaxClient` 使用可配置助手名与统一系统提示  
- [x] `IntentRouterService` / `SqlQueryService` 引用集中提示词  
- [x] `QaAssistantProperties` 增加 `assistantName`、`maxStructuredIngestRows`  
- [x] `QaController` 用户可见兜底文案改为中性表述  
- [x] 按 OpenSpec 模块边界拆分包：`core`、`config`、`intent`、`retrieval`、`learning`、`answer`、`response`、`web`；`alignment`/`sedimentation` 占位（见 `openspec/project.md`）  
- [x] 将「意图 / 回答编排 / 自检」主流程从 `QaController` 拆出：`orchestration.QaAskOrchestrator` + `QaRetrievalPipeline` 等（见 `openspec/project.md`）  
- [x] 证据对齐：`alignment.EvidenceAlignmentService`，问答 JSON 含 `evidenceAlignment`（关键词重合度与告警）  
- [x] 结构化行数硬校验：`learning.StructuredTableRowAuditService` + `POST /qa/structured/row-audit`（完整 CSV/作业流水线仍属后续）  
- [x] 待沉淀 MySQL：`qa_pending_knowledge` + `sedimentation.SedimentationQueueService` + `GET /qa/sedimentation/pending`；反馈 `qa_user_feedback` + `FeedbackPersistenceService`  
- [ ] SSE 发送拆小类（可选）  
