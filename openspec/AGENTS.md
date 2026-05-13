# AI 助手在本仓库中的约定

1. **文档导航**：项目级说明与索引见 `openspec/project.md`；**后续任务排队**见 `openspec/backlog.md`；能力规格与**代码模块划分**见 `openspec/specs/knowledge-assistant/spec.md`（章节「实现模块化边界」）。
2. **规格优先**：实现或修改问答、学习、路由、提示词前，先阅读 `openspec/specs/` 下对应 `spec.md`，变更较大时在 `openspec/changes/<change-id>/` 补充 proposal/tasks。
3. **提示词**：用户可见说明与 LLM system 内容应来自 `KnowledgeAssistantPrompts` 或 `qa.assistant.*` 配置，避免在业务类中散落写死某一商业系统名称。
4. **数据边界**：结构化数据接入默认假设 **小于 10000 行** 量级；更大规模需在 spec 中单独定义分页与索引策略后再实现。`POST /qa/structured/row-audit` 与 `POST /qa/structured/csv-ingest` 与 `qa.assistant.max-structured-ingest-rows` 对齐；CSV 门禁不替代完整 ETL。**`POST /qa/structured/ingest-gate`** 与 **`/qa/structured/job/*`** 对表集合做同源行数门禁并写可选作业日志；**本应用不执行**向业务表的 DML/LOAD（见 `openspec/design/structured-ingest-gate.md`）。
5. **安全**：MySQL 仅允许受控的只读查询；不得在未规格化的情况下放开写权限。
6. **MySQL 库 `assistant`**：本地/默认部署将 `qa.assistant.mysql-url` 指向库 `assistant`，`qa.assistant.mysql-schema=assistant`。初始化 DDL 见 `data/sql/mysql/assistant_bootstrap.sql`；行级扫表与 SQL 生成摘要会跳过 `qa_` 前缀系统表（如 `qa_active_knowledge`），该类表仅由主动学习等专用代码访问。
7. **Neo4j**：与图谱检索、主动学习写入相关的约束与示例子图见 `data/neo4j/assistant_bootstrap.cypher`（按需执行，与 MySQL 示例数据无强一致主键绑定，语义对齐即可）。本地建议 **Neo4j 5.x**（Community 即可），驱动为官方 `neo4j-java-driver`；若使用 4.x，需自行核对 Cypher 语法差异。
8. **Flyway（可选）**与 **`assistant_bootstrap.sql`（手工/一次性）**的分工：
   - **bootstrap**：基线 DDL（含 `qa_active_knowledge`、`company`/`employee` 示例、系统表等）与**示例数据**；新环境空库时优先执行。
   - **Flyway**（`qa.assistant.flyway-enabled=true`）：`classpath:db/migration/assistant` 下**增量**脚本；当前版本主要覆盖与待沉淀/反馈等**扩展表**对齐的 DDL（与 bootstrap 中对应 `CREATE IF NOT EXISTS` 可并存，避免重复执行冲突）。
   - **`qa_active_knowledge`** 等核心表：以 bootstrap 为权威定义；Flyway 脚本**当前不替代**整张基线表的演进（若未来需版本化迁移，应新增 Flyway 版本并在此条中更新说明）。
9. **MySQL 元数据目录**：`POST /qa/mysql/schema-catalog` 只读导出 `information_schema` 为 Markdown；可选 `assess=true`（调大模型写沉淀评估）、`persist=true`（对 `combinedMarkdown` 写入主动学习）；**不接受**请求中的 JDBC。约定见 `openspec/design/mysql-schema-active-learning-pipeline.md`。