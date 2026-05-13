package com.qa.demo.qa.sedimentation;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.response.QaLogService;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * 用户反馈：保留 jsonl 日志，并在 MySQL 可用时写入 {@code qa_user_feedback}。
 */
@Service
public class FeedbackPersistenceService {

    private static final String TABLE = "qa_user_feedback";

    private final QaAssistantProperties properties;
    private final QaLogService qaLogService;

    public FeedbackPersistenceService(QaAssistantProperties properties, QaLogService qaLogService) {
        this.properties = properties;
        this.qaLogService = qaLogService;
    }

    public Map<String, Object> recordFeedback(String turnId, boolean useful, String comment) {
        Map<String, Object> event = qaLogService.buildFeedbackEvent(turnId, useful, comment);
        qaLogService.appendFeedbackEvent(event);
        if (properties.isMysqlEnabled()) {
            persistToMysql(turnId, useful, comment);
        }
        return Map.of(
                "ok", true,
                "message", "feedback recorded",
                "turnId", turnId,
                "mysqlPersisted", properties.isMysqlEnabled(),
                "timestamp", event.get("timestamp")
        );
    }

    private void persistToMysql(String turnId, boolean useful, String comment) {
        String feedbackId = UUID.randomUUID().toString();
        String qualified = "`" + properties.getMysqlSchema() + "`.`" + TABLE + "`";
        String createSql = """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    feedback_id VARCHAR(64) NOT NULL,
                    turn_id VARCHAR(64) NOT NULL,
                    useful TINYINT(1) NOT NULL,
                    comment VARCHAR(2000) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_feedback_id (feedback_id),
                    KEY idx_turn_id (turn_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(qualified);
        String insertSql = """
                INSERT INTO %s (feedback_id, turn_id, useful, comment)
                VALUES (?, ?, ?, ?)
                """.formatted(qualified);
        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword());
             Statement st = connection.createStatement()) {
            st.execute(createSql);
            try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                ps.setString(1, feedbackId);
                ps.setString(2, turnId == null ? "" : turnId.trim());
                ps.setInt(3, useful ? 1 : 0);
                ps.setString(4, comment == null ? null : truncate(comment, 2000));
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
            // 不因 MySQL 失败影响已写入的 jsonl
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
