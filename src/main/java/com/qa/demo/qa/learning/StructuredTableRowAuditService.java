package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 对指定业务表做行数统计，与 {@link QaAssistantProperties#getMaxStructuredIngestRows()} 比对（结构化接入前置校验）。
 */
@Service
public class StructuredTableRowAuditService {

    private final QaAssistantProperties properties;

    public StructuredTableRowAuditService(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public record TableRowAudit(String table, long rowCount, int limit, boolean withinLimit) {
    }

    public List<TableRowAudit> auditTables(List<String> tables) {
        int limit = Math.max(1, properties.getMaxStructuredIngestRows());
        List<TableRowAudit> out = new ArrayList<>();
        if (!properties.isMysqlEnabled() || tables == null) {
            return out;
        }
        String schema = properties.getMysqlSchema();
        for (String raw : tables) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String table = raw.trim();
            if (!isSafeIdentifier(table) || table.startsWith("qa_")) {
                out.add(new TableRowAudit(table, -1, limit, false));
                continue;
            }
            long count = countRows(schema, table);
            out.add(new TableRowAudit(table, count, limit, count >= 0 && count <= limit));
        }
        return out;
    }

    private long countRows(String schema, String table) {
        String sql = "SELECT COUNT(*) AS c FROM `" + schema.replace("`", "") + "`.`" + table.replace("`", "") + "`";
        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword());
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong("c");
            }
        } catch (Exception ignored) {
            return -1L;
        }
        return -1L;
    }

    private static boolean isSafeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+");
    }
}
