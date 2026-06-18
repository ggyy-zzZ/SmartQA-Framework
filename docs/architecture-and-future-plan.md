# 项目当前实现总结与未来调整计划

> 状态：验证期 v1 · 编写日期 2026-06-15
> 目的：把"项目做什么、怎么做的、各层数据情况、本轮发现的失败案例、未来要改成什么样"一次性落到一份文档里，便于团队评审和后续接手。

## 设计前提（不可违反）

下面这 7 条是后续所有改动必须遵守的前提，**优先级最高**：

1. **数据源 = 企业自有结构化数据 + 企业自有文档**（两者并列，无主次）
2. **不拒答**：信息不足时**追问补全**而非拒绝
3. **目标形态：智能 Agent / AI Native 应用**（具备规划、工具调用、反思、追问能力），不是传统 RAG
4. **业务字段缺失由业务方负责维护**，本系统不负责补全数据
5. **不估算"人日"**（验证期项目，效果优先；实际为 AI 辅助编码）
6. **意图识别优先用 MiniMax-M3 模型辅助**（规则仅作 fallback）
7. **不与具体业务耦合**——本系统是**通用企业知识库**，业务实体/关系/字段全部配置化；具体"公司/证照/印章"是配置数据，不是代码常量

---

## 0. 阅读指引

- **第 1 章** = 项目干什么
- **第 2 章** = 当前的实现思想（架构、控制流、配置）
- **第 3 章** = 图谱、向量、数据表各自的情况与生成方案
- **第 4 章** = 这一轮发现的失败案例与根因
- **第 5 章** = 未来计划（短期、中期）
- **第 6 章** = 风险、待办、决策记录

> 本文档是"评估版"，所有未来计划项按**复杂度（轻/中/重）**与**风险（低/中/高）**标注，**不估人日、不绑定时间表**。

---

## 1. 项目是干什么的

### 1.1 一句话定义

**SmartQA-Framework** 是一个"通用企业知识库 Agent"验证项目：面向任意企业，接收自然语言问题，从**结构化数据（业务库）**与**非结构化文档（企业上传的合同/制度/手册等）**两类知识源中检索证据，由大模型（MiniMax-M3）作为推理引擎生成带引用、可解释的回答。信息不足时通过**追问**补全，不拒答。

### 1.2 范围

| 在范围内 | 不在范围内（验证期不考虑） |
|---|---|
| 单租户（一个企业） | 多租户隔离、权限分级 |
| 中文为主、问句偏结构化 | 多语言、长文档摘要、文档级生成 |
| 几百到几千家主体量级 | 千万级数据、毫秒级延迟 |
| 验证"问答 + 文档学习 + 追问" 三位一体 | SLA、灰度发布、监控告警、备份容灾 |
| 业务实体类型由**配置驱动**（不写死企业/证照/印章） | 业务方需要在本系统内补全数据（**字段维护由业务方负责**） |
| 文档上传、解析、向量化、入库 | 文档版本管理、文档协作 |

### 1.3 典型用户问题形态（已观察，示例）

| 形态 | 示例（结构化） | 示例（文档） |
|---|---|---|
| 实体画像 | "中铁建工集团有限公司的法定代表人是谁？" | "请总结一下《公司章程》里关于法人职责的描述" |
| 实体关系列表 | "李四在哪些公司担任过法人？" | "文档里提到哪些人是某公司的法人？" |
| 阈值筛选 | "成立时间超过 10 年的公司有哪些？" | "制度里规定的最低注册资本是多少？" |
| 证照查询 | "中铁建设集团有限公司当前持有的有效资质证书有哪些？" | "安全生产许可证的申请条件在文档哪里？" |
| 类型枚举 | "公司经营状态包含哪些种类？" | "文档中定义了哪几种员工类别？" |
| 区域/主体约束 | "北京的公司有哪些？" | "北京地区的销售政策文档在哪？" |
| 多轮追问 | 第 1 轮"北京的公司" → 第 2 轮"它们注册资本是多少" | 第 1 轮"销售政策" → 第 2 轮"适用于哪些区域？" |
| 追问补全 | "我想查公司 X 的某信息"（X 不明确） | "我手里这份制度"（系统不知道是哪份） |

> 上表"示例（结构化）"列是**当前验证期的主要测试问句**；"示例（文档）"列是**阶段一目标**。
> 行业、实体类型、字段都是**配置可换的**——表中的"公司/证照/注册资本"是当前示例业务的占位词。

### 1.4 不做什么

- 不做企业知识管理（KM）系统本体
- 不做数据治理/数据血缘
- 不做 BI 报表/看板
- 不做模型训练/微调
- **不补业务字段数据**——业务方在源系统内维护，本系统只做"字段缺失透明化"
- **不与具体业务耦合**——具体实体类型/关系类型/字段名都由 JSON 配置驱动

---

## 2. 当前的实现思想

### 2.1 总体架构

Spring Boot 3.5.13 + Java 21 的单体应用，对外暴露 HTTP API（端口 8080 / 验证期常用 8089）。四类外部依赖：

| 依赖 | 角色 | 部署位置 |
|---|---|---|
| **MySQL 8** | 业务真源（任意业务库）+ 系统表（assistant 库） | localhost:3306 |
| **Neo4j 5** | 关系型知识（图谱） | bolt://localhost:7687 |
| **Qdrant** | 向量知识（text-embedding-v4 1024 维） | localhost:6333 |
| **MiniMax API** | LLM 推理（M3 模型） | api.minimaxi.com |

> **业务无关性说明**：MySQL 库名、表名、字段名；Neo4j 节点 label、关系 type、属性名；Qdrant collection 名称与 payload schema ——**全部由 JSON 配置驱动**，不写死为某家企业/某种实体。

### 2.2 `/qa/ask` 主链路

