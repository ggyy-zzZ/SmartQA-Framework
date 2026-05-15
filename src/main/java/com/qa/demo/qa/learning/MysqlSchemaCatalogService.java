package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 {@link QaAssistantProperties} 中已配置的 MySQL，只读查询 {@code information_schema}，
 * 生成《数据库结构说明》Markdown；供 HTTP 展示或 {@link ActiveLearningService#learn} 持久化。
 *
 * @see com.qa.demo.qa.web.QaController 暴露 {@code POST /qa/mysql/schema-catalog}
 */
@Service
public class MysqlSchemaCatalogService {

    private final QaAssistantProperties properties;

    public MysqlSchemaCatalogService(QaAssistantProperties properties) {
        this.properties = properties;
    }

    /** 是否具备导出前置条件（启用且已配置 schema），供 HTTP 层快速返回。 */
    public boolean canExport() {
        return properties.isMysqlEnabled()
                && properties.getMysqlSchema() != null
                && !properties.getMysqlSchema().isBlank();
    }

    public record SchemaCatalogExport(
            String schema,
            int tableCount,
            String markdown,
            boolean markdownTruncated,
            int markdownCharCount
    ) {
    }

    /**
     * 导出目录；失败时抛出异常由控制器转换为 JSON 错误。
     */
    public SchemaCatalogExport exportCatalog() throws Exception {
        if (!canExport()) {
            throw new IllegalStateException("MySQL 未启用或未配置 qa.assistant.mysql-schema。");
        }
        String schema = properties.getMysqlSchema();
        int maxTables = Math.max(1, Math.min(500, properties.getMaxSchemaExportTables()));
        int maxChars = Math.max(4096, properties.getMaxSchemaExportChars());

        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword())) {
            List<String> tables = listBusinessTables(connection, schema, maxTables);
            Map<String, String> tableComments = loadTableComments(connection, schema, tables);
            StringBuilder md = new StringBuilder();
            md.append("# 数据库结构说明（只读元数据）\n\n");
            md.append("- **来源**：`information_schema`，仅结构，不包含业务行数据。\n");
            md.append("- **Schema**：`").append(escapeMdInline(schema)).append("`。\n");
            md.append("- **表数量上限**：本次最多列出 ").append(maxTables).append(" 张业务表（`qa_` 前缀系统表已排除）。\n");
            md.append("- **实际导出表数**：").append(tables.size()).append("。\n\n");
            md.append("## 表清单\n\n");
            for (String t : tables) {
                md.append("- `").append(escapeMdInline(t)).append("`");
                String c = tableComments.get(t);
                if (c != null && !c.isBlank()) {
                    md.append(" — ").append(escapeMdInline(c));
                }
                md.append("\n");
            }
            md.append("\n---\n\n");
            for (String table : tables) {
                appendTableSection(connection, schema, table, md);
            }
            String full = md.toString();
            boolean truncated = full.length() > maxChars;
            String out = truncated ? full.substring(0, maxChars) + "\n\n… **以下已截断**（超过 qa.assistant.max-schema-export-chars=" + maxChars + "）。\n" : full;
            return new SchemaCatalogExport(schema, tables.size(), out, truncated, out.length());
        }
    }

    private List<String> listBusinessTables(Connection connection, String schema, int maxTables) throws Exception {
        String sql = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """;
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < maxTables) {
                    String name = rs.getString("table_name");
                    if (name == null || !isSafeIdentifier(name) || name.startsWith("qa_")) {
                        continue;
                    }
                    out.add(name);
                }
            }
        }
        return out;
    }

    private Map<String, String> loadTableComments(Connection connection, String schema, List<String> tables)
            throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        if (tables.isEmpty()) {
            return map;
        }
        String sql = """
                SELECT table_name, table_comment
                FROM information_schema.tables
                WHERE table_schema = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString("table_name");
                    if (t != null && tables.contains(t)) {
                        map.put(t, rs.getString("table_comment"));
                    }
                }
            }
        }
        return map;
    }

    private void appendTableSection(Connection connection, String schema, String table, StringBuilder md)
            throws Exception {
        md.append("## 表 `").append(escapeMdInline(table)).append("`\n\n");
        md.append("| column | data_type | nullable | column_key |\n");
        md.append("|--------|-----------|----------|------------|\n");
        String sql = """
                SELECT column_name, data_type, is_nullable, column_key
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                ORDER BY ordinal_position
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString("column_name");
                    String dtype = rs.getString("data_type");
                    String nullable = rs.getString("is_nullable");
                    String colKey = rs.getString("column_key");
                    if (col == null || !isSafeIdentifier(col)) {
                        continue;
                    }
                    md.append("| ")
                            .append(escapeMdCell(col)).append(" | ")
                            .append(escapeMdCell(dtype == null ? "" : dtype.toLowerCase(Locale.ROOT))).append(" | ")
                            .append(escapeMdCell(nullable == null ? "" : nullable)).append(" | ")
                            .append(escapeMdCell(colKey == null ? "" : colKey))
                            .append(" |\n");
                }
            }
        }
        md.append("\n");
    }

    private static boolean isSafeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+");
    }

    private static String escapeMdInline(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("`", "'");
    }

    private static String escapeMdCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "/").replace("\r", " ").replace("\n", " ").replace("\t", " ");
    }

    /**
     * 动态连接参数：允许外部传入数据库连接信息（不依赖预配置）。
     */
    public record DynamicConnection(
            String host,
            int port,
            String database,
            String username,
            String password
    ) {
        public String toJdbcUrl() {
            return String.format(
                    "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
                    host, port, database
            );
        }
    }

    /**
     * 使用动态连接参数导出 Schema 目录。
     */
    public SchemaCatalogExport exportCatalogWithConnection(DynamicConnection conn, int maxTables, int maxChars)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                conn.toJdbcUrl(),
                conn.username(),
                conn.password())) {
            String schema = conn.database();
            List<String> tables = listBusinessTables(connection, schema, maxTables);
            Map<String, String> tableComments = loadTableComments(connection, schema, tables);
            StringBuilder md = new StringBuilder();
            md.append("# 数据库结构说明（只读元数据）\n\n");
            md.append("- **来源**：`information_schema`，仅结构，不包含业务行数据。\n");
            md.append("- **Schema**：`").append(escapeMdInline(schema)).append("`。\n");
            md.append("- **连接方式**：动态连接\n");
            md.append("- **表数量上限**：本次最多列出 ").append(maxTables).append(" 张业务表（`qa_` 前缀系统表已排除）。\n");
            md.append("- **实际导出表数**：").append(tables.size()).append("。\n\n");
            md.append("## 表清单\n\n");
            for (String t : tables) {
                md.append("- `").append(escapeMdInline(t)).append("`");
                String c = tableComments.get(t);
                if (c != null && !c.isBlank()) {
                    md.append(" — ").append(escapeMdInline(c));
                }
                md.append("\n");
            }
            md.append("\n---\n\n");
            for (String table : tables) {
                appendTableSection(connection, schema, table, md);
            }
            String full = md.toString();
            boolean truncated = full.length() > maxChars;
            String out = truncated ? full.substring(0, maxChars) + "\n\n… **以下已截断**（超过 " + maxChars + " 字符）。\n" : full;
            return new SchemaCatalogExport(schema, tables.size(), out, truncated, out.length());
        }
    }

    /**
     * 使用动态连接导出目录；表数与字符上限与 {@link QaAssistantProperties} 中 export 配置一致。
     */
    public SchemaCatalogExport exportCatalogWithConnection(DynamicConnection conn) throws Exception {
        int maxTables = Math.max(1, Math.min(500, properties.getMaxSchemaExportTables()));
        int maxChars = Math.max(4096, properties.getMaxSchemaExportChars());
        return exportCatalogWithConnection(conn, maxTables, maxChars);
    }

    /**
     * 获取数据库表关联关系（外键）。
     * 查询 information_schema.KEY_COLUMN_USAGE 返回表间外键关系。
     */
    public List<TableRelationship> getTableRelationships(Connection connection, String schema) throws Exception {
        List<TableRelationship> relationships = new ArrayList<>();
        String sql = """
                SELECT
                    kcu.table_name AS from_table,
                    kcu.column_name AS from_column,
                    kcu.referenced_table_name AS to_table,
                    kcu.referenced_column_name AS to_column,
                    kcu.constraint_name AS constraint_name
                FROM information_schema.KEY_COLUMN_USAGE kcu
                WHERE kcu.table_schema = ?
                  AND kcu.referenced_table_name IS NOT NULL
                  AND kcu.referenced_table_name != kcu.table_name
                ORDER BY kcu.table_name, kcu.column_name
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    relationships.add(new TableRelationship(
                            rs.getString("from_table"),
                            rs.getString("from_column"),
                            rs.getString("to_table"),
                            rs.getString("to_column"),
                            rs.getString("constraint_name")
                    ));
                }
            }
        }
        return relationships;
    }

    /**
     * 使用动态连接获取表关联关系。
     */
    public List<TableRelationship> getTableRelationshipsWithConnection(DynamicConnection conn) throws Exception {
        try (Connection connection = DriverManager.getConnection(conn.toJdbcUrl(), conn.username(), conn.password())) {
            return getTableRelationships(connection, conn.database());
        }
    }

    /**
     * 读取表的样本数据（用于评估阶段）。
     * @param connection 数据库连接
     * @param schema schema 名称
     * @param table 表名
     * @param limit 最大返回行数
     */
    public List<Map<String, String>> readTableSampleData(Connection connection, String schema, String table, int limit)
            throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        if (!isSafeIdentifier(table) || !isSafeIdentifier(schema)) {
            throw new IllegalArgumentException("Invalid table or schema name");
        }
        String sql = "SELECT * FROM `" + schema + "`.`" + table + "` LIMIT " + limit;
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = rs.getMetaData().getColumnLabel(i);
                    String value = rs.getString(i);
                    row.put(columnName, value);
                }
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 使用动态连接读取表样本数据。
     */
    public List<Map<String, String>> readTableSampleDataWithConnection(DynamicConnection conn, String table, int limit)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(conn.toJdbcUrl(), conn.username(), conn.password())) {
            return readTableSampleData(connection, conn.database(), table, limit);
        }
    }

    /**
     * 读取表全量数据（用于沉淀阶段）。
     * @param connection 数据库连接
     * @param schema schema 名称
     * @param table 表名
     * @param offset 起始偏移
     * @param limit 最大返回行数
     */
    public List<Map<String, String>> readTableData(Connection connection, String schema, String table, long offset, int limit)
            throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        if (!isSafeIdentifier(table) || !isSafeIdentifier(schema)) {
            throw new IllegalArgumentException("Invalid table or schema name");
        }
        String sql = "SELECT * FROM `" + schema + "`.`" + table + "` LIMIT " + limit + " OFFSET " + offset;
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = rs.getMetaData().getColumnLabel(i);
                    String value = rs.getString(i);
                    row.put(columnName, value);
                }
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 使用动态连接读取表全量数据（分页）。
     */
    public List<Map<String, String>> readTableDataWithConnection(DynamicConnection conn, String table, long offset, int limit)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(conn.toJdbcUrl(), conn.username(), conn.password())) {
            return readTableData(connection, conn.database(), table, offset, limit);
        }
    }

    /**
     * 表关联关系记录
     */
    public record TableRelationship(
            String fromTable,
            String fromColumn,
            String toTable,
            String toColumn,
            String constraintName
    ) {
    }
}
