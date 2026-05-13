# Tasks: assistant MySQL / Neo4j 引导

- [x] 默认 `application.properties` 与 `QaAssistantProperties` 指向库与 schema `assistant`  
- [x] 新增 `data/sql/mysql/assistant_bootstrap.sql`（表、索引、示例数据）  
- [x] 新增 `data/neo4j/assistant_bootstrap.cypher`（约束、索引、示例子图）  
- [x] `MysqlContextService` / `SqlQueryService` 在 schema 枚举中跳过 `qa_` 系统表  
- [x] 更新 `openspec/specs/knowledge-assistant/spec.md`、`openspec/AGENTS.md`  