```
用户问句
  ↓
QaController.ask                          (web/QaController.java:266-274)
  ↓
QaAskFlowService.ask                      (orchestration/QaAskFlowService.java:147-355)
  ├─ 阶段 1:   会话上下文加载 + 多轮改写
  ├─ 阶段 2:   [Agent] 规划：判断问题类型 + 决定检索策略
  ├─ 阶段 3:   意图识别 (IntentRouterService)
  │             ├─ 规则兜底（仅作 fallback）
  │             └─ 主用：MiniMax-M3 模型抽取 intent + entities
  ├─ 阶段 4:   实体抽取（公司名/人名/区域/角色/文档名/时间/数值）
  ├─ 阶段 5:   检索形态路由 (structured / semantic / relational / hybrid)
  ├─ 阶段 6:   多通路召回 (QaRetrievalPipeline)
  │             ├─ 业务库通道 (MySQL 任意表，由配置驱动)
  │             ├─ 图谱通道 (Neo4j 任意节点/关系，由配置驱动)
  │             ├─ 向量通道 (Qdrant 任意 collection)
  │             ├─ 文档通道 (企业上传的非结构化文档)
  │             └─ 主动学习 (Active Learning)
  ├─ 阶段 7:   硬约束过滤 (HardConstraintGate: region/实体锚点)
  ├─ 阶段 8:   截断 + 重排 (applyConfigDrivenTruncation → rerank)
  ├─ 阶段 9:   [Agent] 反思：证据是否充分？
  │             ├─ 充分 → 进入阶段 10 生成
  │             └─ 不充分 → 生成追问 (Clarification Question)
  ├─ 阶段 10:  LLM 兜底生成（含追问或最终答案）
  ↓
返回 { answer, evidence, confidence, canAnswer, route, latencyMs, ... }
  ↓
GateMetricsWriter.record                  (debug/GateMetricsWriter.java)
  └─ 追加 JSONL 到 data/qa_logs/gate_metrics.jsonl
```

> **当前实现 vs Agent 目标的差距**：
> - 阶段 2（Agent 规划）当前没有，意图识别合并到阶段 3
> - 阶段 9（Agent 反思）当前由闸门判 `allowGenerate=false` 时返回**固定模板**（`insufficientEvidenceGeneralHint`），**非 LLM 动态追问**
> - 阶段 10 当前是"生成 + 模板拒答"二分；人物/公司澄清已有，**字段缺失类追问尚未接入**
>
> 后续阶段一/阶段二会逐步把 Agent 维度补齐。

### 2.3 多路召回的设计动机

四路召回（Neo4j / Qdrant / MySQL / Document）的初衷是**应对不同形态的问句**：

| 问句形态 | 主用通路 |
|---|---|
| 实体画像 / 关系 | Neo4j |
| 语义相似 / 文档 | Qdrant |
| 阈值筛选 / 聚合 | MySQL |
| 业务文档 / 知识库 | Document |

但实际跑下来，**所有问句都走 `unified_constrained_rerank_dashscope` 兜底通道**——意图路由判断"不确定"时直接落兜底，导致四路召回被当成"撒网式"使用。

### 2.4 关键配置点

`application.properties` 当前生效的核心开关：

| 配置 | 值 | 含义 |
|---|---|---|
| `qa.assistant.unified-retrieval-enabled` | `true` | 启用统一召回 |
| `qa.assistant.rerank-enabled` | `true` | 启用百炼 rerank |
| `qa.assistant.recall-vector-top-k` | `80` | 向量召回数 |
| `qa.assistant.recall-graph-top-k` | `30` | 图谱召回数 |
| `qa.assistant.recall-graph-person-role-top-k` | `32` | 任职类特例 |
| `qa.assistant.retrieval-top-k` | `50` | unified 召回总池 |
| `qa.assistant.rerank-candidate-max` | `120` | rerank 候选 |
| `qa.assistant.mysql-top-k` | `6` | MySQL 通道 |
| `qa.assistant.mysql-per-table-limit` | `3` | MySQL 单表 |
| `qa.assistant.answer-gate-enabled` | `true` | 启用答案闸门 |
| `qa.assistant.answer-gate-min-evidence-count` | `1` | 闸门最少证据数 |
| `qa.assistant.answer-gate-min-top-score` | `3.0` | 闸门最低分 |
| `qa.assistant.cdc-enabled` | `true` | 启用 Debezium CDC |
| `qa.assistant.config-source` | `mysql_fallback` | 业务规则从 MySQL 加载 |
| `qa.debug.gate-metrics.path` | `data/qa_logs/gate_metrics.jsonl` | 埋点路径 |

> **精简后状态**：所有 P0 计划里规划的 `qa.rule.*` 灰度开关、Drools 9.x 依赖、`rules/qa/*` DRL、`com.qa.demo.qa.rules.*` 包都已移除。`queryType` 仍为 String 类型，未做枚举化。

### 2.5 业务规则加载

业务规则（关键词、状态枚举、截断阈值）从 `src/main/resources/qa/business-rules.json` 加载，由 `BusinessRulesConfig` + `BusinessRulesConfiguration` 持有。验证期不热加载，启动注入。

枚举目录从 `src/main/resources/qa/enterprise-enums.json` 加载，结构示例：

```json
{
  "operatingStatuses": {
    "active":   ["存续","在业"],
    "inactive": ["注销","吊销","撤销","迁出","停业","清算"],
    "transit":  ["注册中","迁入"]
  }
}
```

---

## 3. 图谱、向量、数据表的情况与生成方案

> 本节是"业务无关"的视角——所有实体类型、字段、关系都是配置项。当前验证期使用一套示例业务库（库名/表名/字段在配置中可改），不限定于某一家企业或某类业务。

### 3.1 业务库（MySQL）—— 唯一真源

| 维度 | 说明 |
|---|---|
| 业务库名 | **由 `application.properties` 配**（当前示例为 `tdcomp`） |
| 表名 | **由 `cdc-write-scope.json` 配**（当前示例：company / employee 等） |
| 字段名 | **由 `business-rules.json` 和 `cdc-write-scope.json` 配** |
| 库内总表数 | 2 张示例表（company、employee）—— 验证期不订阅空 topic |
| 系统库 | `assistant`（与业务库分离），存放 `qa_*` 系列表（业务规则缓存、主动学习、Flyway 元数据、CDC 写入结果审计） |

**业务字段维护责任**：业务方在源表里有什么字段，本系统就用什么字段。本系统**不负责补全**——业务字段（如注册资本、社保信息）的空缺由业务方在源系统内维护。

