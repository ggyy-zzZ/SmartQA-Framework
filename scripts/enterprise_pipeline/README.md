# Enterprise Pipeline

将 MySQL（`tdcomp`）结构化数据编排为知识文档，并同步到 Neo4j 图谱 + Qdrant 向量库。

## 1) 安装依赖

```bash
python -m venv .venv
source .venv/Scripts/activate
pip install -r scripts/enterprise_pipeline/requirements.txt
```

## 2) 从 MySQL 编排知识文档

```bash
python scripts/enterprise_pipeline/build_knowledge_from_mysql.py \
  --host localhost \
  --port 3306 \
  --username root \
  --password root \
  --schema tdcomp \
  --output-dir data/knowledge
```

输出文件：

- `data/knowledge/enterprise_mysql_clean.jsonl`
- `data/knowledge/enterprise_mysql_compiled.txt`
- `data/knowledge/enterprise_mysql_stats.json`

### 学习范围（结构化）

从业务库导出并写入三库时，默认包含：

| 类别 | 来源表 | 写入内容 |
|------|--------|----------|
| 主体公司 | `company` | 基本信息、地址、经营范围等 |
| 关键人员 | `company` 角色列 + 董监高 | 法人、监事、财务、证照/印章角色等 |
| 资质证照 | `certificate_management` | 类型、状态、有效期、证号（若表中有）、监管/保管/执行人 |
| 印章 | `seal_management` + `seal_person_detail` | 印章类型、保管部门、用印相关人员 |
| 股东/产品线/银行账户 | 对应业务表 | 与主体关联的结构化摘要 |

编译文本中含 **「证照信息」「印章信息」「关键人员」** 段落；向量文档含证照/印章字段；Neo4j 含 `Certificate`、`Seal` 节点及 `HAS_CERTIFICATE`、`HAS_SEAL` 关系。

> 证照扫描件表 `certificate_attachment` 默认不导出（仅元数据学习）。全量同步后需 **重建 Neo4j + Qdrant** 才能生效。

## 3) 同步知识图谱

### 本地 Neo4j 免鉴权模式

```bash
python scripts/enterprise_pipeline/sync_neo4j.py \
  --input data/knowledge/enterprise_mysql_clean.jsonl \
  --uri bolt://localhost:7687 \
  --wipe
```

### Neo4j 开启鉴权模式

```bash
python scripts/enterprise_pipeline/sync_neo4j.py \
  --input data/knowledge/enterprise_mysql_clean.jsonl \
  --uri bolt://localhost:7687 \
  --username neo4j \
  --password "your-password" \
  --wipe
```

## 4) 一键跑全流程

```bash
python scripts/enterprise_pipeline/run_pipeline.py \
  --output-dir data/knowledge \
  --mysql-host localhost \
  --mysql-port 3306 \
  --mysql-username root \
  --mysql-password root \
  --mysql-schema tdcomp \
  --uri bolt://localhost:7687 \
  --wipe
```

## 5) 同步向量库（Qdrant）

请先确保 Qdrant 已启动（默认 `http://localhost:6333`）。

```bash
# 推荐：百炼 text-embedding-v4（与 Java 应用默认一致，1024 维，集合 enterprise_knowledge_v2）
export DASHSCOPE_API_KEY="你的百炼密钥"
python scripts/enterprise_pipeline/sync_vectors_qdrant.py \
  --input data/knowledge/enterprise_mysql_clean.jsonl \
  --host localhost \
  --port 6333 \
  --collection enterprise_knowledge_v2 \
  --embedding-provider dashscope \
  --embedding-model text-embedding-v4 \
  --embedding-dim 1024 \
  --recreate
```

> `embedding-provider` 可选：
> - `dashscope`：**默认**，百炼 `text-embedding-v4`（需 `DASHSCOPE_API_KEY` 或 `--embedding-api-key`）
> - `hash`：本地伪向量，仅用于无 API 时联调
> - `minimax`：MiniMax 嵌入 API

查询验证（须与灌库时相同的 provider / dim / collection）：

```bash
python scripts/enterprise_pipeline/query_vectors_qdrant.py \
  --query "猎道信息技术有限公司经营状态" \
  --collection enterprise_knowledge_v2 \
  --embedding-provider dashscope \
  --top-k 5
```

## 6) 一键跑清洗+图谱+向量

```bash
python scripts/enterprise_pipeline/run_pipeline.py \
  --output-dir data/knowledge \
  --mysql-host localhost \
  --mysql-port 3306 \
  --mysql-username root \
  --mysql-password root \
  --mysql-schema tdcomp \
  --uri bolt://localhost:7687 \
  --wipe \
  --with-vector \
  --embedding-provider hash \
  --recreate-vector
```

## 7) 调试小样本

```bash
python scripts/enterprise_pipeline/run_pipeline.py --limit 100 --wipe --with-vector --recreate-vector
```
