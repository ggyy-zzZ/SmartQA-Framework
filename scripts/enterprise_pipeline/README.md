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
