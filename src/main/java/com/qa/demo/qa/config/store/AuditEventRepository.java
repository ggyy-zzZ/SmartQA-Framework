package com.qa.demo.qa.config.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;

@Repository
public class AuditEventRepository extends AssistantStoreSupport {

    private static final Logger log = LoggerFactory.getLogger(AuditEventRepository.class);
    private static volatile boolean askTraceTableReady = false;

    private final ObjectMapper objectMapper;

    public AuditEventRepository(QaAssistantProperties properties, ObjectMapper objectMapper) {
        super(properties);
        this.objectMapper = objectMapper;
    }

    public void append(String eventType, String turnId, String scope, Map<String, Object> payload) {
        if (!properties.isAuditMysqlEnabled()) {
            return;
        }
        String sql = """
                INSERT INTO qa_audit_event (event_type, turn_id, scope, payload_json)
                VALUES (?, ?, ?, CAST(? AS JSON))
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, eventType);
            ps.setString(2, turnId);
            ps.setString(3, scopeOrDefault(scope));
            ps.setString(4, objectMapper.writeValueAsString(payload));
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("[AuditEvent] append failed type={}: {}", eventType, e.getMessage());
        }
    }

    public void appendAskTrace(Map<String, Object> payload) {
        if (!properties.isAuditMysqlEnabled() || payload == null || payload.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO qa_ask_trace (
                    turn_id, conversation_id, scope, question, intent, query_type, retrieval_source,
                    route, can_answer, answer_gate_reject_reason, evidence_count, knowledge_deposit_triggered, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                """;
        try (Connection conn = openConnection()) {
            ensureAskTraceTable(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, stringValue(payload, "turnId"));
                ps.setString(2, stringValue(payload, "conversationId"));
                ps.setString(3, scopeOrDefault(stringValue(payload, "scope")));
                ps.setString(4, stringValue(payload, "question"));
                ps.setString(5, stringValue(payload, "intent"));
                ps.setString(6, stringValue(payload, "queryType"));
                ps.setString(7, stringValue(payload, "retrievalSource"));
                ps.setString(8, stringValue(payload, "route"));
                ps.setBoolean(9, booleanValue(payload, "canAnswer"));
                ps.setString(10, stringValue(payload, "answerGateRejectReason"));
                ps.setInt(11, intValue(payload, "evidence"));
                ps.setBoolean(12, booleanValue(payload, "knowledgeDepositTriggered"));
                ps.setString(13, objectMapper.writeValueAsString(payload));
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.debug("[AuditEvent] append ask trace failed: {}", e.getMessage());
        }
    }

    private static void ensureAskTraceTable(Connection conn) throws Exception {
        if (askTraceTableReady) {
            return;
        }
        synchronized (AuditEventRepository.class) {
            if (askTraceTableReady) {
                return;
            }
            String ddl = """
                    CREATE TABLE IF NOT EXISTS qa_ask_trace (
                      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                      turn_id VARCHAR(64) DEFAULT NULL,
                      conversation_id VARCHAR(64) DEFAULT NULL,
                      scope VARCHAR(64) DEFAULT NULL,
                      question VARCHAR(1024) DEFAULT NULL,
                      intent VARCHAR(64) DEFAULT NULL,
                      query_type VARCHAR(64) DEFAULT NULL,
                      retrieval_source VARCHAR(128) DEFAULT NULL,
                      route VARCHAR(128) DEFAULT NULL,
                      can_answer TINYINT(1) NOT NULL DEFAULT 0,
                      answer_gate_reject_reason VARCHAR(128) DEFAULT NULL,
                      evidence_count INT NOT NULL DEFAULT 0,
                      knowledge_deposit_triggered TINYINT(1) NOT NULL DEFAULT 0,
                      payload_json JSON NOT NULL,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      KEY idx_turn_id (turn_id),
                      KEY idx_conversation_id (conversation_id),
                      KEY idx_query_type_created (query_type, created_at),
                      KEY idx_reject_reason_created (answer_gate_reject_reason, created_at),
                      KEY idx_can_answer_created (can_answer, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """;
            try (Statement st = conn.createStatement()) {
                st.execute(ddl);
            }
            askTraceTableReady = true;
        }
    }

    private static String stringValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean booleanValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static int intValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof java.util.Collection<?> list) {
            return list.size();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return 0;
        }
    }
}
