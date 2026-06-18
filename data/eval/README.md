# 意图路由 Eval 基线

用于重构前后对比的**黄金用例**与 trace 字段约定，不依赖自动化跑分脚本即可手工对照 Playground / `ask_events.jsonl`。

## 文件

| 文件 | 说明 |
|------|------|
| `intent-routing-cases.jsonl` | 单轮/多轮意图与检索期望（早期子集） |
| `qa_cases.jsonl` | 阶段一完整回归集（§4 六案例 + Q-01/Q-02 + 路由用例） |

## 自动化回放

```bash
# 需本地服务已启动（JDK 21 打包后 java -jar，验证期常用 8091）
"/c/Program Files/Java/jdk-21/bin/java" -jar target/demo-0.0.1-SNAPSHOT.jar --server.port=8091

python scripts/eval/run_qa_eval.py --base-url http://localhost:8091
python scripts/eval/run_qa_eval.py --base-url http://localhost:8091 --tags section4
python scripts/eval/run_qa_eval.py --base-url http://localhost:8091 --out data/eval/qa_eval_results.csv
```

## 2026-06-15 基线（8091，新 jar）

| 范围 | 结果 | 说明 |
|------|------|------|
| `--tags section4` | **14/14（100%）** | §4 失败模式 + 阈值追问 + catalog 多轮 |
| 全量 25 case | 见 `qa_eval_results.csv` | Q-01（≥20 条法人）需 Neo4j；无图谱时走 SQL 降级（不 500） |

### P5 文档上传

```bash
curl -F "file=@data/eval/sample_upload.md" "http://localhost:8091/qa/documents/upload?corpusCode=user_uploads"
```

核心验证问句：

- 「公司经营状态包含哪些种类」→ `needGranularity=type_catalog`，`retrievalSource=unified_type_catalog`，证据 `catalog_v1`
- 「注册资金 100w 以上的公司有哪些」→ `route=clarify_field_gap_registered_capital`，`canAnswer=false`

## 每条用例字段

- `id`：用例编号
- `question`：用户原问（多轮见 `priorTurns`）
- `priorTurns`（可选）：上一轮 `question` / `answer` 摘要
- `expect`：期望 trace 子集
  - `queryType`：意图 queryType（过渡期仍记录）
  - `needFacet` / `needGranularity`：信息需求（新主路径）
  - `retrievalSourceContains`：检索来源应包含的子串
  - `canAnswer`：是否应能作答
  - `evidenceSchemas`：证据中至少应出现的 schema

## 对照方式

1. 在 Playground 提问，查看响应 `routing` 与 `retrievalSource`。
2. 或查 `data/qa_logs/ask_events.jsonl` 最新一条的同名字段。
3. 与 `expect` 比对；重构后 `needFacet`/`needGranularity` 优先于 `queryType`。

## 当前重点场景

- 证照**类型目录**（`type_catalog` + `catalog_v1`）
- 法人企业追问 → **实例证照**（`person_certificate_list` / `instance`）
- 任职列表（`person_role_list`）
