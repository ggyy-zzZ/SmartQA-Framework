# Proposal: MySQL 库 `assistant` 与图谱引导脚本

## 背景

本地已新建 MySQL schema（库）`assistant`，希望应用默认对齐该库，并提供可重复的表结构、索引与 Neo4j 侧约束/示例子图，避免与历史 `tdcomp` 等环境硬编码耦合。

## 目标

1. 默认配置 `qa.assistant.mysql-url` / `qa.assistant.mysql-schema` 指向 `assistant`。  
2. 提供 `data/sql/mysql/assistant_bootstrap.sql`：`qa_active_knowledge`、`employee`、`company` 及示例数据，满足 `SqlQueryService` 固定表名与主动学习落库。  
3. 提供 `data/neo4j/assistant_bootstrap.cypher`：与 `GraphContextService` / `ActiveLearningService` 一致的标签、关系及约束/索引。  
4. 行级扫表与 SQL schema 摘要跳过 `qa_` 前缀系统表，与规格一致。  
5. 将上述约定写入 `openspec/specs/knowledge-assistant/spec.md` 与 `openspec/AGENTS.md`。

## 非目标

- 不迁移历史 `tdcomp` 数据；需要时由运维自行导出导入。  
- 不修改 Neo4j 查询 Cypher 的业务语义（仅补充初始化资产）。
