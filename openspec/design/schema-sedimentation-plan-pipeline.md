# Schema 沉淀方案流水线（结构化方案 → 按路执行）

本文档描述 `POST /qa/mysql/sedimentation/pipeline` 的设计边界：在**只读元数据**（`information_schema` 导出的 Markdown 目录）基础上，由大模型输出**可解析的 JSON 沉淀方案**，再经**第二次可选模型调用**生成学习正文，最后由应用按方案**选择性启用** MySQL / Qdrant / Neo4j 写入通路。

---

## 1. 目标与不做的事

**目标**

1. 判断指定 schema 元数据是否**适合**沉淀为知识库文本（`feasible` + `feasibilityRationale` + `confidence`）。
2. 产出人类可读的 **`planSummaryMarkdown`**（运维/研发视角的步骤、风险、前提）。
3. 产出机器可消费的 **`sinks`**：分别控制 MySQL 文本表、Qdrant 向量、Neo4j 图谱是否写入，以及图谱关键词条数建议 `keywordLimit`。
4. 通过 **`ingest.bodyStrategy`** 选择学习正文来源：`model_digest`（二次模型精炼）或 `catalog_as_is`（直接使用目录 Markdown，可能触发长度截断）。
5. **`persist=true`** 时：正文写入 **`qa.assistant.mysql-url` / `mysql-schema` 所指的库**（与既有主动学习一致，通常为 `assistant`），**不**根据方案切换 JDBC 目标库。

**明确不做**

- 不在本接口接受**任意 JDBC URL**；数据源仍为 `source=configured`（`qa.assistant.mysql-*`）或 `source=dynamic`（与 `/qa/mysql/connect` 同形的主机/库名参数）。
- 不执行模型生成的 **SQL/Cypher**；Neo4j/MySQL 物理形态（表名、节点标签、集合名）**不由方案扩展**，仅允许开关各路已有实现。
- 不把业务表行批量拉取进学习通道（与 `mysql-schema-active-learning-pipeline.md` 一致）。

---

## 2. JSON 方案 schema（实现契约）

模型**必须**只输出一个 JSON 对象（实现侧用 `SchemaSedimentationPlanService.extractJsonObject` 容忍少量围栏噪声）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `feasible` | boolean | 是否建议沉淀 |
| `feasibilityRationale` | string | 中文理由 |
| `confidence` | number | 0～1 |
| `planSummaryMarkdown` | string | Markdown 方案摘要 |
| `sinks.mysql` | object | `enabled` boolean, `rationale` string |
| `sinks.qdrant` | object | 同上 |
| `sinks.neo4j` | object | `enabled`, `rationale`, `keywordLimit`（4～24，实现侧钳制） |
| `ingest.bodyStrategy` | string | `model_digest` \| `catalog_as_is` |
| `ingest.titleHint` | string | 可选，拼入 `sourceName` 后缀 |

**校验规则（实现）**

- `feasible=false` ⇒ 三路 `enabled` 解析为 false，不调用 `learn`。
- `feasible=true` 且三路均为 false ⇒ 返回可读消息，不写入。
- `persist=false` ⇒ 仅返回方案与解析字段，不调用 `learn`。

---

## 3. 执行与 `ActiveLearningService` 的衔接

- **sourceType**：`mysql_schema_sedimentation_plan`
- **triggerType**：`sedimentation_pipeline_model_digest` 或 `sedimentation_pipeline_catalog_as_is`
- **写入策略**：`ActiveLearningService#learnWithSinkPolicy`，由 `LearningSinkPolicy` 传入；被关闭的路返回 `SinkStatus.skip`。

提示词类名：`com.qa.demo.knowledge.SedimentationPlanPrompts`（与 `KnowledgeAssistantPrompts` 并列，避免单文件过长）。

---

## 4. 与既有能力的关系

| 能力 | 关系 |
|------|------|
| `POST /qa/mysql/schema-catalog` + `assess` | 叙述性评估 + 固定三路 `learn`；**无**结构化 JSON 方案 |
| `POST /qa/mysql/connect` | 动态库导出 + 可选叙述评估 + 固定三路 `learn` |
| **本流水线** | 结构化方案 + 可选 digest + **按路开关** 的 `learn` |

---

## 5. 配置与上限

复用 `qa.assistant.max-schema-assessment-catalog-chars`、`max-schema-assessment-response-chars`（方案 JSON 长度上限为其倍数）、`max-schema-export-chars`（digest 与 catalog_as_is 截断上限）。详见 `SchemaSedimentationPlanService` 实现。
