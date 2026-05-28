# 企业知识同步平台方案（EKSP）

本文档描述从「一次性离线灌库」升级为**可增量同步、可域扩展**的企业知识库建设方案。针对当前两个核心缺口：

1. **数据变更无法同步到知识库**（Neo4j / Qdrant 仅全量 wipe 重建）
2. **tdcomp 域之外无扩展**（合同、商机、客户等无 Domain Pack 与灌库脚本）

与现有沉淀机制的关系见 [knowledge-sedimentation-guide.md](./knowledge-sedimentation-guide.md)。

---

## 1. 现状诊断

### 1.1 问题一：变更无法同步

| 环节 | 现状 | 缺口 |
|------|------|------|
| Python 灌库 | `run_pipeline.py` / `sync_neo4j.py --wipe` 全量删重建 | 无增量 upsert、无删除传播 |
| Java 定时同步 | `ScheduledSyncService` 每 30 分钟比对**行数** | 行数不变但内容变更检测不到；且只写占位 Markdown 到主动学习，**不更新** Neo4j / `enterprise_knowledge_v2` |
| 追踪表 | `sync_tracking` 仅记 `last_row_count` | 无 `updated_at` 水位、无内容 hash、无实体级版本 |
| Qdrant | 灌库 point id 不稳定 | 业务实体变更时难以同 id 覆盖更新 |

**结论：** Bulk 灌库与在线问答是两套割裂系统；现有「增量同步」未真正更新图谱与向量。

相关代码：

- `scripts/enterprise_pipeline/sync_neo4j.py`（`--wipe`）
- `src/main/java/com/qa/demo/qa/learning/ScheduledSyncService.java`
- `src/main/java/com/qa/demo/qa/learning/SyncTrackingService.java`

### 1.2 问题二：域无法扩展

`build_knowledge_from_mysql.py` 是以 **Company 为锚**的单体脚本，硬编码表包括：

- `company`、`certificate_management`、`seal_management`
- `company_shareholder_info`、`company_directors_supervisors`
- `company_product_line`、`bank_account`、`employee` 等

合同、商机、客户等域需要：

- 不同**实体锚点**（Contract / Opportunity / Customer）
- 不同**关系模型**与**向量文档模板**
- 问答侧 **Intent / Graph / SQL** 配套

每新增一域即修改单体 Python 文件，不可持续。

---

## 2. 目标架构：Enterprise Knowledge Sync Platform（EKSP）

将「灌库」升级为 **企业知识同步平台**，分四层：

```
┌─────────────────────────────────────────────────────────────┐
│ 数据源层：业务 MySQL（tdcomp / crm / contract…）、文档元数据   │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 同步编排层：sync-manifest.yaml、变更检测、Sync Job、状态表      │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 域转换层（Domain Pack 插件）：org_master / customer / …       │
│              → Canonical JSONL（统一实体 + 关系）              │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 知识存储层：Neo4j upsert、Qdrant upsert、编译文档（可选）       │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ 问答层：Live SQL（实时）+ Graph（关系）+ Vector（语义）         │
└─────────────────────────────────────────────────────────────┘
```

### 2.1 设计原则

| 原则 | 说明 |
|------|------|
| **稳定主键** | 所有写入使用 `domain + entity_type + entity_id` 作为 upsert 键；禁止 wipe 作为常规手段 |
| **域可插拔** | 每个业务域一个 Domain Pack，不改核心框架即可扩展 |
| **同步可观测** | 每次 job 有 batch id、变更统计、失败可重跑 |
| **检索分层** | 结构化主数据走图谱+向量；高实时性明细保留 Live SQL |

### 2.2 与现有组件分工

| 组件 | 定位 |
|------|------|
| **EKSP（本方案，改造 Bulk Pipeline）** | 域插件 + 增量 upsert；企业结构化主数据同步引擎 |
| **Active Learning** | 制度文档、别名、Schema 说明、人工补知识（非结构化） |
| **Live SQL（SqlQueryService）** | 实时明细、聚合统计 |
| **structured-ingest-gate** | 新表接入前行数/合规门禁 |
| **Schema sedimentation pipeline** | 新库元数据评估（非业务行同步） |

