# scripts/eval/ — QA 评估门禁

> **P0 门禁(Step 1)**:为后续 7 步拨乱反正提供度量衡。
> **设计预期**:当前 q01/q02 等 5 条 case 已知 500,首次跑会触发 fail-under → 退出码 3。
> 后续步骤每修好一批就 re-run,只准涨不准跌。

---

## 文件

| 文件 | 作用 |
|---|---|
| `run_qa_eval.py` | Python 评估器,跑 `data/eval/qa_cases.jsonl` 全量/子集,出 CSV |
| `verify_qa_eval.ps1` | PowerShell 包装:启动应用 → 跑 eval → 关停 → 透传退出码 |
| `README.md` | 本文件 |

## 配套

| 文件 | 作用 |
|---|---|
| `data/eval/qa_cases.jsonl` | 26 条回归用例(Q-01/Q-02/阈值筛选/type_catalog/routing/followup) |
| `data/eval/qa_eval_results.csv` | 本次跑的结果,字段后向兼容 |
| `data/eval/qa_eval_baseline.csv` | 首次跑自动落,后续不覆盖(防止误操作) |
| `data/qa_logs/gate_metrics.jsonl` | 每次 ask 落 1 行;Step 1 增了 `error500` / `errorMessage` 字段 |
| `data/qa_logs/spring_run.log` | mvn 启动日志(verify_qa_eval.ps1 重定向) |

---

## 用法

### 一键跑全量(本地开发)

```powershell
# 在项目根目录
pwsh ./scripts/eval/verify_qa_eval.ps1
```

默认:`--fail-under 80`、无 `--must-pass`、无 `--baseline`。

### 自定义门禁

```powershell
# 必须 pass Q-01 / Q-02,触发更高阈值
pwsh ./scripts/eval/verify_qa_eval.ps1 `
    -FailUnder 90 `
    -MustPass "q01_person_role_list,q01_person_role_sql_fallback,person_role_legal_rep,q02_followup_cert,followup_cert_after_role"
```

### 与 baseline 比对(开发迭代)

```powershell
# 假设 baseline 已固化;新跑一次比对 regressed
pwsh ./scripts/eval/verify_qa_eval.ps1 -Baseline "data/eval/qa_eval_baseline.csv"
```

### 应用已起(开发调试)

```powershell
# 不重启应用,直接跑 eval
pwsh ./scripts/eval/verify_qa_eval.ps1 -SkipStart -FailUnder 100
```

### 直接调 Python(高级)

```bash
# 单跑 eval,不启动应用
python scripts/eval/run_qa_eval.py --base-url http://localhost:8080 --fail-under 80
```

---

## 退出码

| 退出码 | 含义 | 触发条件 |
|---|---|---|
| **0** | 全部 pass + 满足 fail-under | `passed == total` 且 `rate ≥ fail-under` |
| **2** | 部分失败 | `passed < total` 但 `rate ≥ fail-under` 且无 regressed |
| **3** | **门禁触发** | `rate < fail-under`(Step 1 预期红) |
| **4** | must-pass 失败 | `--must-pass` 命中的 case 有 fail |
| **5** | 启动失败 | Maven 没起来 / 端口被占 / Python 缺失 |

**优先级**:`4` > `3` > `2` > `0`

---

## CSV 字段

| 字段 | 说明 |
|---|---|
| `id` | case id(对应 qa_cases.jsonl) |
| `question` | 问句 |
| `pass` | True/False |
| `failures` | 失败原因(`http_500: <message>` / `queryType: want=X got=Y` 等) |
| `route` | 路由类型(`unified_constrained_rerank_dashscope` / `http_500` / ...) |
| `retrievalSource` | 检索源 |
| `queryType` | LLM/规则推断的 queryType |
| `canAnswer` | 闸门是否能答 |
| `evidenceCount` | 证据条数 |
| `errorMessage` | **Step 1 新增** 500 时填异常 message |
| `regressed` | **Step 1 新增** 与 baseline 比对,True/False/空 |
| `improved` | **Step 1 新增** 与 baseline 比对,True/False/空 |

---

## 验证标准(Step 1 完成的判据)

> Step 1 提交后**门禁预期红**(67% < 80%)。验证的是"门禁真能红",不是"门禁绿了"。

| # | 验证项 | 怎么验 |
|---|---|---|
| 1 | 脚本能跑完整流程 | `pwsh ./scripts/eval/verify_qa_eval.ps1` → 退出码 3(预期) |
| 2 | baseline 已固化 | `data/eval/qa_eval_baseline.csv` 存在,行数 = 26 |
| 3 | 5 条 500 都有 errorMessage | `qa_eval_results.csv` 中 q01/q02/person_role/followup_cert_after_role 的 `failures` 列含真实异常 message(非空) |
| 4 | 500 写进 gate_metrics | `gate_metrics.jsonl` 末尾有 ≥ 5 条 `error500=true` 且 `route=http_500` |
| 5 | fail-under 工作 | 传 `-FailUnder 90` → 退出码 3;传 `-FailUnder 50` → 退出码 2 |
| 6 | must-pass 工作 | 传 `-MustPass q01_person_role_list` → 退出码 4 |
| 7 | baseline diff 工作 | 改 `qa_eval_baseline.csv` 删 1 条 pass,重跑 → 退出码 2 + 输出 `regressed` 列表 |
| 8 | README 文档 | 本文件覆盖 5 个退出码 + 3 个参数 + 配套文件清单 |

---

## 后续步骤(待办)

- [ ] Step 2:修 q01/q02 500 bug(法人列表 + 多轮证照)
- [ ] Step 3:接 P5 上传文档入向量检索
- [ ] Step 4:把 P4 字段缺失追问改为 LLM 反思(删 EvidenceFieldCoverageAdvisor)
- [ ] Step 5:按 InformationNeed.granularity 分流检索
- [ ] Step 6:意图 LLM 异步并行
- [ ] Step 7:`queryType` 枚举化
- [ ] Step 8:业务硬编码清理

每完成一步,跑一次 `verify_qa_eval.ps1`,通过率必须**只准涨不准跌**。
