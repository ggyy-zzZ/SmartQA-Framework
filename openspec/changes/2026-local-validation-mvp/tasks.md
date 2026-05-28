# Tasks: 本地验证 MVP

## Sprint 1 — 基础设施（当前）

- [x] 编写 `docs/local-validation-mvp-roadmap.md`
- [x] 创建本 change 目录（proposal + tasks）
- [x] Flyway `V3__sync_entity_state.sql`
- [x] `scripts/enterprise_pipeline/sync_manifest.yaml`
- [x] `scripts/enterprise_pipeline/sync_common.py`（stable id、content hash）
- [x] `sync_vectors_qdrant.py`：稳定 point id + payload 增 domain/entity_type
- [x] `build_knowledge_from_mysql.py`：`--since`、`--company-ids`
- [x] `run_incremental_sync.py` 编排脚本
- [x] `QaConversationService`：多轮实体继承泛化（非证照绑定）
- [x] `QaAskFlowService`：响应增加 `routing` 字段
- [x] 更新 `scripts/enterprise_pipeline/README.md` incremental 说明
- [x] 更新 `openspec/backlog.md`

## Sprint 2 — 增量闭环

- [x] 启用 Flyway（`application.properties` + `AssistantFlywayMigrationRunner` 成功日志）
- [x] Flyway `V4__sync_tracking.sql`
- [x] `SyncEntityStateService`
- [x] `EnterpriseKnowledgeSyncService` + `LocalKnowledgeOpsService.runIncrementalSyncScript`
- [x] 重写 `ScheduledSyncService`（EKSP incremental）
- [x] `POST /qa/learn/knowledge-sync/incremental`
- [x] content_hash 跳过未变实体
- [ ] tombstone / 软删传播
- [ ] 验收 K1

## Sprint 3 — 意图稳定化

- [ ] lexicon 路由词合并进 business-rules
- [ ] 按 queryType 的通用 evidence 闸门（扩展 `QaAnswerGateService`）
- [ ] `data/eval/routing_cases.jsonl` + 跑批脚本
- [ ] 验收 K2

## Sprint 4 — 联调归档

- [ ] Playground routing / evidence 展示增强
- [ ] 稳定行为写回 `spec.md`
- [ ] 归档本 change