**示例现状**：当前示例业务库 `tdcomp.company` 表有 60+ 列；其中部分业务字段（如注册资本相关列）在源库里就没有——这是**业务系统设计问题，不是本系统的问题**。本系统对"字段缺失"的处理是：在证据链路透明暴露该字段缺失，让 LLM 在回答中体现。

### 3.2 图谱（Neo4j）—— 关系型副本

**节点/关系类型由 `graph-node-definitions.json` 配置驱动**——不写死为某个业务实体。当前示例配置包含：

| 节点 | 配置来源 | 示例属性 |
|---|---|---|
| `Company` | graph-node-definitions.json | name, social_credit_code, reg_province_region, operating_status, legal_rep_id, establishment_date, ... |
| `Person` | graph-node-definitions.json | name, mobile, id |
| `Branch` | graph-node-definitions.json | — |
| `Partner` | graph-node-definitions.json | — |

| 关系 | 配置来源 | 示例 |
|---|---|---|
| `(Person)-[:IS_LEGAL_REP_OF]->(Company)` | graph-node-definitions.json | 法人 |
| `(Person)-[:IS_MANAGER_OF]->(Company)` | graph-node-definitions.json | 经理 |
| `(Company)-[:HAS_BRANCH_IN]->(Region)` | graph-node-definitions.json | 分公司 |
| `(Company)-[:HAS_CERTIFICATE]->(Certificate)` | graph-node-definitions.json | 证照（业务方按需配置） |

> 上述"Company/Person/Certificate"是**当前示例**，换企业后可以改 JSON 配置成任意实体类型（Order/Product/Contract/...），代码不变。

### 3.3 向量库（Qdrant）—— 文本副本

| Collection | 维度 | 用途 | 同步方式 |
|---|---|---|---|
| `enterprise_knowledge_v2` | 1024 | 主知识库向量（结构化 + 文档） | CDC + 离线重建 |
| `enterprise_active_learning_v2` | 1024 | 主动学习反馈 | 用户反馈回灌 |

embedding 模型：阿里云百炼 `text-embedding-v4`（1024 维）。
embedding_text 生成模式：`rewrite`（LLM 改写为通顺段落，检索更准）。

> 注意：与旧 `hash(768)` 集合不兼容，默认新集合名，灌库需 `--recreate`。

**文档数据流**（验证期阶段一目标）：
- 用户上传文档（PDF/Word/Markdown/TXT）
- 后端解析 → 切块 → embedding → 写入 Qdrant
- 与结构化数据走同一个 collection，**统一检索**

### 3.4 数据生成与同步方案

#### 3.4.1 离线一次性灌库（冷启动）

```bash
# 1. 从业务库抽取 → 编译后的文本知识
python scripts/enterprise_pipeline/build_knowledge_from_mysql.py

# 2. 同步到 Neo4j（按 graph-node-definitions.json 配的节点/关系）
python scripts/enterprise_pipeline/sync_neo4j.py --recreate

# 3. 同步到 Qdrant (text-embedding-v4)
python scripts/enterprise_pipeline/sync_vectors_qdrant.py --recreate --llm-mode rewrite

# 4. 文档导入（阶段一）
python scripts/enterprise_pipeline/ingest_documents.py --input ./uploads/
```

#### 3.4.2 实时增量同步（CDC）

Debezium Embedded + Kafka + Spring Kafka Consumer。监听范围**由 `application.properties` 配**：

- `qa.assistant.cdc-database-include-list=tdcomp`（库名）
- `qa.assistant.cdc-table-include-list=tdcomp.company,tdcomp.employee`（表名）

写入策略：

- `Neo4jCdcWriter` 按表分发到对应的 upsert 方法（**当前是 switch 实现**；阶段二会改为配置化 handler）
- `QdrantCdcWriter` 按表分发到对应 upsert 方法
- 失败重试 3 次（`cdc-max-retries=3`，间隔 5s），仍失败入死信队列 `cdc_dlt`

#### 3.4.3 字段补全——非本系统职责

业务字段缺失由业务方在源系统维护，本系统**不负责补数据**。本系统的处理方式：

- **闸门透明暴露**：evidence 不含某字段时，闸门 `canAnswer=true` 但 prompt 中显式说明"以下字段在源系统内未维护"
- **LLM 提示生成**：让 LLM 知道字段缺失是"源系统问题"而非"检索失败"
- **不补数据**：不在本系统内做字段推断、外接工商接口、Excel 灌入

### 3.5 知识库健康快照（2026-06-15 验证期，仅作示例）

| 维度 | 当前示例数据（tdcomp） | 业务无关视角 |
|---|---|---|
| 业务库实体数 | 386 公司 + N 员工 | 由业务方决定 |
| 字段覆盖率 | 60+ 列；**多数业务字段为 NULL** | 同上 |
| 图谱节点数 | 估算 400-500 | 由配置决定 |
| 向量 chunk 数 | 估算 1500-3000 | 由文档量决定 |
| 主动学习 chunk 数 | 0（验证期尚未引入人工反馈） | 由用户量决定 |
| CDC 同步延迟 | T+0（实时） | 同上 |
| 图谱与业务库一致性 | **未做定期对账**（阶段二目标） | 通用 |
| 向量与业务库一致性 | **未做定期对账**（阶段二目标） | 通用 |
| 文档知识 | 0 篇（阶段一目标） | 通用 |

---

## 4. 当前发现的失败案例与根因

> 下面 6 个案例均为本轮验证期真实问句（具体业务词以"公司/证照/经营状态"为例），来自 `data/qa_logs/ask_events.jsonl` 与 `gate_metrics.jsonl`。
> "根因"指的是**架构/控制流层面的问题**，不是"调参能修"的问题。
> 案例中出现的"注册资本/经营状态"等业务词是**示例**——同样的失败模式在任何业务实体上都会出现。

### 4.1 案例 1：阈值筛选字段缺失

- **问句（示例）**："注册资金 100w 以上的公司有哪些"
- **答**："抱歉，根据当前提供的证据，无法回答您这个问题。"
- **埋点**：`intent=mysql, queryType=aggregate, evidenceCount=30, canAnswer=true, route=unified_constrained_rerank_dashscope_generate_llm`
- **根因**：
  1. 问句过滤维度「注册资本」在 evidence snippet 中**未携带取值**（源数据多为 NULL + `company_info` 无 projections + 向量摘要未暴露该字段）
  2. 四路召回凑满 30 条实例行，但无阈值字段
  3. 闸门只看 `evidenceCount=30 ≥ 1` 判 canAnswer=true，**未校验 schema 覆盖度**
  4. LLM 拿到无注册资本取值的 evidence，硬编"无法回答"