---

## 3. 增量同步方案

### 3.1 变更检测策略（分阶段）

| 阶段 | 机制 | 适用 | 成本 |
|------|------|------|------|
| **P0** | `updated_at` 水位 + 主键分页 | 表有更新时间列 | 低 |
| **P0** | 关键列拼接 content hash | 无更新时间列 | 低 |
| **P1** | 软删除 `is_deleted` / `status` | 删除传播 | 中 |
| **P2** | MySQL Binlog / Debezium CDC | 大表、强一致 | 高 |

**P0 增量拉取示例：**

```sql
SELECT * FROM contract
WHERE updated_at > :last_watermark
ORDER BY updated_at, id
LIMIT 5000;
```

### 3.2 实体级状态表

在 `assistant` 库新增 **`sync_entity_state`**（扩展/替代现有 `sync_tracking` 的行级粒度）：

| 字段 | 说明 |
|------|------|
| `domain` | 如 `org_master`、`contract` |
| `entity_type` | `Company`、`Contract`、`Customer` |
| `entity_id` | 业务主键 |
| `source_db` / `source_table` | 来源 |
| `content_hash` | 正文 hash；相同则跳过 re-embed |
| `graph_synced_at` / `vector_synced_at` | 各路同步时间 |
| `watermark` | 源表 `updated_at` |
| `status` | `active` / `deleted` |

现有 `sync_tracking` 可保留用于**表级**门禁与行数审计，与实体级状态互补。

### 3.3 写入语义：Upsert + Tombstone

**Neo4j（常规增量，禁止 wipe）：**

```cypher
MERGE (c:Contract {contractId: $id})
SET c += $props,
    c.updatedAt = $ts,
    c.deleted = false

// 删除传播（按合规二选一）
MATCH (c:Contract {contractId: $id}) SET c.deleted = true
// 或 DETACH DELETE
```

**Qdrant：**

- Point id = 稳定 hash（`domain + entity_type + entity_id`）
- Payload 增加：`domain`、`entity_type`、`entity_id`、`version`、`updated_at`
- `content_hash` 未变 → 跳过 re-embed
- 删除 → 删 point 或 payload `deleted=true` + 检索过滤

**`--wipe` 仅用于灾难重建**，不作为日常运维。

### 3.4 Sync Job 编排

```
定时 / 手动 / Webhook
    → 读取 sync-manifest.yaml
    → 按域并行 extract（增量 SQL）
    → 输出 Canonical JSONL（仅变更实体）
    → upsert Neo4j（域 mapper）
    → upsert Qdrant（hash 变化者 re-embed）
    → 更新 sync_entity_state
    → 写 job 日志
```

**与现有 HTTP 的关系：**

| 现有 | 改造后 |
|------|--------|
| `POST /qa/learn/knowledge-sync/from-mysql` | 增加 `mode=full_rebuild`（保留 wipe） |
| （无） | 新增 `POST /qa/learn/knowledge-sync/incremental` |
| `ScheduledSyncService` 写占位 Markdown | 改为触发真正 incremental job |

### 3.5 同步频率建议

| 数据类型 | 频率 | 方式 |
|----------|------|------|
| 公司 / 人员 / 证照 | 日增量 + 周全量校验 | 水位 |
| 合同 / 商机 | 15～60 分钟 | 水位 |
| 客户主数据 | 日增量 | 水位 |
| 审批中状态 | 问答时 Live SQL | 短 TTL 或不 embed |

---

## 4. 域扩展方案（Domain Pack）

### 4.1 目录结构

```
scripts/enterprise_pipeline/
├── sync_manifest.yaml          # 全局同步清单
├── sync_job.py                 # Job 入口（full / incremental）
├── sync_upsert_neo4j.py        # 通用 upsert（读 canonical JSONL）
├── sync_upsert_qdrant.py       # 通用 upsert + hash 跳过 embed
├── canonical_schema.json       # Canonical JSONL 契约
└── domains/
    ├── org_master/             # 现有 tdcomp 主体（自 build_knowledge_from_mysql 迁移）
    │   ├── manifest.yaml
    │   ├── extractor.py
    │   ├── graph_mapper.py
    │   └── vector_doc.py
    ├── crm_customer/
    ├── crm_opportunity/
    └── contract/
```

