package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CDC 同步审计：库表变更触发、Neo4j/Qdrant 写入结果写入本地 JSONL。
 */
@Component
public class CdcSyncAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(CdcSyncAuditLogger.class);

    private final QaAssistantProperties props;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    public CdcSyncAuditLogger(QaAssistantProperties props) {
        this.props = props;
        this.objectMapper = new ObjectMapper();
    }

    /** 应用/Consumer 启动时写入一条会话标记。 */
    public void sessionStarted() {
        append("cdc_session_start", Map.of(
                "logPath", resolveLogPath().toString(),
                "enabled", props.isCdcAuditLogEnabled()
        ));
    }

    /** 从 Kafka 解析到可写入批次（代表 MySQL 行变更已送达）。 */
    public void mysqlChangeDetected(String table, String op, String entityId, int mergedEventCount) {
        append("mysql_change", Map.of(
                "table", table,
                "op", op,
                "entityId", entityId,
                "mergedEvents", mergedEventCount
        ));
    }

    public void storeWriteSuccess(String store, String table, String op, String entityId, String detail) {
        append(store + "_write_ok", Map.of(
                "store", store,
                "table", table,
                "op", op,
                "entityId", entityId,
                "detail", detail != null ? detail : ""
        ));
    }

    public void storeWriteFailed(String store, String table, String op, String entityId, String error) {
        append(store + "_write_fail", Map.of(
                "store", store,
                "table", table,
                "op", op,
                "entityId", entityId,
                "error", error != null ? error : ""
        ));
    }

    private void append(String event, Map<String, Object> fields) {
        if (!props.isCdcAuditLogEnabled()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", Instant.now().toString());
        row.put("event", event);
        row.putAll(fields);
        try {
            String line = objectMapper.writeValueAsString(row) + System.lineSeparator();
            Path path = resolveLogPath();
            synchronized (writeLock) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("[CDC Audit] Failed to write audit log: {}", e.getMessage());
        }
    }

    private Path resolveLogPath() {
        String configured = props.getCdcAuditLogPath();
        Path path = Path.of(configured);
        if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir", ".")).resolve(path).normalize();
        }
        return path;
    }
}
