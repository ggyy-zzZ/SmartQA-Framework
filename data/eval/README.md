# 意图路由 Eval 基线

用于重构前后对比的**黄金用例**与 trace 字段约定，不依赖自动化跑分脚本即可手工对照 Playground / `ask_events.jsonl`。

## 文件

| 文件 | 说明 |
|------|------|
| `intent-routing-cases.jsonl` | 单轮/多轮意图与检索期望 |

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
