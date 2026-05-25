# 本地验证执行计划

## 阶段 0：清空三库（一次性）

**页面（推荐）**：打开 `http://localhost:8080/qa-playground.html` → **学习知识** → **企业三库学习（清空并重建）**。

**命令行**：

```bash
python scripts/ops/reset_local_stores.py --clear-logs
```

清空内容：

| 存储 | 动作 |
|------|------|
| Qdrant | 删除 `enterprise_knowledge_v1/v2`、`enterprise_active_learning_v1/v2` |
| Neo4j | 删除 Company/Person/股东/证照/产品线/银行/LearnedKnowledge 等节点 |
| MySQL `assistant` | TRUNCATE `qa_*`、`company`、`employee`，恢复 bootstrap 示例两行 |
| 日志 | 可选 `--clear-logs` 清空 `data/qa_logs/*.jsonl` |

## 阶段 1：重建知识（JSONL → 图 + 向量）

**页面**：playground「清空并重建三库」（`POST /qa/learn/knowledge-sync/rebuild`）。

```bash
export DASHSCOPE_API_KEY=你的密钥
python scripts/ops/rebuild_local_knowledge.py
# 或限量试跑：python scripts/ops/rebuild_local_knowledge.py --limit 100
```

等价于：reset → `sync_neo4j.py --wipe` → `sync_vectors_qdrant.py --recreate`（百炼 1024 维）。

若无 `enterprise_mysql_clean.jsonl`，先从业务库构建：

```bash
python scripts/enterprise_pipeline/build_knowledge_from_mysql.py --schema tdcomp
```

## 阶段 2：启动应用并自检

```bash
./mvnw.cmd spring-boot:run
curl http://localhost:8080/qa/assistant/runtime-summary
```

确认：`embeddingProviderActive=dashscope`，`qdrantCollection=enterprise_knowledge_v2`。

## 阶段 2.5：P0 统一召回 + 重排（已接入）

企业 scope 默认开启（`qa.assistant.unified-retrieval-enabled=true`）：

1. 并行召回：Neo4j + Qdrant + MySQL + SQL + **主动学习（无阈值，始终合并）**
2. 百炼 `gte-rerank-v2` 重排后取 `retrieval-top-k` 条进 LLM（无 `DASHSCOPE_API_KEY` 时按 score 截断）

检查：`GET /qa/assistant/runtime-summary` 应含 `unifiedRetrievalEnabled`、`rerankProviderActive`。

## 阶段 3：离线评测基线

```bash
python scripts/eval/run_qa_eval.py
```

用例：`data/eval/qa_cases.jsonl`。结果 CSV 在 `data/eval/baseline_*.csv`。

## 阶段 4：按评测结果微调

| 旋钮 | 配置项 |
|------|--------|
| 拒答松紧 | `qa.assistant.answer-gate-min-top-score` |
| unknown 拦截 | `qa.assistant.answer-gate-block-on-unknown-intent` |
| 对齐拦截 | `qa.assistant.alignment-strict=true` |
| 向量 TopK | `qa.assistant.vector-top-k` |

改配置后重启应用，再跑 `run_qa_eval.py` 对比 CSV。

## 阶段 5（后续）

- 沉淀 `approve` API
- `retrievalDiagnostics` 响应字段
- Testcontainers 主链路集成测