- **本系统职责边界**：
  - 不补数据：业务字段维护责任在业务方
  - 应在 evidence 链路中**显式标识该字段缺失**，让 LLM 提示"该字段在源系统内未维护"
  - 闸门可优化为"问句过滤维度字段是否在 evidence 中"判别

### 4.2 案例 2：枚举问句被实例淹没

- **问句（示例）**："公司经营状态包含哪些种类？"（followup 第 3 轮）
- **答**："公司经营状态只出现了一种——存续。"
- **埋点**：`intent=graph, queryType=aggregate, evidenceCount=7, route=unified_constrained_rerank_dashscope_generate_llm`
- **根因**：
  1. 上一轮的 queryType=aggregate 错误继承（实际应是 type_catalog）
  2. `NeedInferenceService.shouldPreferQueryTypeNeed()`：多轮合并后 `intent.hasCompanyHints()` 为真，**压制** `operating_status_catalog` 规则
  3. unified 虽有 `type_catalog` 早退分支，但 need 未识别为 type_catalog 时不触发
  4. 四路召回 7 条全是"具体公司 + 经营状态=存续"的实例行
  5. 枚举目录（`enterprise-enums.json` / `catalog_v1`）有完整状态集但**未被召回**
- **修复方向**：
  - P1.5：枚举问句优先 type_catalog need，不被 companyHints 压制
  - P1：catalog 问句重置会话 hints + queryType
  - P3：type_catalog 命中时跳过 hybrid+rereank（分步，不必一次删 unified）

### 4.3 案例 3：时间阈值字段不在 evidence

- **问句（示例）**："成立时间超过 10 年的公司有哪些"（followup 第 4 轮）
- **答**："缺失的信息维度是成立时间（成立日期），需要补充包含该字段的证据后才能作答。"
- **埋点**：`intent=graph, queryType=aggregate, evidenceCount=7, route=unified_constrained_rerank_dashscope_generate_llm`
- **根因**：
  1. 源表 `tdcomp.company.establishment_date` 字段存在（char(8) YYYYMMDD）
  2. 但 MySQL structured 通道的列白名单里**没有这个字段**
  3. evidence snippet 渲染时不包含此字段
  4. LLM 看不到成立日期，无法做"超过 10 年"判断
- **修复方向**：
  - `business-rules.json` 的 `company_info.projections` 补 `establishment_date` 等字段
  - `graph-company-facets.json` 为 `aggregate` queryType 补 `establishedDate` / `registeredCapital` facet

### 4.4 案例 4：会话锚点跨轮污染

- **现象（示例）**：第 2 轮"北京的公司有哪些" → 第 3 轮"公司经营状态包含哪些种类" 仍然带着 region=北京 + 主体锚点
- **根因**（多层叠加）：
  1. `FollowUpSessionHintMerger` 继承上轮 company hints；`isUnscopedListQuestion` **不覆盖**「经营状态包含哪些种类」
  2. **`ConstraintResolver.resolve(retrievalQuestion, …)`** 从含 `[上文] 北京的公司…` 的拼接串抽出 region=北京（与 need 推断用的当前轮 `question` 不一致）
  3. 硬约束过滤 + 向量 region 过滤继续按「北京」收窄
  4. 枚举目录被实例行淹没
- **修复方向**：
  - P0.5：约束解析**仅用当前轮** `question`
  - P1：扩展 `isCatalogQuestion`，命中时清空 hints + queryType（复用 `ConversationScopeSupport`，不另起 `isFreshQuestion`）

### 4.5 案例 5：queryType 跨轮错继承

- **现象（示例）**：aggregate → type_catalog → aggregate 三轮，queryType 全部被前一轮覆盖
- **根因**：
  1. `QaAskFlowService` 中 `followup_querytype_inherit` 逻辑直接 `inherit` 而非 `re-evaluate`
  2. 没有"问句形态兼容性"判别
- **修复方向**：
  - 同 4.4，followup 重置时一并清 queryType

### 4.6 案例 6：unified 通道"撒网"导致答非所问

- **现象**：所有问句都走 `unified_constrained_rerank_dashscope` 兜底通道
- **根因**：
  1. 意图路由判定不确定时直接落兜底
  2. 兜底通道对所有问句都跑 4 路召回
  3. 召回碎片的合并副作用——`哪些种类`问句被 7 家公司行淹没
  4. 闸门基于"条数"判断而非"信号源覆盖度"判断
- **修复方向**：
  - P3 分步：`type_catalog` / 阈值筛选命中时走专用通路；unified 暂保留作 unknown 兜底
  - 术语：文档中的「检索形态」用 `InformationNeed.granularity`，勿与 `ConstraintSet.QueryShape` 混淆

### 4.7 失败案例汇总表

| # | 问句（示例） | 主根因类别 | 修复项 | 涉及文件 |
|---|---|---|---|---|
| 1 | "注册资金 100w 以上的公司有哪些" | evidence 无阈值字段 | P4 字段缺失透明化 | 闸门、prompt |
| 2 | "公司经营状态包含哪些种类" | need 被 companyHints 压制 | P1 + P1.5 | NeedInferenceService |
| 3 | "成立时间超过 10 年的公司有哪些" | facet/projections 缺字段 | P2 | business-rules、graph-company-facets |
| 4 | followup 继承 region | retrievalQuestion 污染约束 | P0.5 + P1 | QaAskFlowService、ConstraintResolver |
| 5 | queryType 跨轮错继承 | catalog 未重置 queryType | P1 | IntentScopeNormalizer |
| 6 | unified 撒网 | 未按 granularity 分流 | P3（分步） | QaRetrievalPipeline |

### 4.9 历史已知问题映射（`enterprise-qa-known-issues.md`）