### 4.2 Domain manifest 示例（contract）

```yaml
domain: contract
version: 1
anchor_entity: Contract

source:
  db: tdcomp
  tables:
    - name: contract_main
      primary_key: contract_id
      watermark_column: updated_at
      company_fk: company_id
    - name: contract_party
      primary_key: id
      parent_fk: contract_id

joins:
  - contract_main.contract_id = contract_party.contract_id

graph:
  nodes:
    - label: Contract
      id_field: contract_id
    - label: Customer
      id_field: customer_id
  relationships:
    - type: SIGNED_BY
      from: Contract
      to: Customer
    - type: UNDER_COMPANY
      from: Contract
      to: Company

vector:
  template: contract_summary_v1
  fields: [contract_no, title, amount, status, sign_date, parties]

retrieval:
  intents: [contract, document]
  graph_queries: [contract_by_company, contract_by_customer]
```

### 4.3 Neo4j 图谱扩展

在现有 `Company` / `Person` / `Certificate` 等节点上增量增加：

| 节点 | 关键属性 | 关系 |
|------|----------|------|
| `Customer` | customerId, name, industry, level | `(Customer)-[:BELONGS_TO]->(Company)` |
| `Opportunity` | oppId, name, stage, amount, expectedClose | `(Opportunity)-[:FOR_CUSTOMER]->(Customer)` |
| `Contract` | contractId, no, title, amount, status, dates | `(Contract)-[:WITH_CUSTOMER]->(Customer)`、`(Contract)-[:UNDER_COMPANY]->(Company)` |

跨域关联必须挂到已有 `Company`，以支持「某公司有哪些合同 / 某客户的商机 pipeline」类问答。

### 4.4 向量策略

**统一 payload 结构：**

```json
{
  "domain": "contract",
  "entity_type": "Contract",
  "entity_id": "C2024001",
  "company_id": "11280",
  "customer_id": "CU001",
  "text": "合同编号… 甲方… 金额… 状态…",
  "updated_at": "2026-05-28T10:00:00Z"
}
```

**集合策略：**

| 方案 | 说明 | 建议阶段 |
|------|------|----------|
| **A：单集合** | `enterprise_knowledge_v2` + payload `domain` 过滤 | 初期推荐 |
| **B：多集合** | 每域独立 collection，检索 merge | 域隔离要求高时 |

### 4.5 问答侧配套（Java）

| 组件 | 改动 |
|------|------|
| `IntentRouterService` | 增加 `contract` / `customer` / `opportunity` queryType |
| `GraphContextService` | 按公司 / 客户 / 合同号的 Cypher 模板 |
| `EntityTableMapper` | 域 → 表映射，Live SQL 兜底 |
| `business-rules.json` | 域关键词、槽位规则 |

**检索分工：**

| 问法类型 | 通路 |
|----------|------|
| 列表 / 统计 / 最新状态 | Live SQL |
| 关系穿透 / 跨实体 | Neo4j |
| 模糊描述 / 条款语义 | Qdrant |

---

## 5. Canonical 中间格式

所有 Domain Pack 输出统一 JSONL，便于一个 Sync Job 写多 sink：

```json
{
  "sync_batch_id": "20260528-001",
  "op": "upsert",
  "domain": "contract",
  "entity_type": "Contract",
  "entity_id": "C2024001",
  "content_hash": "abc123...",
  "updated_at": "2026-05-28T10:00:00Z",
  "company_ids": ["11280"],
  "graph": {
    "nodes": [],
    "edges": []
  },
  "vector_text": "…",
  "metadata": {
    "contract_no": "HT-2024-001",
    "status": "生效"
  }
}
```

- `op: delete` → tombstone 流程
- 新增域只需实现 **extractor → canonical**，不必修改 `sync_upsert_*.py` 核心

---

## 6. 实施路线图

