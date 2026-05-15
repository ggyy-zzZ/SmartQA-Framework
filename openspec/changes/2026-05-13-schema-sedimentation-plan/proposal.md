# Proposal: Schema 结构化沉淀方案流水线

## 动机

在仅依赖 `information_schema` 元数据的前提下，需要：

1. 模型输出**可解析**的可行性判断与沉淀方案（而非仅叙述性段落）；
2. 按方案决定是否写入 MySQL / Qdrant / Neo4j；
3. 支持「二次模型」将目录精炼为学习正文后再入库。

## 范围

- 新增 HTTP：`POST /qa/mysql/sedimentation/pipeline`（请求体 `source`、`persist`、`scope` 及动态连接字段）。
- 新增服务：`SchemaSedimentationPlanService`、`LearningSinkPolicy`，扩展 `ActiveLearningService#learnWithSinkPolicy`。
- 提示词：`SedimentationPlanPrompts`。
- 文档：`openspec/design/schema-sedimentation-plan-pipeline.md`、`specs/knowledge-assistant/spec.md` 增补 Scenario。

## 非目标

- 不开放任意 SQL/Cypher 执行；不扩展新的物理存储形态（仍为 `qa_active_knowledge`、既有 Qdrant collection、既有 Neo4j Learned 子图）。