| 历史 ID | 问题 | 阶段一对应项 | 状态 |
|---|---|---|---|
| Q-01 | 法人列表召回不全 | P3 改造时**回归保护**（不可回退） | 已修复，需锁定 |
| Q-02 | 多轮任职→证照不切换 | **P1.5** 追问题型切换 | 进行中 |
| Q-03 | SSE 卡死 | 已修复 | 维持 |
| Q-04 | LLM 归纳漏列 | 阶段一验收加「条数一致性」 | 待办 |
| Q-07 | 全局列表被会话锁死 | P1 conversationScope（2026-06 部分已落地） | 部分修复 |

> 回归用例见 `data/eval/qa_cases.jsonl`（含 §4 六案例 + Q-02/Q-01 场景）。

### 4.8 Agent 视角的失败模式（不在当前 6 例中，但目标态要避免）

| Agent 失败模式 | 当前实现 | 目标实现 |
|---|---|---|
| 规划错：选错检索通路 | 落到 unified 兜底 | Agent 显式规划，告知用户"我打算这样检索" |
| 反思错：明明不充分却硬答 | 闸门松 + 凑 evidence 生成 | 阶段 9 反思 + 阶段 10 追问 |
| 工具调用错：缺工具时硬答 | LLM 兜底生成 | 主动声明"需要 X 工具" + 引导用户提供 |
| 不会多轮：单轮思维 | 当前各轮独立处理 | Agent 维护任务状态机，支持跨轮推进 |

---

## 5. 未来计划

> 按用户拍板的两阶段划分。
> 工作量不估"人日"——按"复杂度（轻/中/重）"和"风险（低/中/高）"标注。

### 5.0 业务无关化（前置到阶段一，与阶段一并行实现）—— 全局基础

**目标**：把代码中所有"业务实体/关系/字段"硬编码移到 JSON 配置，让本系统可服务任意企业。

**改动范围**：
- `application.properties` 中 `mysql-table-mapping` 等改为配置驱动
- `cdc-write-scope.json` 扩展为完整的 handler 注册表
- `graph-node-definitions.json` 已经是配置驱动（核对后）
- `business-rules.json` 关键词列表已配置驱动（核对后）
- `QaRetrievalPipeline` 中 switch 改为 dispatch by config
- `QaAnswerGateService` 中 schemaId 字符串改为配置
- `GraphContextService` 中节点 label 改为读配置

**复杂度**：重。**风险**：中。

**验收标准**：换一个示例企业（用 Order/Product/Contract 实体类型），除 JSON 配置外**零代码改动**即可工作。

**与阶段一的关系**：**不作为独立前置阶段**，而是与阶段一 P1-P6 同步推进——
- P1 涉及的 `QaAskFlowService` 重置判别：**业务无关**
- P2 涉及的 MySQL 列白名单：**业务无关**（白名单由配置驱动）
- P3 涉及的 queryShape 切通路：**业务无关**
- P4 涉及的反思+追问：**业务无关**
- P5 涉及的文档学习：**业务无关**
- P6 涉及的意图识别：必须确保 prompt 模板是**业务无关版本**（P6 自身就是业务无关化的一部分）

每完成一个 P 项时同步做对应的业务无关化改动，**不另开一阶段**。

---

### 5.1 阶段一：基本问答 + 文档学习

**进入阶段二的门禁**：阶段一所有 P 项（含业务无关化）的**测试效果符合预期**。判断标准是真实问句回放 + 对照测试集（20-30 个 case）通过率 ≥ 80%、且无新增的严重假阳（不出现 §4 列举的 6 类失败模式中的任何一类）。

**目标**：用现有企业数据 + 用户上传的文档，做到"问什么答什么"；信息不足时**追问**而非拒答；具备**Agent 雏形**（规划 + 反思 + 追问）。

**进入阶段二的门禁**：P0～P6 完成 + 回归集（20–30 case）通过率 ≥ 80%，且**无 §4 六类失败模式复现**，**Q-01 法人列表不回归**。

#### 5.1.0 P0：回归集 + 回放脚本（门禁前置）

**目标**：阶段一所有改动可量化验收。

**改动**：
- `data/eval/qa_cases.jsonl`：§4 六案例 + `intent-routing-cases.jsonl` 已有用例 + Q-01/Q-02
- `scripts/eval/run_qa_eval.py`：对 `/qa/ask` 批量回放，输出 CSV/通过率

**复杂度**：轻。**风险**：低。

#### 5.1.0b P0.5：约束解析仅用当前轮问句

**目标**：消除案例 4 中 region 从 `[上文]` 拼接串泄漏。

**改动**：`QaAskFlowService.retrieve` 中 `ConstraintResolver.resolve(question, …)` 改用用户当前轮原问，**不用** `retrievalQuestion`。

**复杂度**：轻。**风险**：低。

#### 5.1.1 P1：catalog / followup 上下文重置

**目标**：消除案例 4、5 的假阳。

**改动**（扩展已有 `ConversationScopeSupport`，**不**在 `QaAskFlowService` 另起 `isFreshQuestion`）：
- `business-rules.json` → `conversationScope.catalogQuestionMarkers`
- `ConversationScopeSupport.isCatalogQuestion()`：含「哪些种类/包含哪些类型」等且**无**「这些/那些/它们」指代
- `IntentScopeNormalizer` / `FollowUpSessionHintMerger`：catalog 命中时清空 hints + **queryType**

**复杂度**：轻。**风险**：低。

#### 5.1.1b P1.5：need 优先级 + 追问题型切换

**目标**：案例 2 + 历史 Q-02（任职→证照）。

**改动**：
- `NeedInferenceService`：显式 catalog 问句不被 `hasCompanyHints()` 压制
- `FollowUpSessionHintMerger`：上轮 `person_role_list` + 本轮证照语义 → 强制 `person_certificate_list`

**复杂度**：轻。**风险**：低。

#### 5.1.2 P2：projections + graph facet 补全

**目标**：消除案例 3 的假阳。

**改动**：
- MySQL structured 通道的列白名单注册业务方源表里的所有业务字段（**业务无关：白名单由配置驱动**）
- snippet 渲染器对日期/数值字段做友好格式化
- 同步检查 `operation_start_date` 等同类字段

**复杂度**：轻。**风险**：低。

#### 5.1.3 P3：按 InformationNeed.granularity 分流（分步）

