# 人物证照问答方案（设计计划）

> 版本：2026-05（讨论沉淀）  
> 状态：待评审 / 待实施（P0 起）  
> 关联：[系统架构总览](./architecture.md)、[企业管道 README](../scripts/enterprise_pipeline/README.md)

---

## 1. 背景与问题

### 1.1 业务场景

用户典型交互：

1. **第一轮**：「XXX 负责哪些证照 / 管哪些资质证照」
2. **第二轮**：「涉及类型有哪些 / 具体是哪些证照」（承接第一轮，追问证照**类型**明细）

期望助手输出（结构化）：

| 字段 | 说明 |
|------|------|
| 证照类型 | 业务枚举中文名（如 ICP备案、营业执照-独立法人） |
| 所属公司 | 证照关联主体 |
| 角色 | 执行人 / 保管人 / 监管人（可多角色、多行） |
| 状态 | 有效 / 失效等（可选） |

### 1.2 现状与痛点

| 现象 | 根因归纳 |
|------|----------|
| 第一轮证照类型列不全、条数与库不一致 | 无「人×证照」查询形态；召回以**公司向量块**为主，证照类型埋在长文本中 |
| 第二轮「类型有哪些」答成**主体类型**（有限责任公司等） | 「类型」未消歧；会话无 `topic=certificate`；检索走公司 `entityType` |
| 追问像重新开一问 | 客户端未稳定传递 `conversationId`；追问未绑定 `anchorPerson` |
| 担心「一人一照灌库」导致数据爆炸 | **合理**：不应做人×证×公司的笛卡尔积或第二套全量向量库 |

### 1.3 设计目标

- **意图准确**：识别「人物证照职责」与「公司证照」「任职列表」的边界。
- **多轮连贯**：第二轮「类型」默认指**证照类型**，禁止误用主体类型。
- **数据可控**：规模与 `certificate_management` 实际关系同阶，**不**新增无限增长的知识库形态。
- **可验收**：可与 MySQL 业务表对账（如执行人维度 23 条）。

---

## 2. 设计原则

1. **单一事实源（SoT）**  
   业务真相：`tdcomp.certificate_management` + `company` + `employee`。  
   管道产物：`data/knowledge/enterprise_mysql_clean.jsonl`（公司聚合）为离线镜像，**不是**第二套事实库。

2. **唯一标识与业务属性解耦**  
   - **标识**：`employee_id`、`company_id`、`certificate_management.id`、`certificate_type` 枚举码 —— 仅用于关联、去重、精确查询。  
   - **属性**：姓名、证照类型中文名、角色标签、状态 —— 用于证据 snippet 与面向用户的生成。  
   - 意图层：`personName` = 展示用规范名；`personEmployeeId` = 检索锚点（在 `PersonNameResolver` 一次性绑定）。  
   - 禁止：用姓名 LIKE 在检索层猜人；用 `intent=mysql` 表达「人物证照」业务形态；在 snippet 中展示类型码或 `公司#123` 式编码解释。

3. **人物证照 = 查询视图，不是新知识库**  
   不在 Qdrant 为「每人每证」建独立 point；不在全量组合下预计算笛卡尔积。

4. **意图 → 检索 → 生成分工**  
   - 意图：定 `queryType`、锚点人物、会话 `topic`、输出契约。  
   - 检索：按形态走专用通道，证据 snippet 统一三列。  
   - 生成：仅依据证据填表，禁止用 `entityType` 凑证照答案。

5. **公司维度仍是主索引**  
   向量 / Neo4j / `compiled.txt` 继续服务「某公司有哪些证照」；人物证照走**结构化查询优先**。

6. **增量可维护**  
   新业务证照 → 现有 build/sync 流程 → 人物证照查询自动覆盖，无需单独「人证照库」同步。

---

## 3. 方案总览

