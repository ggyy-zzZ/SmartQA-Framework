package com.qa.demo.qa.web;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.QaScopes;
import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.learning.LearningResponseBuilder;
import com.qa.demo.qa.learning.MysqlSchemaCatalogAssessmentService;
import com.qa.demo.qa.learning.MysqlSchemaCatalogService;
import com.qa.demo.qa.learning.SchemaSedimentationPlanService;
import com.qa.demo.qa.learning.StructuredIngestJobService;
import com.qa.demo.qa.learning.StructuredCsvIngestService;
import com.qa.demo.qa.learning.StructuredTableRowAuditService;
import com.qa.demo.qa.orchestration.QaAskOrchestrator;
import com.qa.demo.qa.response.QaLogService;
import com.qa.demo.qa.sedimentation.FeedbackPersistenceService;
import com.qa.demo.qa.sedimentation.SedimentationQueueService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QA HTTP 入口：编排委托 {@link QaAskOrchestrator}，学习上传/文本委托 {@link ActiveLearningService} 与 {@link LearningResponseBuilder}；结构化行数审计与 CSV 门禁见 {@code /qa/structured/*}；MySQL 元数据目录见 {@code /qa/mysql/schema-catalog}；结构化沉淀方案见 {@code /qa/mysql/sedimentation/pipeline}。
 */
@RestController
@RequestMapping("/qa")
public class QaController {

    private final QaAskOrchestrator askOrchestrator;
    private final QaLogService qaLogService;
    private final ActiveLearningService activeLearningService;
    private final LearningResponseBuilder learningResponseBuilder;
    private final StructuredTableRowAuditService structuredTableRowAuditService;
    private final StructuredIngestJobService structuredIngestJobService;
    private final StructuredCsvIngestService structuredCsvIngestService;
    private final MysqlSchemaCatalogService mysqlSchemaCatalogService;
    private final MysqlSchemaCatalogAssessmentService mysqlSchemaCatalogAssessmentService;
    private final FeedbackPersistenceService feedbackPersistenceService;
    private final SedimentationQueueService sedimentationQueueService;
    private final SchemaSedimentationPlanService schemaSedimentationPlanService;
    private final QaAssistantProperties assistantProperties;

    public QaController(
            QaAskOrchestrator askOrchestrator,
            QaLogService qaLogService,
            ActiveLearningService activeLearningService,
            LearningResponseBuilder learningResponseBuilder,
            StructuredTableRowAuditService structuredTableRowAuditService,
            StructuredIngestJobService structuredIngestJobService,
            StructuredCsvIngestService structuredCsvIngestService,
            MysqlSchemaCatalogService mysqlSchemaCatalogService,
            MysqlSchemaCatalogAssessmentService mysqlSchemaCatalogAssessmentService,
            FeedbackPersistenceService feedbackPersistenceService,
            SedimentationQueueService sedimentationQueueService,
            SchemaSedimentationPlanService schemaSedimentationPlanService,
            QaAssistantProperties assistantProperties
    ) {
        this.askOrchestrator = askOrchestrator;
        this.qaLogService = qaLogService;
        this.activeLearningService = activeLearningService;
        this.learningResponseBuilder = learningResponseBuilder;
        this.structuredTableRowAuditService = structuredTableRowAuditService;
        this.structuredIngestJobService = structuredIngestJobService;
        this.structuredCsvIngestService = structuredCsvIngestService;
        this.mysqlSchemaCatalogService = mysqlSchemaCatalogService;
        this.mysqlSchemaCatalogAssessmentService = mysqlSchemaCatalogAssessmentService;
        this.feedbackPersistenceService = feedbackPersistenceService;
        this.sedimentationQueueService = sedimentationQueueService;
        this.schemaSedimentationPlanService = schemaSedimentationPlanService;
        this.assistantProperties = assistantProperties;
    }

    /**
     * 本地排障：核对当前 JVM 读到的 MiniMax 配置（不返回密钥明文）。
     * 若此处显示未配置，而终端 curl 正常，说明 IDE/另一进程未继承 {@code MINIMAX_API_KEY}。
     */
    @GetMapping("/assistant/runtime-summary")
    public Map<String, Object> assistantRuntimeSummary() {
        String key = assistantProperties.getApiKey();
        boolean present = key != null && !key.isBlank();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minimaxApiKeyPresent", present);
        body.put("minimaxApiKeyCharLength", present ? key.length() : 0);
        body.put("minimaxApiUrl", assistantProperties.getApiUrl());
        body.put("minimaxModel", assistantProperties.getModel());
        body.put("mysqlEnabled", assistantProperties.isMysqlEnabled());
        body.put("vectorEnabled", assistantProperties.isVectorEnabled());
        body.put("hint", present
                ? "密钥非空；若仍报 login fail，请核对是否为有效 MiniMax Chat API Key 并已重启进程。"
                : "未读到 qa.assistant.api-key（通常应来自环境变量 MINIMAX_API_KEY 或 application-local.properties）；与 curl 成功的终端不是同一环境。");
        body.put("timestamp", OffsetDateTime.now().toString());
        return body;
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@Valid @RequestBody AskRequest request) throws IOException {
        return askOrchestrator.buildAskResponse(
                request.question(),
                QaScopes.normalize(request.scope()),
                request.conversationId(),
                request.followUp()
        );
    }

    @PostMapping(value = "/learn/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> learnByUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "manual_upload") String trigger,
            @RequestParam(defaultValue = QaScopes.ENTERPRISE) String scope
    ) throws IOException {
        String normalizedScope = QaScopes.normalize(scope);
        if (file == null || file.isEmpty()) {
            return Map.of(
                    "ok", false,
                    "message", "上传文件为空，请重新选择 .md 文件。",
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
        String filename = file.getOriginalFilename() == null ? "unknown.md" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".md")) {
            return Map.of(
                    "ok", false,
                    "message", "仅支持上传 .md 文件。",
                    "filename", filename,
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        ActiveLearningService.LearningResult result = activeLearningService.learn(
                content,
                "markdown_upload",
                filename,
                trigger,
                normalizedScope
        );
        return learningResponseBuilder.buildLearningResponse(
                qaLogService.nextTurnId(),
                "upload:" + filename,
                result,
                normalizedScope
        );
    }

    @PostMapping("/learn/text")
    public Map<String, Object> learnByText(@Valid @RequestBody LearnRequest request) {
        String normalizedScope = QaScopes.normalize(request.scope());
        ActiveLearningService.LearningResult result = activeLearningService.learn(
                request.content(),
                "manual_text",
                request.title() == null || request.title().isBlank() ? "manual_text" : request.title(),
                "manual_api",
                normalizedScope
        );
        return learningResponseBuilder.buildLearningResponse(
                qaLogService.nextTurnId(),
                request.content(),
                result,
                normalizedScope
        );
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody AskRequest request) {
        return askOrchestrator.startAskStream(
                request.question(),
                QaScopes.normalize(request.scope()),
                request.conversationId(),
                request.followUp()
        );
    }

    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStreamByQuery(
            @RequestParam("question") String question,
            @RequestParam(defaultValue = QaScopes.ENTERPRISE) String scope,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) Boolean followUp
    ) {
        return askOrchestrator.startAskStream(question, QaScopes.normalize(scope), conversationId, followUp);
    }

    /**
     * 对业务表做 COUNT，与 {@code qa.assistant.max-structured-ingest-rows} 比对（入库前自检）。
     */
    @PostMapping("/structured/row-audit")
    public Map<String, Object> auditStructuredTables(@Valid @RequestBody TableAuditRequest request) {
        List<StructuredTableRowAuditService.TableRowAudit> audits = structuredTableRowAuditService.auditTables(request.tables());
        boolean allOk = audits.stream().allMatch(StructuredTableRowAuditService.TableRowAudit::withinLimit);
        return Map.of(
                "ok", allOk,
                "audits", audits,
                "timestamp", OffsetDateTime.now().toString()
        );
    }

    /**
     * 入库前统一门禁（与 {@link #auditStructuredTables} 同源审计，额外返回 {@code allowedToProceed} 与拒绝原因，供 ETL 编排）。
     */
    @PostMapping("/structured/ingest-gate")
    public Map<String, Object> structuredIngestGate(@Valid @RequestBody TableAuditRequest request) {
        StructuredIngestJobService.GateResult gate = structuredIngestJobService.evaluateGate(request.tables());
        return toIngestGateResponse(gate, null, false, null);
    }

    /**
     * 手动执行清单门禁；可选将结果以 JSON 行追加到 {@code qa.assistant.structured-ingest-job-log-path}（或默认日志文件）。
     */
    @PostMapping("/structured/job/run")
    public Map<String, Object> runStructuredIngestJob(@Valid @RequestBody IngestJobRequest request) throws Exception {
        String jobName = request.jobName() == null || request.jobName().isBlank() ? "api" : request.jobName().trim();
        StructuredIngestJobService.Manifest manifest = new StructuredIngestJobService.Manifest(request.tables(), jobName);
        StructuredIngestJobService.GateResult gate = structuredIngestJobService.evaluateGate(manifest.tables());
        boolean log = request.logResult() == null || Boolean.TRUE.equals(request.logResult());
        if (log) {
            structuredIngestJobService.appendJobLog("api", manifest, gate);
        }
        return toIngestGateResponse(gate, jobName, log, null);
    }

    /**
     * 读取 {@code qa.assistant.structured-ingest-manifest-path} 指向的 JSON 清单并执行门禁（路径仅来自配置，防路径穿越）。
     */
    @PostMapping("/structured/job/run-from-config")
    public Map<String, Object> runStructuredIngestJobFromConfig() {
        try {
            StructuredIngestJobService.RunOutcome outcome =
                    structuredIngestJobService.runConfiguredManifestWithAppendLog("api_manifest_file");
            return toIngestGateResponse(
                    outcome.gate(),
                    outcome.manifest().jobName(),
                    true,
                    structuredIngestJobService.configuredManifestPathOrNull()
            );
        } catch (IllegalStateException e) {
            return Map.of(
                    "ok", false,
                    "allowedToProceed", false,
                    "message", e.getMessage() == null ? "illegal_state" : e.getMessage(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "allowedToProceed", false,
                    "message", e.getMessage() == null ? "manifest_job_failed" : e.getMessage(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
    }

    private Map<String, Object> toIngestGateResponse(
            StructuredIngestJobService.GateResult gate,
            String jobName,
            boolean logAppended,
            String manifestPath
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", gate.allowedToProceed());
        m.put("allowedToProceed", gate.allowedToProceed());
        m.put("rejectionReason", gate.rejectionReason());
        m.put("maxStructuredIngestRows", gate.rowLimit());
        m.put("audits", gate.audits());
        m.put("writeToBusinessTablesAllowed", false);
        m.put("writeToBusinessTablesNote", "本应用不对业务表执行 DML；外部 ETL 须在门禁通过后自行入库。");
        if (jobName != null) {
            m.put("jobName", jobName);
        }
        m.put("logAppended", logAppended);
        if (manifestPath != null) {
            m.put("manifestPath", manifestPath);
        }
        m.put("timestamp", OffsetDateTime.now().toString());
        return m;
    }

    /**
     * CSV 结构化接入：按数据行行数与 {@code qa.assistant.max-structured-ingest-rows} 比对，超限则拒绝写入主动学习通道；
     * 通过则将 CSV 包装为文本走与 {@code /qa/learn/text} 相同的多路持久化（不直接改业务表）。
     */
    @PostMapping(value = "/structured/csv-ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ingestStructuredCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean headerRow,
            @RequestParam(defaultValue = QaScopes.ENTERPRISE) String scope
    ) {
        String normalizedScope = QaScopes.normalize(scope);
        int maxBytes = structuredCsvIngestService.maxAllowedUploadBytes();
        if (file == null || file.isEmpty()) {
            return Map.of(
                    "ok", false,
                    "rejected", true,
                    "rejectionReason", "empty_file",
                    "message", "上传文件为空。",
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
        String filename = file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".csv")) {
            return Map.of(
                    "ok", false,
                    "rejected", true,
                    "rejectionReason", "unsupported_extension",
                    "message", "仅支持 .csv 文件。",
                    "filename", filename,
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return Map.of(
                    "ok", false,
                    "rejected", true,
                    "rejectionReason", "read_failed",
                    "message", e.getMessage(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
        if (bytes.length > maxBytes) {
            return Map.of(
                    "ok", false,
                    "rejected", true,
                    "rejectionReason", "file_too_large",
                    "message", "文件超过允许大小（字节）：" + maxBytes,
                    "filename", filename,
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
        StructuredCsvIngestService.CsvRowCountAudit audit = structuredCsvIngestService.auditDataRows(bytes, headerRow);
        if (!audit.withinLimit()) {
            return Map.of(
                    "ok", false,
                    "rejected", true,
                    "rejectionReason", "row_count_exceeds_limit",
                    "dataRowCount", audit.dataRowCount(),
                    "limit", audit.limit(),
                    "filename", filename,
                    "headerRow", headerRow,
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
        ActiveLearningService.LearningResult result = structuredCsvIngestService.learnCsvBytes(
                bytes,
                headerRow,
                filename,
                normalizedScope
        );
        Map<String, Object> body = new HashMap<>(learningResponseBuilder.buildLearningResponse(
                qaLogService.nextTurnId(),
                "csv:" + filename,
                result,
                normalizedScope
        ));
        body.put("structuredIngest", Map.of(
                "format", "csv",
                "dataRowCount", audit.dataRowCount(),
                "limit", audit.limit(),
                "headerRow", headerRow,
                "withinLimit", true
        ));
        return body;
    }

    /**
     * 基于 {@code qa.assistant.mysql-*} 只读导出 {@code information_schema} 目录（Markdown）；
     * {@code assess=true} 时可选调用大模型生成沉淀评估并拼入 {@code combinedMarkdown}；
     * {@code persist=true} 时将 {@code combinedMarkdown} 送入主动学习（与 {@code /qa/learn/text} 同源三通路）。
     */
    @PostMapping("/mysql/schema-catalog")
    public Map<String, Object> mysqlSchemaCatalog(@RequestBody(required = false) SchemaCatalogRequest request) {
        boolean persist = request != null && Boolean.TRUE.equals(request.persist());
        boolean assess = request != null && Boolean.TRUE.equals(request.assess());
        String rawScope = request != null && request.scope() != null && !request.scope().isBlank()
                ? request.scope()
                : QaScopes.ENTERPRISE;
        String normalizedScope = QaScopes.normalize(rawScope);
        if (!mysqlSchemaCatalogService.canExport()) {
            return Map.of(
                    "ok", false,
                    "mysqlEnabled", false,
                    "message", "MySQL 未启用或未配置 mysql-schema。",
                    "persist", persist,
                    "assess", assess,
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
        try {
            MysqlSchemaCatalogService.SchemaCatalogExport export = mysqlSchemaCatalogService.exportCatalog();
            String catalogMd = export.markdown();
            MysqlSchemaCatalogAssessmentService.AssessmentOutcome assessmentOutcome = null;
            String combinedMd = catalogMd;
            String learnSourceType = "mysql_schema_catalog";
            String learnTrigger = "schema_catalog_api";

            if (assess) {
                assessmentOutcome = mysqlSchemaCatalogAssessmentService.assess(export);
                if (!assessmentOutcome.failed() && !assessmentOutcome.modelText().isBlank()) {
                    combinedMd = MysqlSchemaCatalogAssessmentService.combineCatalogAndAssessment(
                            catalogMd,
                            assessmentOutcome.modelText()
                    );
                    learnSourceType = "mysql_schema_catalog_assessed";
                    learnTrigger = "schema_catalog_api_assessed";
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("mysqlEnabled", true);
            body.put("schema", export.schema());
            body.put("tableCount", export.tableCount());
            body.put("markdown", catalogMd);
            body.put("markdownTruncated", export.markdownTruncated());
            body.put("markdownCharCount", export.markdownCharCount());
            body.put("assess", assess);
            if (assess && assessmentOutcome != null) {
                body.put("assessmentFailed", assessmentOutcome.failed());
                if (assessmentOutcome.errorMessage() != null) {
                    body.put("assessmentError", assessmentOutcome.errorMessage());
                }
                body.put("catalogTruncatedForModel", assessmentOutcome.catalogTruncatedForModel());
                body.put("modelAssessment", assessmentOutcome.failed() ? null : assessmentOutcome.modelText());
            }
            body.put("combinedMarkdown", combinedMd);
            body.put("combinedMarkdownCharCount", combinedMd.length());
            body.put("persist", persist);
            body.put("timestamp", OffsetDateTime.now().toString());

            if (persist) {
                ActiveLearningService.LearningResult result = activeLearningService.learn(
                        combinedMd,
                        learnSourceType,
                        "schema-" + export.schema(),
                        learnTrigger,
                        normalizedScope
                );
                body.putAll(learningResponseBuilder.buildLearningResponse(
                        qaLogService.nextTurnId(),
                        "mysql-schema-catalog:" + export.schema(),
                        result,
                        normalizedScope
                ));
                body.put("ok", result.success());
                Map<String, Object> ingestMeta = new LinkedHashMap<>();
                ingestMeta.put("sourceType", learnSourceType);
                ingestMeta.put("schema", export.schema());
                ingestMeta.put("tableCount", export.tableCount());
                ingestMeta.put("markdownTruncated", export.markdownTruncated());
                ingestMeta.put("assessRequested", assess);
                if (assess && assessmentOutcome != null) {
                    ingestMeta.put("assessmentFailed", assessmentOutcome.failed());
                }
                body.put("schemaCatalogIngest", ingestMeta);
            }
            return body;
        } catch (IllegalStateException e) {
            return Map.of(
                    "ok", false,
                    "mysqlEnabled", true,
                    "message", e.getMessage() == null ? "illegal_state" : e.getMessage(),
                    "persist", persist,
                    "assess", assess,
                    "timestamp", OffsetDateTime.now().toString()
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "mysqlEnabled", true,
                    "message", e.getMessage() == null ? "schema_catalog_failed" : e.getMessage(),
                    "persist", persist,
                    "assess", assess,
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
    }

    @GetMapping("/sedimentation/pending")
    public Map<String, Object> listPendingSedimentation(@RequestParam(defaultValue = "50") int limit) {
        return Map.of(
                "items", sedimentationQueueService.listPending(limit),
                "limit", limit,
                "timestamp", OffsetDateTime.now().toString()
        );
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@Valid @RequestBody FeedbackRequest request) {
        return feedbackPersistenceService.recordFeedback(request.turnId(), request.useful(), request.comment());
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> rows = qaLogService.readAskHistory(limit);
        return Map.of(
                "limit", limit,
                "count", rows.size(),
                "items", rows,
                "timestamp", OffsetDateTime.now().toString()
        );
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception e) {
        return Map.of(
                "error", "QA request failed",
                "message", e.getMessage()
        );
    }

    public record AskRequest(
            @NotBlank String question,
            String scope,
            String conversationId,
            Boolean followUp
    ) {
    }

    public record LearnRequest(
            @NotBlank String content,
            String title,
            String scope
    ) {
    }

    public record FeedbackRequest(
            @NotBlank String turnId,
            boolean useful,
            String comment
    ) {
    }

    public record TableAuditRequest(
            @NotEmpty List<@NotBlank String> tables
    ) {
    }

    /**
     * @param persist 为 true 时在导出成功后写入主动学习通道（载荷为 combinedMarkdown）
     * @param assess  为 true 时调用大模型生成沉淀评估并拼入 combinedMarkdown；失败则仅使用目录正文
     */
    public record SchemaCatalogRequest(Boolean persist, String scope, Boolean assess) {
    }

    public record IngestJobRequest(
            @NotEmpty List<@NotBlank String> tables,
            String jobName,
            Boolean logResult
    ) {
    }

    public record TableSampleRequest(
            @NotBlank String host,
            int port,
            @NotBlank String database,
            @NotBlank String username,
            String password,
            @NotBlank String table,
            Integer limit
    ) {
    }

    public record TableDataRequest(
            @NotBlank String host,
            int port,
            @NotBlank String database,
            @NotBlank String username,
            String password,
            @NotBlank String table,
            Long offset,
            Integer limit
    ) {
    }

    /**
     * @param source {@code configured} 使用 qa.assistant.mysql-*；{@code dynamic} 须填 host/port/database/username/password
     */
    public record SedimentationPipelineRequest(
            @NotBlank String source,
            String host,
            Integer port,
            String database,
            String username,
            String password,
            Boolean persist,
            String scope
    ) {
    }

    /**
     * 动态数据库连接：传入连接参数，拉取 Schema → 评估 → 写入知识库。
     */
    public record DynamicConnectRequest(
            @NotBlank String host,
            int port,
            @NotBlank String database,
            @NotBlank String username,
            String password,
            Boolean assess,
            Boolean persist,
            String scope
    ) {
    }

    /**
     * POST /qa/mysql/connect - 动态连接数据库并导出 Schema
     */
    @PostMapping("/mysql/connect")
    public Map<String, Object> mysqlDynamicConnect(@Valid @RequestBody DynamicConnectRequest request) {
        String rawScope = request.scope() != null && !request.scope().isBlank()
                ? request.scope()
                : QaScopes.ENTERPRISE;
        String normalizedScope = QaScopes.normalize(rawScope);
        boolean assess = request.assess() != null && Boolean.TRUE.equals(request.assess());
        boolean persist = request.persist() != null && Boolean.TRUE.equals(request.persist());

        try {
            MysqlSchemaCatalogService.DynamicConnection conn =
                    new MysqlSchemaCatalogService.DynamicConnection(
                            request.host(),
                            request.port(),
                            request.database(),
                            request.username(),
                            request.password() != null ? request.password() : ""
                    );

            MysqlSchemaCatalogService.SchemaCatalogExport export =
                    mysqlSchemaCatalogService.exportCatalogWithConnection(conn, 100, 250_000);

            String catalogMd = export.markdown();
            MysqlSchemaCatalogAssessmentService.AssessmentOutcome assessmentOutcome = null;
            String combinedMd = catalogMd;
            String learnSourceType = "mysql_dynamic_schema_catalog";
            String learnTrigger = "dynamic_connect_api";

            if (assess) {
                assessmentOutcome = mysqlSchemaCatalogAssessmentService.assess(export);
                if (!assessmentOutcome.failed() && !assessmentOutcome.modelText().isBlank()) {
                    combinedMd = MysqlSchemaCatalogAssessmentService.combineCatalogAndAssessment(
                            catalogMd,
                            assessmentOutcome.modelText()
                    );
                    learnSourceType = "mysql_dynamic_schema_catalog_assessed";
                    learnTrigger = "dynamic_connect_api_assessed";
                }
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("host", request.host());
            body.put("port", request.port());
            body.put("database", request.database());
            body.put("schema", export.schema());
            body.put("tableCount", export.tableCount());
            body.put("markdown", catalogMd);
            body.put("markdownTruncated", export.markdownTruncated());
            body.put("markdownCharCount", export.markdownCharCount());
            body.put("assess", assess);
            if (assess && assessmentOutcome != null) {
                body.put("assessmentFailed", assessmentOutcome.failed());
                if (assessmentOutcome.errorMessage() != null) {
                    body.put("assessmentError", assessmentOutcome.errorMessage());
                }
                body.put("catalogTruncatedForModel", assessmentOutcome.catalogTruncatedForModel());
                body.put("modelAssessment", assessmentOutcome.failed() ? null : assessmentOutcome.modelText());
            }
            body.put("combinedMarkdown", combinedMd);
            body.put("combinedMarkdownCharCount", combinedMd.length());
            body.put("persist", persist);
            body.put("timestamp", OffsetDateTime.now().toString());

            if (persist) {
                ActiveLearningService.LearningResult result = activeLearningService.learn(
                        combinedMd,
                        learnSourceType,
                        "dynamic-schema-" + request.host() + "-" + request.database(),
                        learnTrigger,
                        normalizedScope
                );
                body.putAll(learningResponseBuilder.buildLearningResponse(
                        qaLogService.nextTurnId(),
                        "mysql-dynamic-schema:" + request.host() + ":" + request.database(),
                        result,
                        normalizedScope
                ));
                body.put("ok", result.success());
                body.put("schemaCatalogIngest", Map.of(
                        "sourceType", learnSourceType,
                        "host", request.host(),
                        "database", request.database(),
                        "tableCount", export.tableCount(),
                        "markdownTruncated", export.markdownTruncated(),
                        "assessRequested", assess
                ));
            }

            return body;
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "message", e.getMessage() == null ? "connection_failed" : e.getMessage(),
                    "host", request.host(),
                    "port", request.port(),
                    "database", request.database(),
                    "persist", persist,
                    "assess", assess,
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
    }

    /**
     * 基于表结构元数据：模型输出结构化「可行性 + 沉淀方案 JSON」→ 可选二次生成正文 →
     * 按方案启用的通路写入 {@code qa_active_knowledge} / Qdrant / Neo4j（物理形态仍由应用白名单固定）。
     */
    @PostMapping("/mysql/sedimentation/pipeline")
    public Map<String, Object> mysqlSedimentationPipeline(@Valid @RequestBody SedimentationPipelineRequest request) {
        String rawScope = request.scope() != null && !request.scope().isBlank()
                ? request.scope()
                : QaScopes.ENTERPRISE;
        String normalizedScope = QaScopes.normalize(rawScope);
        boolean persist = Boolean.TRUE.equals(request.persist());
        String src = request.source() == null ? "" : request.source().trim().toLowerCase();

        MysqlSchemaCatalogService.SchemaCatalogExport export;
        try {
            if ("configured".equals(src)) {
                if (!mysqlSchemaCatalogService.canExport()) {
                    return Map.of(
                            "ok", false,
                            "message", "MySQL 未启用或未配置 mysql-schema。",
                            "timestamp", OffsetDateTime.now().toString()
                    );
                }
                export = mysqlSchemaCatalogService.exportCatalog();
            } else if ("dynamic".equals(src)) {
                if (request.host() == null || request.host().isBlank()
                        || request.port() == null
                        || request.database() == null || request.database().isBlank()
                        || request.username() == null || request.username().isBlank()) {
                    return Map.of(
                            "ok", false,
                            "message", "source=dynamic 时必须提供 host、port、database、username。",
                            "timestamp", OffsetDateTime.now().toString()
                    );
                }
                MysqlSchemaCatalogService.DynamicConnection conn = new MysqlSchemaCatalogService.DynamicConnection(
                        request.host(),
                        request.port(),
                        request.database(),
                        request.username(),
                        request.password() != null ? request.password() : ""
                );
                export = mysqlSchemaCatalogService.exportCatalogWithConnection(conn);
            } else {
                return Map.of(
                        "ok", false,
                        "message", "source 必须为 configured 或 dynamic。",
                        "timestamp", OffsetDateTime.now().toString()
                );
            }
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "message", e.getMessage() == null ? "schema_export_failed" : e.getMessage(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        }

        SchemaSedimentationPlanService.PipelineOutcome outcome =
                schemaSedimentationPlanService.runPipeline(export, persist, normalizedScope);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", outcome.ok());
        body.put("message", outcome.message());
        body.put("schema", export.schema());
        body.put("tableCount", export.tableCount());
        body.put("persistRequested", persist);
        body.put("timestamp", OffsetDateTime.now().toString());

        if (outcome.plan() != null) {
            SchemaSedimentationPlanService.ParsedSedimentationPlan p = outcome.plan();
            body.put("feasible", p.feasible());
            body.put("feasibilityRationale", p.feasibilityRationale());
            body.put("confidence", p.confidence());
            body.put("planSummaryMarkdown", p.planSummaryMarkdown());
            body.put("sinkPolicy", Map.of(
                    "mysql", p.sinkPolicy().mysql(),
                    "qdrant", p.sinkPolicy().qdrant(),
                    "neo4j", p.sinkPolicy().neo4j(),
                    "keywordLimit", p.sinkPolicy().keywordLimit()
            ));
            body.put("bodyStrategy", p.bodyStrategy().name());
            body.put("ingestTitleHint", p.titleHint());
        }
        if (outcome.rawPlanJson() != null) {
            body.put("planJson", outcome.rawPlanJson());
        }
        body.put("digestApplied", outcome.digestApplied());
        body.put("ingestPreview", outcome.ingestPreview());

        if (outcome.learningResult() != null) {
            ActiveLearningService.LearningResult lr = outcome.learningResult();
            body.putAll(learningResponseBuilder.buildLearningResponse(
                    qaLogService.nextTurnId(),
                    "mysql-sedimentation-pipeline:" + export.schema(),
                    lr,
                    normalizedScope
            ));
            body.put("sedimentationPipeline", Map.of(
                    "sourceType", "mysql_schema_sedimentation_plan",
                    "schema", export.schema(),
                    "digestApplied", outcome.digestApplied()
            ));
        }
        return body;
    }

    /**
     * 获取数据库表关联关系（外键）。
     */
    @PostMapping("/mysql/relationships")
    public Map<String, Object> mysqlTableRelationships(@Valid @RequestBody DynamicConnectRequest request) {
        try {
            MysqlSchemaCatalogService.DynamicConnection conn =
                    new MysqlSchemaCatalogService.DynamicConnection(
                            request.host(),
                            request.port(),
                            request.database(),
                            request.username(),
                            request.password() != null ? request.password() : ""
                    );

            List<MysqlSchemaCatalogService.TableRelationship> relationships =
                    mysqlSchemaCatalogService.getTableRelationshipsWithConnection(conn);

            return Map.of(
                    "ok", true,
                    "host", request.host(),
                    "port", request.port(),
                    "database", request.database(),
                    "relationships", relationships,
                    "count", relationships.size(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "message", e.getMessage() == null ? "get_relationships_failed" : e.getMessage(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
    }

    /**
     * 读取单表样本数据（用于评估阶段）。
     */
    @PostMapping("/mysql/table/sample")
    public Map<String, Object> mysqlTableSampleData(
            @Valid @RequestBody TableSampleRequest request
    ) {
        try {
            MysqlSchemaCatalogService.DynamicConnection conn =
                    new MysqlSchemaCatalogService.DynamicConnection(
                            request.host(),
                            request.port(),
                            request.database(),
                            request.username(),
                            request.password() != null ? request.password() : ""
                    );

            int limit = request.limit() != null && request.limit() > 0 ? request.limit() : 10;
            List<Map<String, String>> sampleData =
                    mysqlSchemaCatalogService.readTableSampleDataWithConnection(conn, request.table(), limit);

            return Map.of(
                    "ok", true,
                    "host", request.host(),
                    "port", request.port(),
                    "database", request.database(),
                    "table", request.table(),
                    "sampleData", sampleData,
                    "rowCount", sampleData.size(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "message", e.getMessage() == null ? "read_sample_data_failed" : e.getMessage(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
    }

    /**
     * 读取表全量数据（用于沉淀阶段，支持分页）。
     */
    @PostMapping("/mysql/table/data")
    public Map<String, Object> mysqlTableData(
            @Valid @RequestBody TableDataRequest request
    ) {
        try {
            MysqlSchemaCatalogService.DynamicConnection conn =
                    new MysqlSchemaCatalogService.DynamicConnection(
                            request.host(),
                            request.port(),
                            request.database(),
                            request.username(),
                            request.password() != null ? request.password() : ""
                    );

            long offset = request.offset() != null ? request.offset() : 0;
            int limit = request.limit() != null && request.limit() > 0 ? request.limit() : 1000;
            List<Map<String, String>> tableData =
                    mysqlSchemaCatalogService.readTableDataWithConnection(conn, request.table(), offset, limit);

            return Map.of(
                    "ok", true,
                    "host", request.host(),
                    "port", request.port(),
                    "database", request.database(),
                    "table", request.table(),
                    "offset", offset,
                    "limit", limit,
                    "data", tableData,
                    "rowCount", tableData.size(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        } catch (Exception e) {
            return Map.of(
                    "ok", false,
                    "message", e.getMessage() == null ? "read_table_data_failed" : e.getMessage(),
                    "timestamp", OffsetDateTime.now().toString()
            );
        }
    }

    /**
     * 导出表数据为 CSV 文件（下载）。
     */
    @PostMapping("/mysql/table/export-csv")
    public void exportTableAsCsv(
            @Valid @RequestBody TableDataRequest request,
            HttpServletResponse response
    ) {
        try {
            MysqlSchemaCatalogService.DynamicConnection conn =
                    new MysqlSchemaCatalogService.DynamicConnection(
                            request.host(),
                            request.port(),
                            request.database(),
                            request.username(),
                            request.password() != null ? request.password() : ""
                    );

            long offset = request.offset() != null ? request.offset() : 0;
            int limit = request.limit() != null && request.limit() > 0 ? request.limit() : 10000;
            List<Map<String, String>> tableData =
                    mysqlSchemaCatalogService.readTableDataWithConnection(conn, request.table(), offset, limit);

            if (tableData.isEmpty()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("No data found");
                return;
            }

            StringBuilder csv = new StringBuilder();
            // Header
            Map<String, String> firstRow = tableData.get(0);
            csv.append(String.join(",", firstRow.keySet())).append("\n");
            // Data rows
            for (Map<String, String> row : tableData) {
                csv.append(String.join(",", row.values())).append("\n");
            }

            String filename = request.table() + "_" + offset + ".csv";
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            response.getWriter().write(csv.toString());

        } catch (Exception e) {
            try {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                response.getWriter().write("Error: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }
}
