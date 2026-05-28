# Proposal: 本地验证 MVP（知识落库 + 意图路由 + 简单回答）

## 动机

企业级知识库建设需分阶段落地。在本地验证阶段，优先打通：

1. **知识落库**：结构化主数据增量同步到 Neo4j / Qdrant，摆脱日常全量 wipe；
2. **意图路由**：问句（含多轮）稳定映射到 `queryType` 与槽位，驱动正确检索通路；
3. **简单回答**：保留证据闸门，暂不强化生成质量。

权限隔离、CDC、文档精细分块、回答模板化等明确推迟。

## 范围

### Track A — 知识落库

- Flyway：`sync_entity_state` 实体级同步状态表
- `scripts/enterprise_pipeline/sync_manifest.yaml`（`org_master`）
- Qdrant 稳定 point id；Neo4j / Qdrant incremental upsert（无日常 wipe）
- `build_knowledge_from_mysql.py --since`；`run_incremental_sync.py` 编排
- Sprint 2：`SyncEntityStateService`、重写 `ScheduledSyncService`、增量 HTTP API

### Track B — 意图路由

- `business-rules.json` 为 queryType 单一权威源（lexicon 逐步合并）
- 多轮槽位继承通用化（`EntityRef` / `retrievedEntities`，不绑定单一业务场景）
- 响应增加 `routing` 可观测字段
- Sprint 3：`routing_cases.jsonl` 场景矩阵评测

### Track C — 简单回答

- 维持 `QaAnswerGateService` 无证据不调 LLM
- Playground 展示 routing 与 evidence 分布（Sprint 4）

## 非目标

- 行级权限 / Qdrant ACL Filter
- Debezium CDC（后续 EKSP Phase 3）
- PDF 章节分块、入库脱敏
- GraphRAG、BM25 融合
- 列表答案模板化、红黄线合规

## 验收

- **K1**：修改 company → incremental sync → 问答基于新数据
- **K2**：routing 场景矩阵（单轮/多轮/缺槽/切换）可重复验证
- **K3**：无 evidence 时 `canAnswer=false`

## 参考

- [`docs/local-validation-mvp-roadmap.md`](../../../docs/local-validation-mvp-roadmap.md)
- [`docs/enterprise-knowledge-sync-platform.md`](../../../docs/enterprise-knowledge-sync-platform.md)
