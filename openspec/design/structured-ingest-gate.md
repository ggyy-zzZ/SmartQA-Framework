# 结构化入库门禁与作业调度

与 `POST /qa/structured/row-audit`、**`POST /qa/structured/ingest-gate`**、`POST /qa/structured/job/*` 及可选 **`StructuredIngestScheduler`** 对齐。

## 清单 JSON（manifest）

路径由 **`qa.assistant.structured-ingest-manifest-path`** 指定（仅配置，不接受请求体传路径）。示例：

```json
{
  "jobName": "nightly_business_tables",
  "tables": ["company", "employee"]
}
```

## 门禁语义

- **`POST /qa/structured/ingest-gate`**：与 row-audit 同源计数，返回 **`allowedToProceed`**、`rejectionReason`（`mysql_disabled` | `no_tables` | `table_invalid_or_inaccessible` | `row_count_exceeds_limit`）、`writeToBusinessTablesAllowed: false`。
- **`POST /qa/structured/job/run`**：请求体带表清单，可选 **`logResult`**（默认 true）将一行 JSON 追加到 **`qa.assistant.structured-ingest-job-log-path`**；未配置时默认 **`{docsDir 父目录}/structured_ingest_job.log`**。
- **`POST /qa/structured/job/run-from-config`**：读取配置中的 manifest 路径并执行上述流程（便于与 Cron 对齐的手动触发）。

## 作业调度

- **`qa.assistant.structured-ingest-schedule-enabled=true`** 时注册 `StructuredIngestScheduler`，按 **`qa.assistant.structured-ingest-schedule-cron`**（默认 `0 0 2 * * ?`）调用 `runConfiguredManifestWithAppendLog`。
- manifest 路径为空或文件不可读时跳过或打日志，不抛致命错误。

## 业务表「自动加载入库」

本仓库**不**实现向业务表写入 DML；外部 ETL 应在 **`allowedToProceed=true`** 后再执行导入。API 响应中的说明字段明确此边界。
