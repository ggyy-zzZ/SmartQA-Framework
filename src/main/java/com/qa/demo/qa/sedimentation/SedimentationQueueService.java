package com.qa.demo.qa.sedimentation;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将「未知意图 / 证据不足」等待沉淀样本写入 MySQL {@code qa_pending_knowledge}（与 jsonl 并行）。
 */
@Service
public class SedimentationQueueService {

    private static final String TABLE = "qa_pending_knowledge";

    private final QaAssistantProperties properties;
    private final ObjectMapper objectMapper;

    public SedimentationQueueService(QaAssistantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void enqueuePending(
            String turnId,
            String question,
            String intent,
            String retrievalSource,
            String depositReason,
            List<ContextChunk> evidence
    ) {
        if (!properties.isMysqlEnabled()) {
            return;
        }
        String pendingId = UUID.randomUUID().toString();
        String evidenceJson;
        try {
            evidenceJson = evidence == null ? "[]" : objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            evidenceJson = "[]";
        }
        String qualified = "`" + properties.getMysqlSchema() + "`.`" + TABLE + "`";
        String createSql = """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    pending_id VARCHAR(64) NOT NULL,
                    turn_id VARCHAR(64) NOT NULL,
                    question TEXT NOT NULL,
                    intent VARCHAR(64) NOT NULL,
                    retrieval_source VARCHAR(128) NOT NULL,
                    deposit_reason VARCHAR(64) NOT NULL,
                    evidence_json LONGTEXT,
                    status VARCHAR(32) NOT NULL DEFAULT 'pending',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_pending_id (pending_id),
                    KEY idx_status_created (status, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(qualified);
        String insertSql = """
                INSERT INTO %s (pending_id, turn_id, question, intent, retrieval_source, deposit_reason, evidence_json, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')
                """.formatted(qualified);
        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword());
             Statement st = connection.createStatement()) {
            st.execute(createSql);
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                ps.setString(1, pendingId);
                ps.setString(2, truncate(turnId, 64));
                ps.setString(3, question == null ? "" : question);
                ps.setString(4, truncate(intent, 64));
                ps.setString(5, truncate(retrievalSource, 128));
                ps.setString(6, truncate(depositReason, 64));
                ps.setString(7, evidenceJson);
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
            // 不因沉淀失败阻断主问答
        }
    }

    public List<Map<String, Object>> listPending(int limit) {
        int top = Math.max(1, Math.min(limit, 200));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!properties.isMysqlEnabled()) {
            return rows;
        }
        String qualified = "`" + properties.getMysqlSchema() + "`.`" + TABLE + "`";
        String sql = """
                SELECT pending_id, turn_id, question, intent, retrieval_source, deposit_reason, status, created_at
                FROM %s
                WHERE status = 'pending'
                ORDER BY created_at DESC
                LIMIT ?
                """.formatted(qualified);
        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword());
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, top);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("pendingId", rs.getString("pending_id"));
                    row.put("turnId", rs.getString("turn_id"));
                    row.put("question", rs.getString("question"));
                    row.put("intent", rs.getString("intent"));
                    row.put("retrievalSource", rs.getString("retrieval_source"));
                    row.put("depositReason", rs.getString("deposit_reason"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", rs.getTimestamp("created_at") == null
                            ? null
                            : rs.getTimestamp("created_at").toInstant().toString());
                    rows.add(row);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