```
用户问句
    │
    ▼
┌─────────────────────────────────────┐
│ 多轮：conversationId + topic 继承      │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 意图层：IntentCard（需求卡片）         │
│  queryType / anchorPerson / topic     │
│  followUpKind / outputSchema          │
└─────────────────────────────────────┘
    │
    ├── person_certificate_list ──► PersonCertificateQuery（MySQL 主，JSONL 兜底）
    ├── company_certificate     ──► 图谱 + 向量 + 文档（现有）
    └── person_role_list        ──► GraphPersonRole（现有，法人董事监事）
    │
    ▼
┌─────────────────────────────────────┐
│ 证据 ContextChunk + AnswerGate       │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 生成：契约 prompt（三列表格/列表）    │
└─────────────────────────────────────┘
```

### 3.1 与「一人一照灌库」的区别

| 误解做法 | 本方案 |
|----------|--------|
| 每人 × 每证类 × 每公司 预计算 | 仅展开业务表已有 supervisor/keeper/executor 关系 |
| 第二套 Qdrant 全量 | Qdrant 仍 ≈ 公司数；人物证照 **查询时** 组装 |
| 新知识灌 assistant 再乘一遍 | 新知识进 SoT，视图查询即得 |

**规模上界**：约「证照行数 × 每行 1～3 个角色人」，与当前库量级（数百～两千行关系）同阶，**非**组合爆炸。

---

## 4. 核心概念：IntentCard（需求卡片）

在每轮 `IntentDecision` 之上（可内嵌或并行 DTO），供检索与生成只读。

| 字段 | 含义 | 示例 |
|------|------|------|
| `queryType` | 查询形态 | `person_certificate_list` / `company_certificate` / `person_role_list` |
| `anchorPerson` | 锚点人物（规范名，展示） | 张雁雯 |
| `anchorEmployeeId` | 锚点员工主键（检索） | 110008506 |
| `topic` | 会话主题 | `certificate` |
| `followUpKind` | 追问细分 | `certificate_type_enum` / `detail_list` / `none` |
| `outputSchema` | 允许输出字段 | certType, companyName, stewardRole, status |
| `forbiddenFields` | topic=certificate 时禁止 | entityType, entityCategory |
| `intent` | 检索通道偏好 | `mysql` 或 `hybrid`（人物证照以结构化为主） |

### 4.1 路由规则（硬编码优先）

| 问句特征 | queryType | topic |
|----------|-----------|-------|
| 人名 +（证照\|许可证\|资质）+（哪些\|负责\|管） | `person_certificate_list` | `certificate` |
| 公司名 + 证照 | `company_certificate` | `company` |
| 人名 + 法人/董事/监事 + 哪些公司 | `person_role_list` | `person_role` |
| 上轮 topic=certificate + 本轮含「类型」 | 继承 `person_certificate_list` + `followUpKind=certificate_type_enum` | `certificate` |

**歧义**：「类型」在 `topic=certificate` 下 **仅** 指证照类型，**不得** 映射为公司主体类型。

### 4.2 对现有意图模块的改动点

| 组件 | 改动 |
|------|------|
| `KnowledgeAssistantPrompts.intentRouterLlmSystemPrompt` | 增加 `person_certificate_list` 定义与正反例 |
| `enterprise-lexicon.json` | `queryTypeRules` 增加人物证照规则，优先于仅关键词的 `company_certificate` |
| `QuestionEntityExtractor` | 人名模式：`{姓名}(负责\|管)…(证照\|许可证)` |
| `IntentSlots` | 校验新 queryType；`isRetrievalReady` 对人物证照要求 `hasPersonFocus` |
| `QaConversationService` | 持久化 `topic`、`anchorPerson`、`lastQueryType`；追问改写检索种子 |

---

## 5. 数据层

### 5.1 保持不变（主形态）

| 产物 | 用途 | 规模 |
|------|------|------|
| `enterprise_mysql_clean.jsonl` | Neo4j / Qdrant 灌库输入 | O(公司数) |
| `enterprise_mysql_compiled.txt` | 文档召回 | O(公司数) |
| `enterprise_knowledge_v2`（Qdrant） | 语义召回 | O(公司数) |
| `certificate-seal-enums.json` | 证照/印章类型 code→中文 | 常量级 |

