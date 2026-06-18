# 图谱退出问答检索通路 — 实施计划

> 状态：草案 · 编写日期 2026-06-17  
> 范围：**仅问答读路径（检索 / 意图 / 消歧）**；本文**不讨论** CDC、图谱写入、主动学习落库、运维重建。  
> 关联：`docs/architecture-and-future-plan.md`（P3 分流通路）、`docs/platform-retrieval-architecture.md`（Connector 分层）

---

## 0. 决策摘要

| 项 | 结论 |
|----|------|
| 是否继续将 Neo4j 作为默认检索通路 | **否** |
| 任职（`person_role_list`）是否必须用图 | **否**，已有 `SqlPersonRoleRetriever` 可主答 |
| 图谱在问答中的定位 | **退出检索平面**；人物消歧可暂保留窄接口，最终改为 employee 表 |
| 与 P3c 方向 | 一致：按问句形态走 **MySQL 专用结构化通路**，不足再 **Qdrant 语义召回** |

---

## 1. 背景与动机

### 1.1 问题陈述

当前企业 scope 在 `unified-retrieval-enabled=true` 时，**几乎所有问句**走 `retrieveUnifiedEnterprise()`，其中 `collectHybridCandidatesExpanded()` **每轮调用** `safeGraphRetrieve()`（图 + 向量 + MySQL + SQL）。

实测（`data/qa_logs/ask_events.jsonl`，823 条问答，排除 learning）：

| 指标 | 数值 |
|------|------|
| `retrievalSource` 以 `unified_*` 开头 | **95.3%** |
| `retrievalSource=graph`（纯图首路） | **0** |
| 最终证据含 `neo4j*` 的问答 | **27.2%** |
| `unified_person_role` 中 neo4j 命中率 | **96%**（任职是唯一「真靠图」场景） |
| `unified_constrained_rerank_*` 中 neo4j 命中率 | **~18%**（多数被 rerank 挤掉或空召回） |

结论：**图谱调用频率高、证据贡献率低**；除任职外，大量问句（地区、母子公司、概况、阈值筛选）图库或无数据、或被向量/常识覆盖。

### 1.2 已观察的失败模式（检索侧）

| 问句类型 | 现象 | 根因（检索） |
|----------|------|----------------|
| 「哪些公司的总公司是总部」 | 意图 `company_profile`，证据 canonical + 向量，**无 neo4j** | 无组织关系列表通路；图节点区划/母公司字段不全 |
| 「北京的公司有哪些」 | 图 `registeredAreaCode` 大量为空 | 已改 MySQL `reg_province_region` 专用通路（P3c） |
| 任职列表 | 强依赖 `neo4j-boundary` | `person-role-graph-primary=true` 主动 **跳过** SQL 主检索 |

### 1.3 设计原则（继承自 architecture-and-future-plan §设计前提）

1. 业务字段在 **MySQL 真源**；本系统不补数，缺失时 **追问澄清**（P4）。
2. 新问法形态优先 **配置化专用 Connector**，而非 hybrid 撒网 + rerank。
3. 意图 `retrievalStrategy` 与 **实际可检索能力** 对齐，避免 `intent=graph` 但证据无图。

---

## 2. 现状：图谱在问答链路上的触点

### 2.1 主链路（读）

```
QaAskFlowService.retrieve()
  └─ retrieveUnifiedEnterprise()          [enterprise + unified=true]
       ├─ P3c 早退（filter / region / global_cert / …）
       └─ collectHybridCandidatesExpanded()
            ├─ [dedicated_list + graphPrimary] → GraphPersonRoleQuery → SQL enrich
            └─ [默认] safeGraphRetrieve() + vector + mysql + sql
```

### 2.2 配置开关（当前默认）

| 配置项 | 默认值 | 作用 |
|--------|--------|------|
| `qa.assistant.unified-retrieval-enabled` | `true` | 企业问答走统一召回 |
| `qa.assistant.person-role-graph-primary` | `true` | 任职列表 **SQL 让位给图**（`skipForPlan`） |
| `qa.assistant.person-role-slim-graph` | `true` | 任职用 `neo4j-boundary` 定界 |
| `retrieval-catalog.json` → `person_role_list.execution.graphPrimaryListPath` | `true` | dedicated 路径先拉图 |

