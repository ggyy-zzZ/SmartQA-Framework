# MySQL 元数据目录 → 主动学习：设计与实现说明

本文档固化「根据**已配置**的 MySQL 连接，只读整理 schema，并**可选**一键写入主动学习」的设计边界，便于后续迭代与评审。实现入口见 `POST /qa/mysql/schema-catalog`。

---

## 1. 目标与不做的事

**目标**

1. 使用应用配置中的 `qa.assistant.mysql-url` / `mysql-schema` / 账号（**不接受请求体中的任意 JDBC**），只读查询 `information_schema`。
2. 生成一篇《数据库结构说明》Markdown（表清单、逐表列、类型、可空、键摘要；含只读声明与表数上限说明）。
3. 当调用方指定 `persist=true` 时，将上述 Markdown 作为**纯文本知识**调用 `ActiveLearningService.learn`（与 `/qa/learn/text` 同源：MySQL `qa_active_knowledge` + 向量 + 图谱）。

**明确不做**
  
- 不执行用户任意 SQL；不批量 `SELECT *` 拉业务行；不自动 `LOAD DATA` / DDL / DML 改业务表。
- 不将密码写入日志、响应体或 git 跟踪的配置文件。

---

## 2. 「自动写入主动学习」的实现要点

### 2.1 写入内容

- **持久化载荷**：`persist=true` 时写入 `ActiveLearningService` 的字符串为 **`combinedMarkdown`**：无评估时等于目录 `markdown`；`assess=true` 且模型成功时，为「目录 + 评估章节」拼接结果；评估失败时降级为仅目录。
- **`learn` 参数约定**（与代码一致）：
  - `sourceType`：`mysql_schema_catalog`（仅目录或评估失败降级）；`mysql_schema_catalog_assessed`（评估成功且已拼入正文）。
  - `sourceName`：`schema-<schema名>`（不含密码）
  - `triggerType`：`schema_catalog_api` / `schema_catalog_api_assessed`（与 sourceType 对应）
  - `scope`：请求体传入，经 `QaScopes.normalize`

### 2.2 与评估步骤的衔接

| 阶段 | 说明 |
|------|------|
| Phase 1 | 只读导出 Markdown（`markdown` 字段）；`persist=true` 时默认仅持久化目录正文。 |
| Phase 2（已实现） | 请求体 `assess=true`：`MysqlSchemaCatalogAssessmentService` 调用 `MiniMaxClient.completeChat`，将评估拼入 `combinedMarkdown`；`persist=true` 时对 **combinedMarkdown** 调用 `learn`。评估失败时降级为仅目录，`sourceType` 仍为 `mysql_schema_catalog`。成功拼接时 `sourceType` 为 `mysql_schema_catalog_assessed`。 |
| Skill | 仍可用于**任意 JDBC**（会话内）、更自由的表范围与人工复核流程，与「仅配置库」HTTP 能力互补。 |

### 2.3 上限与截断

- **表数量**：`qa.assistant.max-schema-export-tables`（默认 15），按表名字母序取前 N 张**非** `qa_` 前缀的 `BASE TABLE`。
- **文档长度**：`qa.assistant.max-schema-export-chars`（默认 250000 字符），超出则对 Markdown **硬截断**并标记 `markdownTruncated=true`（避免 OOM 与向量请求过大）。
- **模型上下文**：`qa.assistant.max-schema-assessment-catalog-chars`（默认 24000）控制送入评估模型的目录最大长度；`max-schema-assessment-response-chars`（默认 6000）控制评估输出截断。

### 2.4 幂等与重复写入

当前**不**做去重：同一 schema 多次 `persist=true` 会产生多条主动学习记录。若需幂等，后续可对「规范化 Markdown 的 hash」扩展 `sourceName` 或在 `qa_active_knowledge` 侧加业务键（另起 backlog）。

---

## 3. HTTP 约定

- **路径**：`POST /qa/mysql/schema-catalog`
- **Content-Type**：`application/json`
- **请求体**（均可选）：
  - `persist`：`boolean`，默认 `false`。为 `true` 时对 `combinedMarkdown` 调用 `learn`。
  - `assess`：`boolean`，默认 `false`。为 `true` 时调用大模型生成评估（消耗 API）。
  - `scope`：`enterprise` | `personal`，默认 `enterprise`。
- **响应**（概要）：
  - `ok` / `mysqlEnabled`
  - `schema`、`tableCount`、`markdown`（纯目录）、`markdownTruncated`、`markdownCharCount`
  - `assess`、`combinedMarkdown`、`combinedMarkdownCharCount`
  - 若 `assess=true`：`modelAssessment`（成功时）、`assessmentFailed`、`assessmentError`（可选）、`catalogTruncatedForModel`
  - `persist`：是否已请求持久化
  - 若 `persist=true` 且已调用学习：与 `LearningResponseBuilder` 一致的字段（如 `knowledgeId`、`sinkStatus` 等），以及 `schemaCatalogIngest`（含 `assessRequested`、`assessmentFailed`、`sourceType`）。

若 `mysqlEnabled=false` 或导出失败，返回 `ok: false` 与可读 `message`。

---

## 4. 与 Skill、现有模块的关系

| 组件 | 关系 |
|------|------|
| `MysqlSchemaCatalogService` | 只读元数据 → Markdown。 |
| `MysqlSchemaCatalogAssessmentService` | `assess=true` 时调模型 → 评估文本；与目录拼接为 `combinedMarkdown`。 |
| `MiniMaxClient.completeChat` | 非流式 system+user，供评估等复用。 |
| `ActiveLearningService` | 持久化三通路。 |
| `StructuredTableRowAuditService` | 对**已有表**做行数审计；与「元数据文档学习」互补，不替代。 |
| Skill `mysql-schema-knowledge-assessment` | **任意 JDBC**（会话内）+ 人工/Agent 驱动的大模型评估流程；与本 API 的「仅配置库」策略分离。 |

---

## 5. 安全清单（实现自检）

- [ ] 仅使用 `QaAssistantProperties` 中的连接，不解析请求中的 JDBC。
- [ ] 仅查询 `information_schema`，不对业务表做全表扫描。
- [ ] 跳过 `qa_` 系统表，与扫表/摘要策略一致。
- [ ] 表名、列名经 `[A-Za-z0-9_]+` 校验，防注入拼接。
- [ ] 响应中不包含数据库密码。

---

## 6. 变更记录

- 2026-05-12：初版（Phase 1：导出 + 可选 `learn`）落地；设计文档与实现对齐。
- 2026-05-12：Phase 2：`assess` + `MiniMaxClient.completeChat` + `combinedMarkdown` 与 `learn` 串联；配置 `max-schema-assessment-catalog-chars` / `max-schema-assessment-response-chars`。