证照类型枚举与管道映射见 `scripts/enterprise_pipeline/schema_field_maps.py`、`src/main/resources/qa/certificate-seal-enums.json`。

### 5.2 人物证照视图（新增逻辑，非新库）

#### 方案 A：运行时结构化查询（**推荐主路径**）

- 配置：`qa.assistant.business-mysql-url`（`jdbc:mysql://.../tdcomp`）
- 逻辑概念：
  1. 读 `certificate_management`（`deleteflag=0`）
  2. 解析 `supervisors` / `certification_keepers` / `executors` 员工 ID
  3. JOIN `employee`（姓名）、JOIN `company`（公司名）
  4. `certificate_type` 经枚举映射为中文（与 build 管道一致）
  5. WHERE 姓名匹配 `anchorPerson`（含 `PersonNameResolver` 规范名）
- 输出：内存聚合为 `List<PersonCertificateRow>` → 转为 `ContextChunk`

**新增服务（计划）**：`PersonCertificateQueryService`（或 `MysqlPersonCertificateRetriever`）。

#### 方案 B：离线兜底（可选，P3）

- build 时从 `clean.jsonl` **按人合并** 写入 `compiled.txt` 一段（**每人 1 块**，非每人每证 1 向量）
- 仅当 MySQL 不可用时，`person_certificate_list` 走 `DocumentContextService`

#### 方案 C：Neo4j 增强（可选，P2）

- sync 时将 steward 信息写入 `Certificate` 属性或 `Person-[:STEWARD_OF]->Certificate`
- 边数 = 实际关系数；用于可解释性与 MySQL 交叉验证，**不替代** SoT

### 5.3 明确不做

- ❌ 人 × 全部证照类型 × 全部公司 的预计算表  
- ❌ 独立 Qdrant collection「每人每证一个 point」  
- ❌ 与 JSONL 双写且需单独全量同步的 `enterprise_person_certificates.jsonl`（可作调试导出，非主路径）

---

## 6. 检索策略

### 6.1 按 queryType 分流

| queryType | 主召回 | 辅召回 | 说明 |
|-----------|--------|--------|------|
| `person_certificate_list` | **PersonCertificateQuery** | 向量/文档低权重补充 | 证据须含 `证照类型=`；AnswerGate 无则拒答 |
| `company_certificate` | 图谱 HAS_CERTIFICATE + 向量 + 文档 | 现有 + facet 裁剪 | 需 `companyHints` |
| `person_role_list` | `GraphPersonRoleQuery` | 现有 | **不**承担证照职责查询 |
| 多轮 `certificate_type_enum` | 同上 + 生成侧重类型去重 | 会话继承 anchorPerson | 禁用 entityType |

### 6.2 统一召回（hybrid）调整

当 `unified-retrieval-enabled=true` 且 `queryType=person_certificate_list`：

1. **先注入** PersonCertificate 证据（高 score）
2. 再合并向量/图，重排时 **boost** 含「证照类型」的 snippet
3. 避免「设立中、无证照」公司块挤占 TopK

### 6.3 与现有图谱的关系

- `GraphPersonRoleQuery`：仅 `Person-[:HAS_ROLE_IN]->Company`（法人/董事/监事），**不**用于证照执行人/保管人。
- `GraphContextService.queryByCertificateIntent`：公司维度证照列表，**不**按人过滤；人物证照不走此路为主答案。

---

## 7. 多轮对话

### 7.1 客户端

- Playground / API 请求体须携带：`conversationId`（服务端返回后持久化）、可选 `followUp`。
- 换话题时：「新对话」清空 `conversationId`。

### 7.2 服务端（`QaConversationService`）

| 机制 | 说明 |
|------|------|
| `topic` | `certificate` / `person_role` / `company` |
| `anchorPerson` | 上一轮锁定人物 |
| `buildRetrievalQuestion` | 证照追问 → `{anchorPerson} 负责的证照类型列表`，非裸「类型有哪些」 |
| `buildModelContextBlock` | 明示：追问指证照类型，非主体类型 |
| `guessFollowUp` | 「具体/哪些 + 证照」或上轮含证照主题 → follow-up |