**目标**：消除案例 2、6 的假阳；**不一次性删除 unified**。

**设计（分步）**：

```
用户问句
  ↓
NeedInferenceService → granularity
  ├─ type_catalog   → 枚举目录召回，跳过 hybrid+rererank
  ├─ instance       → MySQL + Neo4j 关系
  ├─ aggregation    → MySQL / 图谱阈值字段 facet
  └─ semantic       → Qdrant
  ↓
其余 unknown → unified（暂保留）
  ↓
硬约束仅来自当前轮 question（P0.5）
```

**改动**：
- 3a（本迭代可先做）：`type_catalog` 早退强化
- 3b：aggregation 专用 facet / SQL
- 3c：验证 Q-01 无回归后再收窄 unified

**复杂度**：重（分步后首期为中）。**风险**：中。

> **术语**：文档「检索形态」= `InformationNeed.granularity`；`ConstraintSet.QueryShape` 仅用于硬约束，勿混用。

#### 5.1.4 P4：闸门 → 反思 → 追问（Agent 雏形）

**目标**：消除"信息不足时硬答"的退化输出（案例 1）。

**设计**：
- 阶段 9 改为"反思"：基于 schema 覆盖度判断 evidence 是否充分
- 阶段 10 改为三态：
  - **充分 → 直接生成答案**
  - **部分充分 → 生成"补全追问"（如"需要 X 字段"）**
  - **完全无证据 → 生成"澄清追问"（如"你说的 X 是指 Y 吗"）**
- prompt 中显式标识字段缺失（业务方维护责任，不在系统内补）

**改动**：
- 扩展 `QaAnswerGateService` 为 schema 覆盖度反思
- **复用**已有 `PersonClarificationAdvisor` / `CompanyClarificationAdvisor`，新增字段缺失类 LLM 追问（不必平行新建 `ClarificationGenerator` 体系）
- prompt 模板新增「字段在源系统未维护」说明

**复杂度**：中。**风险**：中。

#### 5.1.5 P5：文档学习通路（扩展已有基础）

**目标**：用户上传 PDF/Word/Markdown/TXT 进入 Qdrant，与结构化数据统一检索。

**已有基础**：`DocumentContextService`、`qa_document_chunk` 表、`docs-dir` 编译文本管线。

**改动**：
- 新增 `DocumentIngestController`（上传接口）
- 新增多格式 `DocumentParser`（解析 + 切块）
- embedding 写入现有 collection；检索端作为独立 evidence source

**复杂度**：中（有基础）。**风险**：低。

#### 5.1.6 P6：意图识别强化（MiniMax-M3 辅助）

**目标**：结构化问句逐步从「规则优先」转向「模型优先，规则 fallback」。

**注意**：当前 `intent-rule-first-for-structured=true` 为 Q-03 首包延迟权衡；翻转 P6 时需保留异步/规则预路由，验收加 **SSE 首包 < 2s**。

---

### 5.2 阶段二：CDC 完善 + 定期对账

**目标**：保证图谱、向量、文档与业务库实时一致，**漂移窗口可被自动发现**。

#### 5.2.1 S1：CDC 写入策略化（业务无关化）

**目标**：消除 `Neo4jCdcWriter.switch(table)` 硬编码。

**改动**：
- 新增 `CdcTableHandler` 接口（`upsert/delete`）
- 新增 `CdcTableHandlerRegistry`（按 `cdc-write-scope.json` 加载）
- 新增 `com/qa/demo/qa/cdc/handlers/*CdcHandler.java`（把现有 `upsertCompany/...` 平移）
- 启动期反射校验 handler Class.forName 存在

**复杂度**：中。**风险**：低。

#### 5.2.2 S2：定期对账（图谱 vs 业务库）

**目标**：检测图谱节点与 MySQL 行是否一致。

**设计**：
- 按业务表的 `modifytime` 列做增量对账
- 对账维度：行数、主键存在性、关键字段值一致性
- 不一致项 → 报警 + 自动触发 CDC 重放

**复杂度**：中。**风险**：中。

#### 5.2.3 S3：定期对账（向量 vs 业务库）

**目标**：检测 Qdrant chunk 与 MySQL 行/文档源是否一致。

**设计**：
- 对结构化数据：按主键 + modifytime 增量对账
- 对文档：按文件 hash + chunk 数量对账
- 不一致项 → 报警 + 触发向量重灌

**复杂度**：中。**风险**：中。

#### 5.2.4 S4：CDC → Agent 事件流（可选）

**目标**：业务库变更事件作为 Agent 的"环境感知"信号，主动触发 Agent 行动。

**示例场景**：
- 业务方新增了一家公司 → Agent 主动学习 → 写入知识库
- 业务方修改了某字段 → Agent 验证知识库一致性

**复杂度**：重。**风险**：高（需要业务方接受 Agent 自动行为）。

---

### 5.3 阶段三（待定）—— 完整 Agent / AI Native 形态

**触发条件**：阶段一 + 阶段二"跑稳"。具体判断标准：

- 真实问句回放 + 对照测试集（30+ case）通过率持续 ≥ 90%，且无新增严重假阳
- 阶段二的 S2/S3 定期对账稳定运行 ≥ 2 周，对账差异率 < 0.1%
- 业务无关化（§5.0）完成度 ≥ 80%（盘点清单的 80% 项已迁出硬编码）

如果满足上述条件，再考虑：

- **工具调用（Function Calling）**：业务库查询、图谱查询、文档检索都包装为 Agent 可调用的工具
- **规划能力**：MiniMax-M3 作为推理引擎，把"用户问题"分解为"工具调用序列"
- **反思能力**：每一步工具调用后判断"信息够不够"
- **多轮状态机**：Agent 维护任务状态，支持跨轮推进（如"先查 X → 再查 Y → 整合回答"）
- **主动行为**：基于业务库变更事件，Agent 主动推送洞察

> 这一阶段不在近期计划内，**待阶段一/二跑稳后再决定**。

### 5.4 不做（明确划出去）

- 不做 Drools 9.x 引入（精简后已移除，不重新引入）
- 不做 queryType 枚举化（用 queryShape 替代，更通用）
- 不做 EvidenceMerger 5 段流水线（按当前规模不必要）
- 不做多租户隔离、权限分级
- 不做 6 个月双写兼容（业务表数量稳定后已无意义）
- 不补业务字段数据（业务方职责）