### 2.3 已有可替代能力（无需从零开发）

| 能力 | 实现 | 证据 source |
|------|------|-------------|
| 任职列表 | `SqlPersonRoleRetriever` + `sql-role-columns.json` | `mysql-sql-person-role` |
| 地区公司列表 | `RegionCompanyRetrievalService`（MySQL） | `mysql-region-company` |
| 阈值筛选 | `FilterThresholdRetrievalService` | `mysql-filter-threshold` |
| 全局证照 | `GlobalCertificateRetrievalService` | `global_certificate_list` |
| 人物消歧（待迁） | `PersonNameResolver` → `GraphContextService.listPersonNamesByHintAndRole` | 非证据主路 |

### 2.4 Eval 覆盖（`data/eval/qa_cases.jsonl`）

| 相关 case | 当前期望 |
|-----------|----------|
| `q01_person_role_list` | `retrievalSourceContains: person_role`，≥20 条证据 |
| `q01_person_role_sql_fallback` | SQL fallback，≥1 条 |
| `person_role_legal_rep` / `case16_followup_cert_pronoun` | `person_role_list`，可作答 |
| 地区 / 阈值 / 证照 | 已不依赖图 |

---

## 3. 目标架构（问答检索）

### 3.1 检索分层

```text
问句
  ↓
意图 retrievalStrategy + Need 推断
  ↓
┌─ 专用结构化通路（优先，P3c 扩展）
│    person_role_list      → SqlPersonRoleRetriever（主答）
│    filter_threshold      → FilterThresholdRetrievalService
│    region_company_list   → RegionCompanyRetrievalService
│    global_certificate_list → GlobalCertificateRetrievalService
│    head_office_list      → 【待建】MySQL head_office_company_id
│    type_catalog          → CatalogEvidenceRetriever
│    aggregate_count        → AggregateCountQueryService
├─ 语义兜底
│    semantic_rag          → Qdrant（+ 可选 compiled docs）
└─ 追问 / 澄清（P4）
     字段缺失、人物/公司消歧
```

**不再出现：** 默认 hybrid 内每轮 `safeGraphRetrieve()`；`intent=graph` 作为企业问答主通道。

### 3.2 意图层调整（原则）

| 现状 | 目标 |
|------|------|
| `GRAPH_RELATIONAL` → `person_role_list` + graph | `STRUCTURED_LIST` + facet=`role`，检索走 SQL |
| `company_profile` 命中「总部/总公司」关键词 | 拆为 **关系列表 need**（待建 `head_office` / 组织关系通路），避免走 canonical + 向量 |
| LLM 错填 `companyHints`（如「现在哪些公司」） | 问句片段过滤（见 `intent-routing-assessment-and-plan.md` 同类问题） |

### 3.3 人物消歧（窄辅助，可选阶段）

`PersonNameResolver` 当前第 3 步调图；目标改为：

1. 主动学习别名  
2. `EmployeeBaseKnowledgeService` 精确/模糊匹配  
3. ~~图谱前缀匹配~~ → **employee 表 LIKE / 工号**  
4. LLM 消歧（保留）

问答证据**不依赖** Neo4j 节点即可。

---

## 4. 分阶段实施计划

> 复杂度：轻 / 中 / 重 · 风险：低 / 中 / 高  
> **不估人日**；每阶段以 **eval 门禁 + 手工用例** 验收。

### 阶段 A — 任职改 SQL 主答（中 · 中）

**目标：** `person_role_list` 不再调用 `GraphPersonRoleQuery`；Q-01 不回退。

| # | 动作 | 类型 |
|---|------|------|
| A1 | `person-role-graph-primary=false` | 配置 |
| A2 | `retrieval-catalog.json`：`person_role_list.execution.graphPrimaryListPath=false` | 配置 |
| A3 | `QaRetrievalPipeline.collectHybridCandidatesExpanded`：dedicated_list 分支改为 **直接** `safeSqlRetrieve` / 调用 `SqlPersonRoleRetriever`，删除 graph boundary + enrich 分支 | 代码 |
| A4 | 评估是否删除或闲置 `SqlPersonRoleDetailEnricher`（仅服务图定界） | 代码/清理 |
| A5 | `retrievalSource` 保持 `unified_person_role` 或改为 `mysql_person_role`（需统一 eval 断言） | 约定 |

