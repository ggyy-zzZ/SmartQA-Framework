package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MysqlContextService {

    private static final Pattern EN_TOKEN = Pattern.compile("[a-zA-Z0-9_]{3,}");
    private static final Pattern ZH_TOKEN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
    private static final Set<String> TEXT_TYPES = Set.of(
            "char", "varchar", "text", "tinytext", "mediumtext", "longtext"
    );
    private static final int MAX_SCAN_TABLES = 24;

    private final QaAssistantProperties properties;

    public MysqlContextService(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK) {
        if (!properties.isMysqlEnabled()) {
            return List.of();
        }
        List<String> tokens = extractTokens(question);
        if (tokens.isEmpty()) {
            return List.of();
        }
        int limitedTopK = Math.max(1, Math.min(topK, Math.max(1, properties.getMysqlTopK())));
        try (Connection connection = openConnection()) {
            List<TableSpec> searchable = loadSearchableTables(connection);
            if (searchable.isEmpty()) {
                return List.of();
            }
            List<TableSpec> selectedTables = prioritizeTables(searchable, tokens, question);
            List<ScoredChunk> chunks = new ArrayList<>();
            for (TableSpec table : selectedTables) {
                List<ScoredChunk> hits = queryTable(connection, table, tokens);
                chunks.addAll(hits);
            }
            if (chunks.isEmpty()) {
                return List.of();
            }
            chunks.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
            List<ContextChunk> result = new ArrayList<>();
            for (int i = 0; i < Math.min(limitedTopK, chunks.size()); i++) {
                result.add(chunks.get(i).chunk());
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword()
        );
        int seconds = Math.max(1, properties.getMysqlQueryTimeoutSeconds());
        try (Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(seconds);
        }
        return conn;
    }

    private List<TableSpec> loadSearchableTables(Connection connection) throws SQLException {
        String sql = """
                SELECT table_name, column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = ?
                ORDER BY table_name, ordinal_position
                """;
        Map<String, List<ColumnMeta>> grouped = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, properties.getMysqlSchema());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    String columnName = rs.getString("column_name");
                    String dataType = rs.getString("data_type");
                    if (!isSafeIdentifier(tableName) || !isSafeIdentifier(columnName)) {
                        continue;
                    }
                    // 系统表由 ActiveLearningService 等专用路径访问，避免与行级扫表重复
                    if (tableName.startsWith("qa_")) {
                        continue;
                    }
                    grouped.computeIfAbsent(tableName, k -> new ArrayList<>())
                            .add(new ColumnMeta(columnName, dataType == null ? "" : dataType.toLowerCase(Locale.ROOT)));
                }
            }
        }
        List<TableSpec> result = new ArrayList<>();
        for (Map.Entry<String, List<ColumnMeta>> entry : grouped.entrySet()) {
            List<String> textColumns = entry.getValue().stream()
                    .filter(c -> TEXT_TYPES.contains(c.dataType()))
                    .map(ColumnMeta::name)
                    .toList();
            if (textColumns.isEmpty()) {
                continue;
            }
            List<String> selected = prioritizeColumns(textColumns);
            if (!selected.isEmpty()) {
                result.add(new TableSpec(entry.getKey(), selected));
            }
        }
        return result;
    }

    private List<String> prioritizeColumns(List<String> columns) {
        List<String> sorted = new ArrayList<>(columns);
        sorted.sort(Comparator.comparingInt(this::columnPriority).thenComparing(c -> c.toLowerCase(Locale.ROOT)));
        int max = Math.min(sorted.size(), 10);
        return sorted.subList(0, max);
    }

    private int columnPriority(String column) {
        String c = column.toLowerCase(Locale.ROOT);
        if (c.contains("name") || c.contains("company") || c.contains("ent") || c.contains("corp")) {
            return 0;
        }
        if (c.contains("status") || c.contains("state") || c.contains("type")) {
            return 1;
        }
        if (c.contains("address") || c.contains("scope") || c.contains("business")) {
            return 2;
        }
        if (c.contains("person") || c.contains("share") || c.contains("holder") || c.contains("license")) {
            return 3;
        }
        return 4;
    }

    private List<TableSpec> prioritizeTables(List<TableSpec> tables, List<String> tokens, String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<ScoredTable> scored = new ArrayList<>();
        for (TableSpec table : tables) {
            String name = table.name().toLowerCase(Locale.ROOT);
            double score = 0.0;
            if (name.contains("company") || name.contains("enterprise") || name.contains("corp") || name.contains("ent")) {
                score += 2.5;
            }
            if ((q.contains("银行") || q.contains("bank") || q.contains("账户") || q.contains("account"))
                    && name.contains("bank")) {
                score += 8.0;
            }
            if ((q.contains("证照") || q.contains("certificate") || q.contains("许可证")) && name.contains("cert")) {
                score += 6.0;
            }
            if ((q.contains("员工") || q.contains("employee") || q.contains("人名") || q.contains("人员")) && name.contains("employee")) {
                score += 6.0;
            }
            if ((q.contains("状态") || q.contains("status")) && (name.contains("company") || name.contains("base"))) {
                score += 4.0;
            }
            if (name.contains("base") || name.contains("info") || name.contains("main")) {
                score += 1.0;
            }
            for (String token : tokens) {
                if (token.length() >= 2 && name.contains(token.toLowerCase(Locale.ROOT))) {
                    score += 1.2;
                }
            }
            scored.add(new ScoredTable(table, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredTable::score).reversed());
        int limit = Math.min(MAX_SCAN_TABLES, scored.size());
        List<TableSpec> selected = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            selected.add(scored.get(i).table());
        }
        return selected;
    }

    private List<ScoredChunk> queryTable(Connection connection, TableSpec table, List<String> tokens) {
        String whereClause = buildWhereClause(table.textColumns(), tokens.size());
        if (whereClause.isBlank()) {
            return List.of();
        }
        String sql = "SELECT * FROM `" + properties.getMysqlSchema() + "`.`" + table.name() + "` WHERE "
                + whereClause + " LIMIT ?";
        List<ScoredChunk> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            for (String token : tokens) {
                ps.setString(idx++, "%" + token + "%");
            }
            ps.setInt(idx, Math.max(1, properties.getMysqlPerTableLimit()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = readRow(rs);
                    if (row.isEmpty()) {
                        continue;
                    }
                    String textBlob = flattenRow(row);
                    int hitCount = countTokenHits(textBlob, tokens);
                    if (hitCount <= 0) {
                        continue;
                    }
                    String companyId = pickValue(row, "company_id", "companyid", "ent_id", "corp_id", "id");
                    String companyName = pickValue(row, "company_name", "companyname", "ent_name", "corp_name", "name");
                    if (companyId.isBlank()) {
                        companyId = "unknown";
                    }
                    if (companyName.isBlank()) {
                        companyName = "unknown";
                    }
                    String snippet = buildSnippet(row);
                    double score = 5.0 + hitCount * 1.4;
                    ContextChunk chunk = new ContextChunk(
                            companyId,
                            companyName,
                            "mysql_table:" + table.name(),
                            snippet,
                            score,
                            "mysql-" + table.name()
                    );
                    result.add(new ScoredChunk(chunk, score));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return result;
    }

    private String buildWhereClause(List<String> columns, int tokenCount) {
        if (columns.isEmpty() || tokenCount <= 0) {
            return "";
        }
        String concat = "CONCAT_WS(' '," + columns.stream()
                .map(c -> "IFNULL(`" + c + "`,'')")
                .reduce((a, b) -> a + "," + b)
                .orElse("") + ")";
        List<String> likes = new ArrayList<>();
        for (int i = 0; i < tokenCount; i++) {
            likes.add(concat + " LIKE ?");
        }
        return "(" + String.join(" OR ", likes) + ")";
    }

    private Map<String, String> readRow(ResultSet rs) throws SQLException {
        Map<String, String> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String column = meta.getColumnLabel(i);
            Object value = rs.getObject(i);
            if (column == null || column.isBlank() || value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                row.put(column, text);
            }
        }
        return row;
    }

    private String flattenRow(Map<String, String> row) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return sb.toString();
    }

    private int countTokenHits(String text, List<String> tokens) {
        int hits = 0;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private String pickValue(Map<String, String> row, String... candidates) {
        for (String candidate : candidates) {
            String found = pickByContains(row, candidate);
            if (!found.isBlank()) {
                return found;
            }
        }
        return "";
    }

    private String pickByContains(Map<String, String> row, String marker) {
        String normalized = marker.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains(normalized)) {
                return truncate(entry.getValue(), 80);
            }
        }
        return "";
    }

    private String buildSnippet(Map<String, String> row) {
        List<String> pairs = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, String> entry : row.entrySet()) {
            pairs.add(entry.getKey() + "=" + truncate(entry.getValue(), 60));
            count++;
            if (count >= 7) {
                break;
            }
        }
        return String.join("; ", pairs);
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    private List<String> extractTokens(String question) {
        Set<String> set = new LinkedHashSet<>();
        Matcher zhMatcher = ZH_TOKEN.matcher(question);
        while (zhMatcher.find()) {
            String token = zhMatcher.group().trim();
            if (token.length() >= 2 && token.length() <= 20) {
                set.add(token);
            }
        }
        Matcher enMatcher = EN_TOKEN.matcher(question.toLowerCase(Locale.ROOT));
        while (enMatcher.find()) {
            String token = enMatcher.group().trim();
            if (token.length() >= 3 && token.length() <= 32) {
                set.add(token);
            }
        }
        List<String> result = new ArrayList<>(set);
        if (result.size() > 5) {
            return result.subList(0, 5);
        }
        return result;
    }

    private boolean isSafeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+");
    }

    private record ColumnMeta(String name, String dataType) {
    }

    private record TableSpec(String name, List<String> textColumns) {
    }

    private record ScoredTable(TableSpec table, double score) {
    }

    private record ScoredChunk(ContextChunk chunk, double score) {
    }
}