---

## 8. 生成层契约

当 `queryType=person_certificate_list` 或 `followUpKind=certificate_type_enum` 时，在 `minimaxEvidenceSystemPrompt` 增加专用条款：

1. 只依据证据；先给 **证照类型去重数量**（若适用），再列表：**证照类型 | 公司 | 角色 | 状态**。
2. **禁止**用「主体类型 / 有限责任公司 / 运营主体」回答证照或「类型有哪些」。
3. 条数与证据行数一致（与任职列表「列全」规则对称）。
4. 多轮 `certificate_type_enum`：以类型名为主，可引用上轮公司已列明细。

---

## 9. 实施阶段

| 阶段 | 交付 | 价值 | 预估 |
|------|------|------|------|
| **P0** | `person_certificate_list` 意图（LLM+规则）；`PersonCertificateQueryService`；检索分流 + AnswerGate；生成契约 | 单轮人物证照可用 | 中 |
| **P1** | 多轮 topic + 追问消歧；Playground 展示 topic/人物；`data/eval` 用例 | 第二轮不再答主体类型 | 小 |
| **P2** | Neo4j steward 属性/关系；与 MySQL 对账日志 | 可解释、可弱化运行时 MySQL 依赖 | 中（可选） |
| **P3** | build 按人一块写入 compiled（离线兜底） | 无 MySQL 环境可演示 | 小（可选） |

**不建议顺序**：在未完成 P0 意图与专用检索前，单独扩 compiled 或向量数据。

---

## 10. 验收标准

### 10.1 功能用例

| ID | 步骤 | 通过标准 |
|----|------|----------|
| PC-01 | 问：「张雁雯负责哪些证照」 | `queryType=person_certificate_list`；答案含类型+公司+角色；不出现主体类型 |
| PC-02 | 接 PC-01 问：「涉及类型有哪些」 | 同一 `conversationId`；输出证照类型去重列表；非 entityType |
| PC-03 | 问：「万仕道有哪些证照」 | `company_certificate`；行为与现网一致 |
| PC-04 | 全量同步后 | Qdrant points ≈ 公司数，不随人物证照视图倍增 |

### 10.2 数据对账（可选）

- 与 MySQL 统计口径文档化：如「执行人」维度 23 条 vs 问答列出行数。
- 保管人/监管人是否同一问句合并展示——**产品口径待确认**（见 §11）。

---

## 11. 待决策项

| # | 问题 | 选项 |
|---|------|------|
| 1 | 人物证照主路径 | **A** 运行时 MySQL（推荐） / **B** 仅 compiled 按人块 |
| 2 | Neo4j P2 | 做 steward 关系 / 暂不做 |
| 3 | 统计口径 | 仅执行人 / 执行+保管+监管合并展示 |
| 4 | 离线演示 | 是否必须 P3 compiled 按人块 |

---

## 12. 现状代码索引（实施参考）

| 能力 | 路径 |
|------|------|
| 意图路由 | `qa/intent/IntentRouterService.java` |
| LLM 意图 prompt | `knowledge/KnowledgeAssistantPrompts.java` |
| 词典规则 | `resources/qa/enterprise-lexicon.json` |
| 多轮 | `qa/response/QaConversationService.java` |
| 主流程 | `qa/orchestration/QaAskFlowService.java` |
| 统一召回 | `qa/retrieval/QaRetrievalPipeline.java` |
| 图谱证照（公司） | `qa/retrieval/GraphContextService.java` |
| 证照 build | `scripts/enterprise_pipeline/build_knowledge_from_mysql.py` |
| 全量学习 UI | `static/qa-playground.html` → `/qa/learn/knowledge-sync/from-mysql` |
| 业务库配置 | `qa/config/QaAssistantProperties.java` → `businessMysql*` |

---

## 13. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-05 | 初稿：讨论沉淀；否定笛卡尔积式灌库；采用 SoT + 查询视图 + IntentCard |
