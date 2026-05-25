# SmartQA-Framework 系统架构文档

## 1. 项目概述

**项目名称：** SmartQA-Framework
**技术栈：** Spring Boot 3.5.13, Java 21
**定位：** 企业知识库问答助手系统，从用户提供文档和结构化数据中学习，通过多种检索方式生成答案

### 1.1 核心能力

- **多源检索**：支持向量检索（Qdrant）、图检索（Neo4j）、结构化检索（MySQL）、文档检索
- **意图路由**：智能判断问题类型（图谱/向量/结构化/文档），选择最优检索路径
- **主动学习**：从对话、文本、CSV文件中学习知识，持久化到MySQL/Qdrant/Neo4j
- **多专家协作学习**：数据分析专家 + 系统架构师 + 学习策略专家共同决策
- **增量同步**：追踪数据源变化，自动增量更新已学习内容

---

## 2. 系统架构

### 2.1 模块结构

```
src/main/java/com/qa/demo/qa/
├── core/                    # 核心模型
│   ├── ContextChunk         # 检索结果片段
│   ├── IntentDecision       # 意图路由决策
│   ├── QaScopes             # 知识库作用域
│   └── CompanyCandidate     # 公司候选
├── intent/                  # 意图路由
│   ├── IntentRouterService  # LLM + 规则双层意图判断
│   └── CompanyClarificationAdvisor  # 公司名消歧
├── retrieval/               # 多源检索
│   ├── QaRetrievalPipeline  # 检索管道编排
│   ├── GraphContextService  # Neo4j 图检索
│   ├── VectorContextService # Qdrant 向量检索
│   ├── MysqlContextService # MySQL 结构化检索
│   ├── SqlQueryService     # SQL 聚合查询
│   ├── DocumentContextService # 文档检索
│   ├── EntityTableMapper   # 实体-表映射
│   └── EmployeeBaseKnowledgeService # 员工基础信息
├── learning/                # 主动学习
│   ├── ActiveLearningService # 核心学习服务
│   ├── MultiExpertLearningService # 多专家协作学习
│   ├── SyncTrackingService  # 同步状态追踪
│   ├── ScheduledSyncService # 定时增量同步
│   ├── MysqlSchemaCatalogService # Schema 目录导出
│   ├── MysqlSchemaCatalogAssessmentService # Schema 评估
│   ├── BatchCsvAnalysisService # CSV 批量分析
│   ├── BatchLearningOrchestrator # 批量学习编排
│   ├── StructuredCsvIngestService # CSV 接入
│   ├── StructuredIngestJobService # 结构化接入任务
│   ├── StructuredTableRowAuditService # 行数审计
│   └── LearningResponseBuilder # 学习响应构建
├── answer/                  # 答案生成
│   ├── MiniMaxClient        # MiniMax LLM 客户端
│   └── QaAnswerFallbackService # 兜底答案服务
├── orchestration/           # 编排层
│   ├── QaAskOrchestrator    # 问答应流程编排
│   └── QaSseStreamSupport   # SSE 流式输出支持
├── response/                # 对话管理
│   ├── QaConversationService # 会话管理
│   └── QaLogService         # 问答日志
├── sedimentation/          # 知识沉淀
│   ├── SedimentationQueueService # 待沉淀队列
│   └── FeedbackPersistenceService # 用户反馈持久化
├── alignment/              # 证据对齐
│   └── EvidenceAlignmentService
├── web/                    # HTTP 入口
│   └── QaController        # REST 控制器
└── config/                 # 配置
    ├── QaAssistantProperties # 应用配置
    └── CorsConfig          # CORS 配置

com.qa.demo.graph/           # Neo4j 图数据库配置
com.qa.demo.knowledge/       # Prompt 模板
```

---

## 3. 核心组件详解

### 3.1 QaAskOrchestrator（问答应流程编排）

**文件：** `src/main/java/com/qa/demo/qa/orchestration/QaAskOrchestrator.java`

这是系统的核心编排器，协调整个问答流程：

```
用户问题 → 意图路由 → 多源检索 → 证据合并 → LLM 生成 → 返回答案
```

**主要方法：**
- `buildAskResponse(question, scope, conversationId, followUp)` - 同步问答
- `startAskStream(question, scope, conversationId, followUp)` - SSE 流式问答