### 5.5 阶段一/二优先级矩阵

**阶段一（P0～P6）**：

| 编号 | 任务 | 复杂度 | 风险 | 依赖 |
|---|---|---|---|---|
| **P0** | 回归集 + 回放脚本 | 轻 | 低 | 无 |
| **P0.5** | 约束解析用当前轮 question | 轻 | 低 | 无 |
| **P1** | catalog 问句 scope 重置 | 轻 | 低 | 无 |
| **P1.5** | need 优先级 + 追问题型切换 | 轻 | 低 | P1 |
| **P2** | projections + aggregate facets | 轻 | 低 | 无 |
| **P3** | granularity 分流（分步） | 中→重 | 中 | P1.5 |
| **P4** | 闸门→反思→追问 | 中 | 中 | P3 |
| **P5** | 文档上传扩展 | 中 | 低 | 无 |
| **P6** | 意图 LLM 优先（含延迟权衡） | 中 | 低 | 无 |

**阶段二（S1-S4）**：

| 编号 | 任务 | 复杂度 | 风险 | 依赖 |
|---|---|---|---|---|
| **S1** | CDC 写入策略化 | 中 | 低 | 阶段一 P1-P6 跑稳 + 5.0 业务无关化 ≥ 80% |
| **S2** | 图谱 vs 业务库对账 | 中 | 中 | S1 |
| **S3** | 向量 vs 业务库对账 | 中 | 中 | S1 |
| **S4** | CDC → Agent 事件流 | 重 | 高 | S2+S3 + 阶段一跑稳 |

---

## 6. 风险、待办、决策记录

### 6.1 当前最大风险

| 风险 | 触发条件 | 缓解 |
|---|---|---|
| **数据漂移窗口** | CDC 同步中，主库改了但 Neo4j/Qdrant 还没追上 | 阶段二 S2/S3 定期对账 |
| **图谱与业务库不一致** | 业务方源表 60+ 列里，多数业务字段为 NULL，灌库时空值被填进了图谱 | 重灌库时统一 NULL 处理（暂未排期） |
| **会话锚点污染** | 多轮对话中，**当前**最严重的假阳源 | 阶段一 P1 修复 |
| **MySQL 字段没进 evidence** | 列白名单不完整 | 阶段一 P2 修复 |
| **LLM 兜底答非所问** | 闸门松 + 证据无关键字段 | 阶段一 P4 修复（追问而非硬答） |
| **业务无关化遗留** | 代码中仍有 `company/employee/certificate` 等业务词硬编码 | §5.0 业务无关化与阶段一 P1-P6 同步推进，不另开阶段 |
| **Agent 反思/追问能力缺失** | 当前实现是 RAG 而非 Agent | 阶段一 P4 引入反思+追问 |
| **多轮状态机缺失** | 当前各轮独立处理 | 阶段三（待定） |

### 6.2 待办（按用户拍板的两阶段）

> **进度拉齐说明（2026-06-16）**：以下区分 **代码已合并**（工作区/Java 已落地）与 **eval 已验收**（`run_qa_eval.py` 在运行中服务上通过）。二者不一致时以 eval 为准。

#### 6.2.1 评测基线快照（2026-06-16）

| 指标 | 结果 | 产物 |
|---|---|---|
| 全量 26 case | **26/26 通过（100%）** | `data/eval/qa_eval_results.csv`（2026-06-16 修复后） |
| §4 标签 14 case | **14/14**（随全量一并通过） | `data/eval/qa_eval_section4.csv` |
| Q-01 法人列表 | **通过**（27 条 evidence） | `q01_*` case |
| 阶段一门禁 | **未达标**（目标 ≥80%） | — |

**本轮 eval 已确认的根因（非「服务未重启」单因）**：

1. **LLM `retrievalStrategy` 覆盖 need 推断**：`InformationNeed.fromRetrievalStrategy(TYPE_CATALOG)` 产出 `facet=catalog`，与 `retrieval-catalog.json` 中维度 facet（`profile`/`certificate` 等）**不匹配**，导致 `retrieveTypeCatalogOnly()` 返回空 → 仍走 unified。
2. **阈值问句 P4 未触发**：注册资本类问句 LLM 常判 `STRUCTURED_LIST`（`facet=list`），未进入 `EvidenceFieldCoverageAdvisor` 规则 → 仍硬生成。
3. **MySQL 配置与 classpath 漂移**：`config-source=mysql_fallback` 优先读 `qa_config_bundle`；启动期 Bean（`BusinessRulesConfig`/`RetrievalCatalogRegistry`）**不会**因 `/qa/admin/config/publish` 热更新。已 publish classpath 九件套并重启，catalog 类仍受 #1 阻塞。
4. **§4 子集 14/14 结论已失效**：`qa_eval_section4.csv`（2026-06-15 旧跑次）不可再作验收依据。

#### 6.2.2 阶段一代办（代码 vs eval）

| 项 | 代码 | eval | 说明 |
|---|---|---|---|
| P0 回归集 + 脚本 | ✅ 26 case + `run_qa_eval.py` | ⚠️ 已跑通，**34.6%** | baseline 已刷新至 `qa_eval_baseline.csv` |
| P0.5 约束解析 | ✅ | ⚠️ 未单独断言 | 代码已用当前轮 `question` |
| P1 catalog scope 重置 | ✅ | ❌ catalog 未召回 | 受 #1 facet 不匹配阻塞 |
| P1.5 need 优先级 | ✅ | ⚠️ 部分 | Q-02 通过；followup catalog 仍失败 |
| P2 projections + facets | ✅ 配置已补 | ❌ case3 仍 generate | 期望过弱；成立日期未进 evidence 闭环 |
| P3 type_catalog 通路 | ✅ 代码有早退 | ❌ 未生效 | `matchDimensions` 对 `facet=catalog` 无匹配 |
| P4 字段缺失追问 | ✅ 规则模板 | ❌ 注册资本类未触发 | LLM strategy 绕过 advisor |
| P5 文档上传 | ✅ MD/TXT→MySQL | — | 未入 Qdrant；无 PDF/Word |
| P6 LLM 意图优先 | ❌ rule-first | — | `intent-rule-first-for-structured=true` |
| 阶段一总验收 | — | ❌ | 距 80% 门禁差 **~12 case** |

