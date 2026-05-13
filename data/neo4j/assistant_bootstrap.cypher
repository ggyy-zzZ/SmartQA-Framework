// 与 GraphContextService / ActiveLearningService 对齐的约束与示例子图
// 用法：neo4j-admin database load 或 cypher-shell 连接后 SOURCE 本文件（按部署习惯执行）

// ---- 约束与索引（Neo4j 5+ 语法；旧版请改为等价 CREATE CONSTRAINT / INDEX）----
CREATE CONSTRAINT company_company_id_unique IF NOT EXISTS
FOR (c:Company) REQUIRE c.companyId IS UNIQUE;

CREATE CONSTRAINT learned_knowledge_id IF NOT EXISTS
FOR (d:LearnedKnowledge) REQUIRE d.knowledgeId IS UNIQUE;

CREATE CONSTRAINT learned_keyword_name IF NOT EXISTS
FOR (k:LearnedKeyword) REQUIRE k.name IS UNIQUE;

CREATE INDEX company_name_index IF NOT EXISTS
FOR (c:Company) ON (c.name);

// ---- 示例企业与关系（与 data/sql/mysql/assistant_bootstrap.sql 示例公司语义一致）----
MERGE (c:Company {companyId: '1'})
SET c.name = '示例科技有限公司',
    c.shortName = '示例科技',
    c.status = '存续',
    c.entityType = '有限责任公司',
    c.entityCategory = '科学研究与技术服务',
    c.registeredAddress = '上海市浦东新区示例路1号',
    c.businessScope = '技术开发、技术咨询、技术服务；软件销售。';

MERGE (p1:Person {personId: 'E1'})
SET p1.name = '张三';
MERGE (p1)-[:HAS_ROLE_IN {role: '法定代表人'}]->(c);

MERGE (p2:Person {personId: 'E2'})
SET p2.name = '李四';
MERGE (p2)-[:HAS_ROLE_IN {role: '经理'}]->(c);

MERGE (s:Shareholder {shareholderId: 'S1'})
SET s.name = '示例投资集团';
MERGE (s)-[:HOLDS_SHARES_IN {ratio: '60%'}]->(c);

MERGE (pl:ProductLine {lineId: 'PL1'})
SET pl.line = '企业知识助手产品线';
MERGE (c)-[:BELONGS_TO_PRODUCT {relation: '主营'}]->(pl);