**验收：**

- [ ] `q01_person_role_list`：≥20 条，`person_role` 证据，source 含 `mysql-sql-person-role`
- [ ] `q01_person_role_sql_fallback`、`person_role_legal_rep`、`case16_followup_cert_pronoun` 通过
- [ ] `ask_events` 抽样：任职问句 **0%** 必需 neo4j 证据

**回滚：** 恢复 A1–A2 配置为 `true`。

---

### 阶段 B — 统一召回去图（中 · 中）

**目标：** 非专用通路的问句 **不再** `safeGraphRetrieve()`。

| # | 动作 | 类型 |
|---|------|------|
| B1 | `collectHybridCandidatesExpanded` 默认分支移除 `safeGraphRetrieve` | 代码 |
| B2 | `retrieveHybrid` / `retrieveGraphFirst` 标记 deprecated 或仅测试入口保留 | 代码 |
| B3 | `IntentRuleEngine`：弱化 `relationIntent → graph` 默认路由 | 代码/配置 |
| B4 | `IntentSlots.deriveChannels`：`GRAPH_RELATIONAL` 不再映射到 `intent=graph` 用于企业 unified | 代码 |

**验收：**

- [ ] 全量 `qa_cases.jsonl` ≥ 原有通过率（当前基线 25/26+）
- [ ] case17 概况、case12 地区、case3 阈值、case19 证照无回归
- [ ] 随机 20 条历史 `unified_constrained_rerank` 问句：功能不退化（人工 spot check）

**回滚：** 恢复 B1 中 graph 追加一行（feature flag `qa.assistant.hybrid-graph-enabled` 可选）。

---

### 阶段 C — 人物消歧去图（轻 · 低）

| # | 动作 | 类型 |
|---|------|------|
| C1 | `PersonNameResolver`：用 employee 表查询替代 `listPersonNamesByHintAndRole` | 代码 |
| C2 | `PersonClarificationAdvisor` 确认不依赖图候选（若有则改 SQL） | 代码 |

**验收：**

- [ ] 花名/简称消歧用例（含「老布」类）行为不退化
- [ ] 任职 + 消歧组合用例通过

---

### 阶段 D — 组织关系列表通路（中 · 中）【可选，业务向】

**目标：** 覆盖「总公司是总部」「母公司为 X 的子公司」类问句。

| # | 动作 | 类型 |
|---|------|------|
| D1 | 新增 `HeadOfficeListQuestionSupport` + `HeadOfficeRetrievalService`（MySQL `head_office_company_id`） | 代码 |
| D2 | `NeedInferenceService` 规则 + `QaRetrievalPipeline` 早退 | 代码 |
| D3 | 收紧 `enterprise-canonical-facts` 触发条件（列表问句不注入单点 HQ fact） | 配置 |
| D4 | eval 新增 case：`哪些公司的总公司是总部` | 数据 |

**验收：**

- [ ] 新 case 走专用 source，**非** `unified_constrained_rerank` + 单条 canonical

---

### 阶段 E — 文档与架构对齐（轻 · 低）

| # | 动作 |
|---|------|
| E1 | 更新 `architecture-and-future-plan.md` §2.1 四路召回表述 → 「结构化主通路 + 语义兜底」 |
| E2 | 更新 `platform-retrieval-architecture.md` §3 图谱证照表述 |
| E3 | 更新 `CLAUDE.md` 架构表（Neo4j 角色改为「可选/非问答主路」） |

---

## 5. 拟改动文件清单（实施时参考）