**处理流程：**
1. 解析并验证问题
2. 检测学习命令（"记住..."）
3. 检索主动学习记忆（短问题优先）
4. 公司消歧（如需要）
5. 意图路由（graph/vector/mysql/sql/hybrid/unknown）
6. 多源检索
7. 证据合并
8. 调用 MiniMax LLM 生成答案
9. 兜底答案（LLM 失败时）
10. 知识沉淀（未知意图或证据不足时）

### 3.2 IntentRouterService（意图路由）

**文件：** `src/main/java/com/qa/demo/qa/intent/IntentRouterService.java`

**支持的意图类型：**

| 意图 | 说明 | 触发关键词 |
|------|------|-----------|
| graph | 图谱关系查询 | 股东、股权、穿透、关系、关联、总公司、分公司 |
| vector | 语义相似检索 | - |
| mysql | 结构化数据查询 | 表、字段、结构化、明细、记录 |
| sql | SQL 聚合分析 | 多少、总计、数量、统计、占比、分组、Top |
| document | 文档检索 | 流程、规定、制度、政策、步骤、怎么办 |
| hybrid | 混合检索 | - |
| unknown | 未知 | - |

**路由策略：**
1. **主策略**：调用 LLM 进行分类
2. **兜底策略**：基于关键词规则匹配

### 3.3 QaRetrievalPipeline（多源检索管道）

**文件：** `src/main/java/com/qa/demo/qa/retrieval/QaRetrievalPipeline.java`

**检索顺序（按意图）：**

| 意图 | 主检索 | 降级检索链 |
|------|--------|-----------|
| graph | GraphContextService | → Vector → MySQL → SQL → Document |
| vector | VectorContextService | → Graph → MySQL → SQL → Document |
| document | DocumentContextService | → Graph → Vector → MySQL → SQL |
| mysql | MysqlContextService | → Graph → Vector → SQL → Document |
| sql | SqlQueryService | → MySQL → Graph → Vector → Document |
| hybrid | 全部合并 | - |

**特殊处理：**
- **Supplemental Tables**：追加实体相关表（如员工表）
- **Employee Base**：追加员工基础信息

---

## 4. 学习系统

### 4.1 ActiveLearningService（主动学习核心）

**文件：** `src/main/java/com/qa/demo/qa/learning/ActiveLearningService.java`

**学习内容：**
- 纯文本学习
- Markdown 文件上传
- CSV 结构化数据
- Schema 目录

**三通道持久化：**

| 通道 | 存储 | 查询方式 |
|------|------|----------|
| MySQL | `qa_active_knowledge` 表 | 关键词 LIKE 匹配 |
| Qdrant | 向量数据库 | 语义相似度检索 |
| Neo4j | 图数据库 | 实体关系推理 |

**学习方法：**
```java
learn(rawContent, sourceType, sourceName, triggerType, scope)
learnWithSinkPolicy(...)  // 可指定沉淀策略
```

### 4.2 MultiExpertLearningService（多专家协作学习）

**文件：** `src/main/java/com/qa/demo/qa/learning/MultiExpertLearningService.java`

模拟三个专家协作完成数据库学习：

```
┌─────────────────────────────────────────────────────────┐
│  1. DataAnalystExpert（数据分析专家）                    │
│     - 分析表结构（列名、类型、约束）                      │
│     - 推断业务含义（基于列名模式）                        │
│     - 检测表间关联关系                                   │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  2. SystemArchitectExpert（系统架构师）                  │
│     - 评估是否需要向量存储（文本列、描述性字段）           │
│     - 评估是否需要图谱（外键关联、多对多关系）            │
│     - 决定沉淀策略（MySQL Only / MySQL+Vector / ALL）    │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  3. LearningPlannerExpert（学习策略专家）                │
│     - 综合分析结果                                       │
│     - 生成具体执行方案                                   │
│     - 估算学习行数                                       │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│  4. 执行学习                                            │
│     - 按方案执行多通道持久化                             │
│     - 记录同步状态                                       │
└─────────────────────────────────────────────────────────┘
```

### 4.3 增量同步（ScheduledSyncService + SyncTrackingService）

**文件：**
- `src/main/java/com/qa/demo/qa/learning/ScheduledSyncService.java`
- `src/main/java/com/qa/demo/qa/learning/SyncTrackingService.java`