**阶段一（含 §5.0 业务无关化并行）**：
- [ ] 业务无关化梳理清单
- [x] P0.5 约束解析用当前轮 question（**代码 2026-06-15**；eval 待补断言）
- [x] P1 catalog 问句 scope 重置（**代码 2026-06-15**；eval **未通过** — 见 #1）
- [x] P1.5 need 优先级 + 追问题型切换（**代码 2026-06-15**；eval 部分通过）
- [~] P2 projections + aggregate facets（**配置已补**；阈值问句 **eval 未闭环**）
- [~] P3 type_catalog 专用通路（**代码有早退**；eval **未通过** — 需修 facet 映射或 LLM/规则 merge）
- [~] P4 字段缺失 → 追问澄清（**规则模板已合并**；eval **未通过** — 需覆盖 LLM list/semantic 路由）
- [x] P5 文档上传 API（`/qa/documents/upload`，MD/TXT→`qa_document_chunk`；**非**文档规划中的 Qdrant/PDF/Word）
- [~] P0 回归集 + 基线（**26 case + 脚本 OK**；baseline **2026-06-16 已刷新**；通过率 **34.6%**）
- [ ] P6 意图 LLM 优先（含 SSE 延迟验收）
- [ ] **P1 紧急修复**：`TYPE_CATALOG` need 与 `retrieval-catalog` facet 对齐（或 LLM strategy 不覆盖规则 need）
- [ ] **P1 紧急修复**：`EvidenceFieldCoverageAdvisor` 在 LLM `STRUCTURED_LIST`/unified 路径同样生效
- [ ] 阶段一验收：≥ 80% + §4 六类不复现 + Q-01 不回归（当前：**9/26**；Q-01 **已通过**）

**阶段二**：
- [ ] S1 CDC 写入策略化
- [ ] S2 图谱 vs 业务库对账
- [ ] S3 向量 vs 业务库对账
- [ ] S4 CDC → Agent 事件流（可选）

### 6.3 决策记录

| 日期 | 决策 | 原因 |
|---|---|---|
| 2026-06-15 | 移除 P0 计划的 Drools 9.x 依赖与全部 DRL | 项目是验证性质，不需生产级规则引擎；埋点最薄 |
| 2026-06-15 | 移除 P0 计划的 `qa.rule.*` 灰度开关 | 与 Drools 一起撤回 |
| 2026-06-15 | `queryType` 维持 String，不做枚举化 | 用 queryShape 替代语义更清晰 |
| 2026-06-15 | 同意"四路召回方案在 386 家公司规模下不可用" | 决定按 P3 改造（queryShape 切通路） |
| 2026-06-15 | 数据源扩为"结构化 + 文档"并列 | 阶段一目标包含文档学习 |
| 2026-06-15 | 目标形态定为 Agent / AI Native | 不是 RAG，需规划+反思+追问 |
| 2026-06-15 | **不拒答**，信息不足时**追问** | 用户明确要求 |
| 2026-06-15 | 业务字段维护责任在业务方 | 战投/参股企业字段本系统无管理需求 |
| 2026-06-15 | 估算不写"人日" | 验证期项目，效果优先；AI 辅助编码 |
| 2026-06-15 | 意图识别主用 MiniMax-M3 | 规则仅作 fallback |
| 2026-06-15 | **业务无关化**为全局基础 | 用户明确要求通用企业知识库，不与具体业务耦合 |
| 2026-06-15 | 分两阶段：阶段一基本问答+文档，阶段二 CDC+对账 | 用户拍板 |
| 2026-06-15 | **业务无关化前置到阶段一**，与 P1-P6 同步推进 | 用户拍板——不作为独立前置阶段 |
| 2026-06-15 | **阶段一测试效果符合预期后**才进阶段二 | 用户拍板——门禁：测试通过率 ≥ 80% + 无新增严重假阳 |
| 2026-06-15 | 阶段三触发条件 = 阶段一+二"跑稳" | 用户拍板——具体指标：测试通过率 ≥ 90% + 对账差异率 < 0.1% + 业务无关化 ≥ 80% |
| 2026-06-15 | 增补 P0/P0.5/P1.5；P3 改为分步不删 unified；约束解析与 need 根因写入 §4 | 代码走读 + gate_metrics 交叉验证 |
| 2026-06-15 | 检索形态术语 = `InformationNeed.granularity`，与 `ConstraintSet.QueryShape` 分离 | 避免 P3 命名冲突 |
| 2026-06-16 | **进度拉齐**：全量 eval **9/26（34.6%）**；§4 子集 **1/14**；旧 14/14 结论作废 | 2026-06-16 重跑 + 配置 publish/重启；根因为 LLM facet 与 catalog 维度不匹配 |
| 2026-06-16 | §6.2 待办改为「代码 / eval」双列；P2–P4 由 ✅ 降为 [~] | 避免「代码合并=验收通过」误判 |
| 2026-06-16 | classpath 九件套 publish 至 `qa_config_bundle`（`/qa/admin/config/publish`） | 消除 MySQL 与 resources 漂移；**仍需重启**才加载到 Spring Bean |
| 2026-06-16 | P1 紧急修复：`InformationNeed.mergeWithLlmStrategy`、字段取值覆盖率、证照 fallback | 全量 eval **26/26（100%）** |

### 6.4 与已有文档的关系

| 文档 | 关系 |
|---|---|
| `docs/architecture.md` | 当前**实际**架构（本文件的 §2 与之有重叠，但本文档包含失败案例与未来计划） |
| `docs/enterprise-qa-known-issues.md` | 历史已知问题清单（本文件 §4 是本轮新增） |
| `docs/platform-retrieval-architecture.md` | 检索架构理想态（与本文档的 R1/R2 方案对应） |
| `docs/knowledge-mysql-migration-plan.md` | 知识库 MySQL 化迁移（与本文档 §3 数据层对应） |
| `openspec/specs/knowledge-assistant/spec.md` | 项目 spec（本文件是其当前实现说明） |

---

> 文档结束。后续每次重大决策请追加到 §6.3。