| 文件 | 阶段 | 说明 |
|------|------|------|
| `application.properties` | A | `person-role-graph-primary` 等 |
| `retrieval-catalog.json` | A | `graphPrimaryListPath` |
| `QaRetrievalPipeline.java` | A, B | hybrid / dedicated_list |
| `SqlPersonRoleRetriever.java` | A | 移除/忽略 `skipForPlan` 图优先逻辑 |
| `SqlPersonRoleDetailEnricher.java` | A | 评估删除 |
| `SqlQueryService.java` | A | `skipForPlan` 清理 |
| `IntentSlots.java` | B | deriveChannels |
| `IntentRuleEngine.java` | B | relation → hybrid/sql |
| `PersonNameResolver.java` | C | 消歧数据源 |
| `NeedInferenceService.java` + `retrieval/*/` | D | 组织关系通路 |
| `enterprise-canonical-facts.json` | D | HQ fact 触发词 |
| `data/eval/qa_cases.jsonl` | A, D | 断言调整 / 新增 |

**暂不改动（本文范围外）：** `Neo4jCdcWriter`、`GraphConfig`、CDC 相关、`ActiveLearningService` graph sink。

---

## 6. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| Q-01 任职条数下降 | 高 | 阶段 A 单独上线；对比 eval + 生产 `q01` 日志 |
| SQL 角色列与业务库不一致 | 中 | `sql-role-columns.json` 与源表对账；空列走澄清 |
| 去掉图后证照实例仅走 MySQL | 低 | `CertificateRetrievalService` 已有多源；确认 catalog `graphInstanceSource` 引用 |
| 意图仍产出 `intent=graph` 但无图证据 | 中 | 阶段 B 同步改意图推导 + 文档 |
| canonical fact 抢占列表问句 | 中 | 阶段 D 收紧 triggers 或列表 need 早退 |

---

## 7. 验收门禁（阶段一整体）

与 `architecture-and-future-plan.md` 阶段一门禁对齐：

| 门禁 | 标准 |
|------|------|
| Eval | `qa_cases.jsonl` ≥ **80%** 通过（目标 **26/26**） |
| Q-01 | `戴科彬在哪些企业担任法定代表人` **不回退** |
| 图谱证据占比 | 任职/通用问句 **不强制** neo4j；允许 0% |
| §4 六类失败 | 地区、阈值、证照列表、catalog 等 **不复现** |

---

## 8. 不在本计划内

- CDC / Debezium / `Neo4jCdcWriter` 启停或改造  
- 图谱数据重建、节点模型变更  
- 主动学习写入 Neo4j  
- Agent 化 P4 / 意图 LLM 优先 P6（可并行，无硬依赖）  
- 文档向量 P5（已暂停）

---

## 9. 建议执行顺序

```
A（任职 SQL）→ B（hybrid 去图）→ C（消歧去图）→ D（组织关系，可选）→ E（文档）
     ↑              ↑
  必须先过 Q-01   可与 A 同 PR，但建议分开便于回滚
```

**不建议：** 未过阶段 A 即删 `GraphContextService` 类（消歧、证照、管理接口仍可能引用）。

---

## 10. 决策记录

| 日期 | 决策 | 依据 |
|------|------|------|
| 2026-06-17 | 图谱退出问答默认检索平面 | ask_events 统计；P3c 已证明 MySQL 专用通路有效 |
| 2026-06-17 | 任职可不使用图谱 | `SqlPersonRoleRetriever` 已实现；graph-primary 为配置选择 |
| 2026-06-17 | 本计划不含 CDC 讨论 | 范围限定为读路径 |

---

## 11. 附录：关键代码索引

| 组件 | 路径 |
|------|------|
| 统一召回 | `qa/retrieval/QaRetrievalPipeline.java` → `retrieveUnifiedEnterprise` |
| Hybrid 拉图 | `collectHybridCandidatesExpanded` → `safeGraphRetrieve` |
| 任职 SQL | `qa/retrieval/sql/SqlPersonRoleRetriever.java` |
| 任职图 | `qa/retrieval/graph/GraphPersonRoleQuery.java` |
| 角色列配置 | `src/main/resources/qa/sql-role-columns.json` |
| 人物消歧 | `qa/intent/PersonNameResolver.java` |
| Eval 脚本 | `scripts/eval/run_qa_eval.py` |