**同步追踪表（sync_tracking）：**

| 字段 | 说明 |
|------|------|
| source_host | 数据源主机 |
| source_port | 数据源端口 |
| source_db | 数据库名 |
| table_name | 表名 |
| last_sync_time | 最后同步时间 |
| last_row_count | 最后同步时的行数 |

**增量同步流程：**

```
定时任务（30分钟） → 获取所有已学习数据源 → 检测行数变化
                                              ↓
                              有变化 → 触发增量学习 → 更新记录
                              无变化 → 跳过
```

---

## 5. 外部服务集成

### 5.1 Neo4j（图数据库）

**用途：** 企业股东关系、股权穿透、管理层关系

**查询示例：**
- `MATCH (c:Company)-[:SHAREHOLDER]->(s) WHERE ...` - 股东查询
- `MATCH (c:Company)-[:LEGAL_REP]->(e)` - 法人查询

**配置：** `graph.neo4j.uri`, `graph.neo4j.username`, `graph.neo4j.password`

### 5.2 Qdrant（向量数据库）

**用途：** 语义相似度检索

**向量化：** 百炼 DashScope `text-embedding-v4`（1024 维，见 `TextEmbeddingService`）；写入与检索须同一模型。灌库：`scripts/enterprise_pipeline/sync_vectors_qdrant.py` 或 `scripts/ops/rebuild_local_knowledge.py`。

**配置：** `qa.assistant.qdrant-url`, `qa.assistant.qdrant-collection`（默认 `enterprise_knowledge_v2`），主动学习集合 `qa.assistant.qdrant-active-learning-collection`

**本地清空：** `python scripts/ops/reset_local_stores.py`

### 5.3 MySQL

**用途：**
- 系统表存储（`qa_active_knowledge`, `qa_pending_knowledge` 等）
- 业务数据检索
- Schema 元数据

**配置：**
- `qa.assistant.mysql-url` - 系统库
- `qa.assistant.business-mysql-url` - 业务库（如 tdcomp）

### 5.4 MiniMax LLM

**用途：** 意图分类、答案生成、Schema 评估

**配置：** `qa.assistant.api-url`, `qa.assistant.api-key`, `qa.assistant.model`

---

## 6. REST API 端点

### 6.1 问答

| 端点 | 方法 | 说明 |
|------|------|------|
| `/qa/ask` | POST | 同步问答 |
| `/qa/ask/stream` | POST/GET | SSE 流式问答 |

### 6.2 学习

| 端点 | 方法 | 说明 |
|------|------|------|
| `/qa/learn/upload` | POST | Markdown 文件上传学习 |
| `/qa/learn/text` | POST | 纯文本学习 |
| `/qa/learning/multi-expert` | POST | 多专家协作学习数据库 |

### 6.3 结构化数据

| 端点 | 方法 | 说明 |
|------|------|------|
| `/qa/structured/csv-ingest` | POST | CSV 文件接入 |
| `/qa/structured/csv-batch-analyze` | POST | 批量 CSV 分析 |
| `/qa/structured/csv-batch-learn-auto` | POST | 一键批量学习 |
| `/qa/structured/row-audit` | POST | 表行数审计 |
| `/qa/structured/ingest-gate` | POST | 接入门禁检查 |

### 6.4 Schema

| 端点 | 方法 | 说明 |
|------|------|------|
| `/qa/mysql/schema-catalog` | POST | 导出 Schema 目录（Markdown） |

### 6.5 知识管理

| 端点 | 方法 | 说明 |
|------|------|------|
| `/qa/sedimentation/pending` | GET | 待沉淀队列 |
| `/qa/feedback` | POST | 用户反馈 |
| `/qa/history` | GET | 问答历史 |

### 6.6 运维

| 端点 | 方法 | 说明 |
|------|------|------|
| `/qa/assistant/runtime-summary` | GET | 配置检查 |

---

## 7. 数据库 Schema

### 7.1 系统表（assistant 库）

**qa_active_knowledge** - 主动学习知识库

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| knowledge_id | VARCHAR(64) | 知识唯一ID |
| title | VARCHAR(255) | 标题 |
| content | LONGTEXT | 内容 |
| source_type | VARCHAR(64) | 来源类型 |
| source_name | VARCHAR(255) | 来源名称 |
| trigger_type | VARCHAR(64) | 触发类型 |
| scope | VARCHAR(32) | 作用域 |
| created_at | TIMESTAMP | 创建时间 |

