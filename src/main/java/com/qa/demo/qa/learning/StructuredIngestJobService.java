package com.qa.demo.qa.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * P0 结构化流水线：行数门禁聚合（{@link #evaluateGate}）、清单 JSON 解析与任务日志。
 * <p>
 * 本仓库<strong>不</strong>对业务表执行自动 INSERT/LOAD DATA；「加载入库」由外部 ETL 在通过门禁后自行完成。
 */
@Service
public class StructuredIngestJobService {

    private static final Logger log = LoggerFactory.getLogger(StructuredIngestJobService.class);

    private final StructuredTableRowAuditService rowAuditService;
    private final QaAssistantProperties properties;
    private final ObjectMapper objectMapper;

    public StructuredIngestJobService(
            StructuredTableRowAuditService rowAuditService,
            QaAssistantProperties properties,
            ObjectMapper objectMapper
    ) {
        this.rowAuditService = rowAuditService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public record GateResult(
            boolean allowedToProceed,
            String rejectionReason,
            List<StructuredTableRowAuditService.TableRowAudit> audits,
            int rowLimit
    ) {
    }

    public record Manifest(List<String> tables, String jobName) {
    }

    /**
     * 入库前统一门禁：任一表超限、不可访问或 MySQL 未启用则 {@code allowedToProceed=false}。
     */
    public GateResult evaluateGate(List<String> tables) {
        int limit = Math.max(1, properties.getMaxStructuredIngestRows());
        if (!properties.isMysqlEnabled()) {
            return new GateResult(false, "mysql_disabled", List.of(), limit);
        }
        List<String> normalized = normalizeTableList(tables);
        if (normalized.isEmpty()) {
            return new GateResult(false, "no_tables", List.of(), limit);
        }
        List<StructuredTableRowAuditService.TableRowAudit> audits = rowAuditService.auditTables(normalized);
        boolean anyInaccessible = audits.stream().anyMatch(a -> a.rowCount() < 0);
        if (anyInaccessible) {
            return new GateResult(false, "table_invalid_or_inaccessible", audits, limit);
        }
        boolean allWithin = audits.stream().allMatch(StructuredTableRowAuditService.TableRowAudit::withinLimit);
        if (!allWithin) {
            return new GateResult(false, "row_count_exceeds_limit", audits, limit);
        }
        return new GateResult(true, null, audits, limit);
    }

    public Manifest readManifest(Path path) throws Exception {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(json);
        JsonNode tablesNode = root.get("tables");
        if (tablesNode == null || !tablesNode.isArray()) {
            throw new IllegalArgumentException("manifest 缺少 tables 数组");
        }
        List<String> tables = new ArrayList<>();
        for (JsonNode n : tablesNode) {
            if (n.isTextual()) {
                tables.add(n.asText());
            }
        }
        String jobName = root.has("jobName") && root.get("jobName").isTextual() ? root.get("jobName").asText() : "unnamed";
        return new Manifest(tables, jobName);
    }

    public GateResult runManifestFromPath(Path manifestPath) throws Exception {
        Manifest manifest = readManifest(manifestPath);
        return evaluateGate(manifest.tables());
    }

    /**
     * 读取 {@link QaAssistantProperties#getStructuredIngestManifestPath()} 指向的清单并执行门禁，且追加日志。
     */
    public RunOutcome runConfiguredManifestWithAppendLog(String trigger) throws Exception {
        String pathStr = properties.getStructuredIngestManifestPath();
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalStateException("未配置 qa.assistant.structured-ingest-manifest-path");
        }
        Path path = Path.of(pathStr.trim());
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("manifest 文件不可读: " + path);
        }
        Manifest manifest = readManifest(path);
        GateResult gate = evaluateGate(manifest.tables());
        appendJobLog(trigger, manifest, gate);
        return new RunOutcome(manifest, gate);
    }

    public record RunOutcome(Manifest manifest, GateResult gate) {
    }

    public void appendJobLog(String trigger, Manifest manifest, GateResult result) throws Exception {
        Path logPath = resolveJobLogPath();
        Files.createDirectories(logPath.getParent());
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", OffsetDateTime.now().toString());
        line.put("trigger", trigger);
        line.put("jobName", manifest == null ? null : manifest.jobName());
        line.put("allowedToProceed", result.allowedToProceed());
        line.put("rejectionReason", result.rejectionReason());
        line.put("rowLimit", result.rowLimit());
        line.put("tables", manifest == null ? null : manifest.tables());
        line.put("auditSummary", result.audits().stream()
                .map(a -> Map.of("table", a.table(), "rowCount", a.rowCount(), "withinLimit", a.withinLimit()))
                .collect(Collectors.toList()));
        String json = objectMapper.writeValueAsString(line);
        Files.writeString(logPath, json + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    public String configuredManifestPathOrNull() {
        String p = properties.getStructuredIngestManifestPath();
        return p == null || p.isBlank() ? null : p.trim();
    }

    private Path resolveJobLogPath() {
        String configured = properties.getStructuredIngestJobLogPath();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        Path docs = Path.of(properties.getDocsDir());
        Path parent = docs.getParent();
        if (parent == null) {
            return Path.of("structured_ingest_job.log");
        }
        return parent.resolve("structured_ingest_job.log");
    }

    private static List<String> normalizeTableList(List<String> tables) {
        if (tables == null) {
            return List.of();
        }
        return tables.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());
    }
}
