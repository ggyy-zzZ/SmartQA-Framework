# SmartQA-Framework

基于 Spring Boot 的知识库问答助手系统，集成 MiniMax LLM、多源检索（向量数据库、图数据库、关系数据库）和主动学习能力。

## 环境要求

### 必需依赖

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21+ | 推荐使用 JDK 21（已在 CLAUDE.md 中配置） |
| Maven | 3.8+ | 项目构建工具 |

### 中间件服务

启动应用前需确保以下服务已启动：

| 服务 | 地址 | 默认端口 | 说明 |
|------|------|----------|------|
| Qdrant | localhost | 6333 | 向量数据库，用于语义检索 |
| Neo4j | localhost | 7687 | 图数据库，用于知识图谱检索 |
| MySQL | localhost | 3306 | 关系数据库，用于结构化数据检索 |

### 外部 API

| 服务 | 必需 | 说明 |
|------|------|------|
| MiniMax API | 是 | LLM 模型调用，需配置 API Key |

### 环境变量配置

```bash
# MiniMax API 密钥（必需）
export MINIMAX_API_KEY="your-api-key-here"

# MySQL 连接信息（可选，application.properties 有默认值）
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=root
```

或创建项目根目录 `application-local.properties`（已在 .gitignore）：

```properties
qa.assistant.api-key=your-api-key-here
```

## 快速启动

```bash
# 编译项目
./mvnw.cmd clean compile

# 运行应用
./mvnw.cmd spring-boot:run
```

访问 http://localhost:8080/qa-playground.html 打开问答界面。

## 项目结构

```
src/main/java/com/qa/demo/
├── qa/
│   ├── answer/          # LLM 调用（MiniMax）
│   ├── config/          # 配置属性类
│   ├── core/            # 核心模型（ContextChunk, IntentDecision, QaScopes）
│   ├── intent/          # 意图路由
│   ├── learning/        # 主动学习（CSV 接入、Schema 目录）
│   ├── orchestration/    # 问答编排器（QaAskOrchestrator）
│   ├── response/        # 会话管理与日志
│   ├── retrieval/       # 多源检索（Graph, Vector, MySQL, Document）
│   ├── sedimentation/   # 反馈沉淀队列
│   └── web/             # REST 控制器（QaController）
└── graph/               # Neo4j 图数据库配置
```

## 功能模块

### 1. 知识学习
- 粘贴文本学习（直接存入知识库）
- CSV 文件上传学习（表格数据）
- MySQL Schema 目录学习（表结构元数据）

### 2. 问答检索
- 语义检索（Qdrant 向量）
- 知识图谱检索（Neo4j）
- 结构化查询（MySQL）
- 混合检索模式

### 3. 导出功能
- 单表 CSV 导出
- 全量表 ZIP 导出（新增）

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/qa/ask` | POST | 同步问答 |
| `/qa/ask/stream` | GET/POST | 流式问答（SSE） |
| `/qa/learn/text` | POST | 文本学习 |
| `/qa/structured/csv-ingest` | POST | CSV 文件上传 |
| `/qa/mysql/schema-catalog` | POST | Schema 目录导出 |
| `/qa/mysql/table/export-csv` | POST | 单表 CSV 导出 |
| `/qa/mysql/tables/export-all-csv` | POST | 全量表 ZIP 导出 |
| `/qa/feedback` | POST | 反馈提交 |

## 数据库说明

### MySQL Schema

默认使用 `assistant` 库，包含以下系统表：
- `qa_active_knowledge` - 主动学习知识
- `qa_pending_knowledge` - 待处理知识
- `knowledge_chunks` - 知识块
- `conversation_contexts` - 会话上下文
- `qa_feedback` - 用户反馈
- `sedimentation_queue` - 沉淀队列
- `schema_catalog` - Schema 目录

初始化脚本位于：
- `src/main/resources/db/migration/assistant/V1__assistant_qa_extensions.sql`

### Neo4j

用于存储知识图谱关系，初始化脚本位于：
- `data/neo4j/assistant_bootstrap.cypher`

## 配置参考

完整配置项参考 `src/main/resources/application.properties`：

```properties
# LLM 配置
qa.assistant.model=MiniMax-M2.7
qa.assistant.api-url=https://api.minimaxi.com/v1/text/chatcompletion_v2
qa.assistant.api-key=${MINIMAX_API_KEY:}

# 向量检索配置
qa.assistant.vector-enabled=true
qa.assistant.qdrant-url=http://localhost:6333
qa.assistant.qdrant-collection=enterprise_knowledge_v1

# MySQL 配置
qa.assistant.mysql-enabled=true
qa.assistant.mysql-url=jdbc:mysql://localhost:3306/assistant
qa.assistant.mysql-username=root
qa.assistant.mysql-password=root
qa.assistant.mysql-schema=assistant

# Neo4j 配置
graph.neo4j.uri=bolt://localhost:7687
```