**qa_pending_knowledge** - 待沉淀队列

**qa_user_feedback** - 用户反馈

**sync_tracking** - 同步状态追踪

### 7.2 业务表示例

**employee** - 员工表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INT | 主键 |
| name | VARCHAR(64) | 姓名 |
| department | VARCHAR(128) | 部门 |
| email | VARCHAR(128) | 邮箱 |

**company** - 公司表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INT | 主键 |
| company_name | VARCHAR(255) | 公司名 |
| status | VARCHAR(32) | 状态 |
| entity_type | VARCHAR(64) | 企业类型 |
| legal_rep_id | INT | 法人代表ID |
| manager_id | INT | 总经理ID |

---

## 8. 配置说明

### 8.1 核心配置（application.properties）

```properties
# 应用
spring.application.name=demo
server.port=8080

# MiniMax LLM
qa.assistant.api-url=https://api.minimaxi.com/v1/text/chatcompletion_v2
qa.assistant.api-key=${MINIMAX_API_KEY:}
qa.assistant.model=MiniMax-M2.7

# 向量检索
qa.assistant.vector-enabled=true
qa.assistant.qdrant-url=http://localhost:6333
qa.assistant.qdrant-collection=enterprise_knowledge_v1

# MySQL
qa.assistant.mysql-enabled=true
qa.assistant.mysql-url=jdbc:mysql://localhost:3306/assistant
qa.assistant.mysql-schema=assistant

# 业务库
qa.assistant.business-mysql-url=jdbc:mysql://localhost:3306/tdcomp

# 增量同步
qa.assistant.enableScheduledSync=true

# Neo4j
graph.neo4j.uri=bolt://localhost:7687
```

### 8.2 环境变量

| 变量 | 说明 |
|------|------|
| MINIMAX_API_KEY | MiniMax API 密钥 |
| MYSQL_USERNAME | MySQL 用户名 |
| MYSQL_PASSWORD | MySQL 密码 |

---

## 9. 前端页面

**文件：** `src/main/resources/static/qa-playground.html`

### 9.1 功能区域

1. **学习知识**
   - 粘贴文本学习
   - 多专家智能学习（输入数据库连接信息）

2. **提问**
   - 问题输入框
   - 知识库选择（企业/个人）

3. **回答**
   - 思考过程展示
   - 答案展示（Markdown 渲染）

---

## 10. 部署架构

```
┌─────────────────────────────────────────────────────────┐
│                    客户端（浏览器）                       │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│              Spring Boot (port 8080)                     │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │
│  │   Web层     │  │  业务逻辑层  │  │   数据层     │     │
│  │ QaController│  │Orchestrator │  │   MySQL     │     │
│  └─────────────┘  └─────────────┘  └─────────────┘     │
│                            │                            │
│                    ┌───────┴───────┐                    │
│                    ▼               ▼                    │
│              ┌──────────┐   ┌──────────┐                │
│              │ Qdrant   │   │  Neo4j   │                │
│              │ (向量)    │   │  (图谱)   │                │
│              └──────────┘   └──────────┘                │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                    MiniMax LLM API                       │
└─────────────────────────────────────────────────────────┘
```

---

## 11. 常见问题排查

### 11.1 服务无法启动

```bash
# 检查端口占用
netstat -ano | findstr :8080

# 检查 MySQL 连接
mysql -u root -proot -e "SELECT 1"

# 检查 Neo4j
curl http://localhost:7474

# 检查 Qdrant
curl http://localhost:6333/collections
```

### 11.2 问答无响应

1. 检查 `MINIMAX_API_KEY` 环境变量
2. 访问 `/qa/assistant/runtime-summary` 检查配置
3. 查看应用日志

### 11.3 数据未学习

1. 检查 `qa_active_knowledge` 表是否有数据
2. 确认学习接口返回成功
3. 检查同步追踪记录 `sync_tracking`

---

## 12. 相关文档

- [openspec/AGENTS.md](../../openspec/AGENTS.md) - 开发规范
- [openspec/project.md](../../openspec/project.md) - 项目架构详情
- [openspec/specs/knowledge-assistant/spec.md](../../openspec/specs/knowledge-assistant/spec.md) - 技术规格