### Phase 0（2～3 周）：增量闭环

| # | 任务 |
|---|------|
| 1 | 新建 `sync_entity_state` 表 + `sync_manifest.yaml`（先含 `org_master`） |
| 2 | 将 `build_knowledge_from_mysql.py` 迁移为 `domains/org_master/` |
| 3 | 实现 `sync_upsert_neo4j.py` / `sync_upsert_qdrant.py`（无 wipe、稳定 point id） |
| 4 | 重写 `ScheduledSyncService` → 触发 incremental job |
| 5 | HTTP：`POST /qa/learn/knowledge-sync/incremental` |

**验收：** 修改一条 company 记录 → 约定时间内图谱 / 向量 / 问答结果更新。

### Phase 1（3～4 周）：首个新域 customer

| # | 任务 |
|---|------|
| 1 | 梳理 tdcomp 客户表，实现 `crm_customer` Domain Pack |
| 2 | 扩展 Neo4j schema + `GraphContextService` |
| 3 | 向量 payload 增加 `domain` 过滤 |
| 4 | 评测：「XX 公司有哪些重点客户」 |

### Phase 2（4～6 周）：contract + opportunity

| # | 任务 |
|---|------|
| 1 | 两个 Domain Pack + 跨域关系 |
| 2 | Intent 路由 + Live SQL 兜底（合同号精确查） |
| 3 | 非结构化合同（PDF）可走主动学习或独立 collection |

### Phase 3（按需）：CDC 与治理

| # | 任务 |
|---|------|
| 1 | Debezium / Canal 大表 CDC |
| 2 | 同步监控（滞后、失败率、embed 成本） |
| 3 | manifest 级 `redact_columns` 脱敏 |
| 4 | 与 `qa_pending_knowledge` 审核流打通 |

---

## 7. Quick Win（最小验证）

不等待全平台，可先：

1. **去掉日常 `--wipe`**；Neo4j 已 mostly MERGE，保留 wipe 仅作 rebuild
2. **Qdrant point id 改为稳定 hash**（`company_id` / 未来 `contract_id`）
3. **`build_knowledge_from_mysql.py` 增加 `--since`**（按 `updated_at` 过滤）
4. **新建 `domains/contract/` manifest 骨架**，先搭插件框架

---

## 8. 关键决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 日常同步模式 | 增量 upsert | 成本低、可观测；全量仅补偿 |
| 域扩展方式 | Domain Pack + manifest | 避免单体脚本膨胀 |
| Qdrant 集合 | 初期单集合 + domain 过滤 | 运维简单 |
| 实时明细 | Live SQL 兜底 | 合同金额等高频变更不必全量 embed |
| 新域顺序 | customer → contract → opportunity | 客户是商机/合同的关系 hub |
| 现有 ScheduledSync | 重写为触发 EKSP job | 当前占位 Markdown 无业务价值 |

---

## 9. 相关文档与代码

| 资源 | 路径 |
|------|------|
| 现有沉淀机制 | [knowledge-sedimentation-guide.md](./knowledge-sedimentation-guide.md) |
| Enterprise Pipeline | [scripts/enterprise_pipeline/README.md](../scripts/enterprise_pipeline/README.md) |
| 结构化接入门禁 | [openspec/design/structured-ingest-gate.md](../openspec/design/structured-ingest-gate.md) |
| 待办排队 | [openspec/backlog.md](../openspec/backlog.md) |
| 灌库脚本 | `scripts/enterprise_pipeline/build_knowledge_from_mysql.py` |
| 图谱同步 | `scripts/enterprise_pipeline/sync_neo4j.py` |
| 向量同步 | `scripts/enterprise_pipeline/sync_vectors_qdrant.py` |
| 运维灌库 | `src/main/java/com/qa/demo/qa/ops/LocalKnowledgeOpsService.java` |
| 定时同步（待重写） | `src/main/java/com/qa/demo/qa/learning/ScheduledSyncService.java` |

---

## 10. 变更记录

- **2026-05-28**：初版；沉淀增量同步与 Domain Pack 扩展方案，对应灌库无法增量、域无法扩展两个问题。
