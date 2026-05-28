package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 探测业务库锚点表（默认 company）相对增量水位是否有更新行。
 */
@Service
public class KnowledgeSyncChangeDetector {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSyncChangeDetector.class);
    private static final DateTimeFormatter SINCE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> WATERMARK_COLUMNS = List.of(
            "updated_at",
            "update_time",
            "gmt_modified",
            "modify_time",
            "modifytime",
            "modifyTime",
            "gmt_modify",
            "last_modified",
            "last_modify_time"
    );

    private final QaAssistantProperties properties;
    private final EnterpriseKnowledgeSyncService knowledgeSyncService;
    private volatile ChangeProbe lastProbe;

    public KnowledgeSyncChangeDetector(
            QaAssistantProperties properties,
            EnterpriseKnowledgeSyncService knowledgeSyncService
    ) {
        this.properties = properties;
        this.knowledgeSyncService = knowledgeSyncService;
    }

    public Optional<ChangeProbe> lastProbe() {
        ChangeProbe probe = lastProbe;
        return probe != null ? Optional.of(probe) : Optional.empty();
    }

    /**
     * 查询自当前增量 since 以来是否有锚点表变更。
     */
    public ChangeProbe probe() {
        String since = knowledgeSyncService.currentIncrementalSince();
        String table = properties.getMysqlPersonRoleCompanyTable();
        if (table == null || table.isBlank()) {
            table = "company";
        }
        try (Connection conn = openBusinessConnection()) {
            String schema = conn.getCatalog();
            if (schema == null || schema.isBlank()) {
                schema = schemaFromJdbcUrl(properties.getBusinessMysqlUrl());
            }
            Optional<String> watermarkCol = findWatermarkColumn(conn, schema, table);
            if (watermarkCol.isEmpty()) {
                ChangeProbe probe = ChangeProbe.unavailable(
                        since, table, null,
                        "锚点表 " + schema + "." + table
                                + " 无水位列（可配置 qa.assistant.knowledge-sync-watermark-column=modifytime）");
                lastProbe = probe;
                return probe;
            }
            boolean hasDeleteFlag = columnExists(conn, schema, table, "deleteflag");
            String sql = """
                    SELECT COUNT(*) AS cnt, MAX(`%s`) AS max_ts
                    FROM `%s`.`%s`
                    WHERE `%s` > ?
                    """.formatted(watermarkCol.get(), schema, table, watermarkCol.get())
                    + (hasDeleteFlag ? " AND deleteflag = 0" : "");
            long count = 0;
            LocalDateTime maxUpdatedAt = null;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getLong("cnt");
                        Timestamp maxTs = rs.getTimestamp("max_ts");
                        if (maxTs != null) {
                            maxUpdatedAt = maxTs.toLocalDateTime();
                        }
                    }
                }
            }
            ChangeProbe probe = new ChangeProbe(
                    true,
                    since,
                    count,
                    maxUpdatedAt,
                    schema + "." + table,
                    watermarkCol.get(),
                    count > 0 ? "检测到变更" : "无变更"
            );
            lastProbe = probe;
            return probe;
        } catch (SQLException e) {
            log.warn("[KnowledgeSyncChange] probe failed: {}", e.getMessage());
            ChangeProbe probe = ChangeProbe.unavailable(since, table, null, e.getMessage());
            lastProbe = probe;
            return probe;
        }
    }

    private Connection openBusinessConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }

    private Optional<String> findWatermarkColumn(Connection conn, String schema, String table)
            throws SQLException {
        String configured = properties.getKnowledgeSyncWatermarkColumn();
        if (configured != null && !configured.isBlank()) {
            Optional<String> resolved = resolveColumnIgnoreCase(conn, schema, table, configured.trim());
            if (resolved.isPresent()) {
                return resolved;
            }
            log.warn("[KnowledgeSyncChange] configured watermark column '{}' not found on {}.{}",
                    configured, schema, table);
        }
        for (String candidate : WATERMARK_COLUMNS) {
            Optional<String> resolved = resolveColumnIgnoreCase(conn, schema, table, candidate);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> resolveColumnIgnoreCase(
            Connection conn,
            String schema,
            String table,
            String column
    ) throws SQLException {
        String sql = """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND LOWER(column_name) = LOWER(?)
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("column_name"));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean columnExists(Connection conn, String schema, String table, String column)
            throws SQLException {
        String sql = """
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ? AND column_name = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String schemaFromJdbcUrl(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) {
            return "tdcomp";
        }
        String rest = url.substring("jdbc:mysql://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return "tdcomp";
        }
        String dbPart = rest.substring(slash + 1);
        int q = dbPart.indexOf('?');
        String database = q >= 0 ? dbPart.substring(0, q) : dbPart;
        return database.isBlank() ? "tdcomp" : database;
    }

    public record ChangeProbe(
            boolean available,
            String since,
            long changedCount,
            LocalDateTime maxUpdatedAt,
            String anchorTable,
            String watermarkColumn,
            String message
    ) {
        public boolean changed() {
            return available && changedCount > 0;
        }

        public static ChangeProbe unavailable(
                String since,
                String anchorTable,
                String watermarkColumn,
                String message
        ) {
            return new ChangeProbe(false, since, 0, null, anchorTable, watermarkColumn, message);
        }

        public java.util.Map<String, Object> toMap() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("available", available);
            m.put("since", since);
            m.put("changedCount", changedCount);
            m.put("changed", changed());
            m.put("maxUpdatedAt", maxUpdatedAt != null ? maxUpdatedAt.format(SINCE_FMT) : null);
            m.put("anchorTable", anchorTable);
            m.put("watermarkColumn", watermarkColumn);
            m.put("message", message);
            m.put("probedAt", LocalDateTime.now().format(SINCE_FMT));
            return m;
        }
    }
}
