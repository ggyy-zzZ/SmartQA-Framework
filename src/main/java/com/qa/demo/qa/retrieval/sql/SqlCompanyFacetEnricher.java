package com.qa.demo.qa.retrieval.sql;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 companyId 边界结果，从业务库 company 表补充公司维度字段（状态、有效期等）。
 */
@Component
public class SqlCompanyFacetEnricher {

    private static final Set<String> STATUS_MARKERS = Set.of(
            "status", "state", "biz_status", "company_status", "operate_status", "经营状态"
    );
    private static final Set<String> VALIDITY_MARKERS = Set.of(
            "valid", "expiry", "expire", "deadline", "term", "period", "effective", "start", "end", "date"
    );

    private final QaAssistantProperties properties;

    public SqlCompanyFacetEnricher(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public List<ContextChunk> enrichFromBoundaries(String question, List<ContextChunk> boundaries, int limit) {
        if (boundaries == null || boundaries.isEmpty()) {
            return List.of();
        }
        List<String> companyIds = extractCompanyIds(boundaries);
        if (companyIds.isEmpty()) {
            return List.of();
        }
        try (Connection connection = DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        )) {
            String table = sanitizeIdentifier(properties.getMysqlPersonRoleCompanyTable(), "company");
            List<String> columns = loadColumns(connection, table);
            if (columns.isEmpty()) {
                return List.of();
            }
            String idCol = pickColumn(columns, List.of("company_id", "id", "companyid"), null);
            if (idCol == null) {
                return List.of();
            }
            String nameCol = pickColumn(columns, List.of("company_name", "name", "companyname", "ent_name"), "");
            List<String> facetCols = chooseFacetColumns(columns, question);
            if (facetCols.isEmpty()) {
                return List.of();
            }
            return queryRows(connection, table, idCol, nameCol, facetCols, companyIds, limit);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<ContextChunk> queryRows(
            Connection connection,
            String table,
            String idCol,
            String nameCol,
            List<String> facetCols,
            List<String> companyIds,
            int limit
    ) throws Exception {
        int top = Math.max(1, Math.min(limit, Math.max(8, companyIds.size() * 2)));
        String placeholders = buildPlaceholders(companyIds.size());
        String selectedCols = buildSelectedColumns(idCol, nameCol, facetCols);
        String sql = "SELECT " + selectedCols + " FROM `" + table + "` WHERE `" + idCol + "` IN (" + placeholders + ") LIMIT ?";
        List<ContextChunk> chunks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            for (String companyId : companyIds) {
                ps.setString(idx++, companyId);
            }
            ps.setInt(idx, top);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String companyId = safe(rs.getString(idCol));
                    String companyName = nameCol.isBlank() ? "" : safe(rs.getString(nameCol));
                    String snippet = buildSnippet(rs, facetCols);
                    if (snippet.isBlank()) {
                        continue;
                    }
                    chunks.add(ContextChunk.ofCompany(
                            companyId,
                            companyName,
                            "company_facet",
                            snippet,
                            23.0,
                            "mysql-company-facet"
                    ));
                }
            }
        }
        return chunks;
    }

    private static String buildSelectedColumns(String idCol, String nameCol, List<String> facetCols) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        selected.add("`" + idCol + "`");
        if (nameCol != null && !nameCol.isBlank()) {
            selected.add("`" + nameCol + "`");
        }
        for (String col : facetCols) {
            selected.add("`" + col + "`");
        }
        return String.join(",", selected);
    }

    private static List<String> loadColumns(Connection connection, String table) throws Exception {
        String sql = """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                ORDER BY ordinal_position
                """;
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = safe(rs.getString("column_name"));
                    if (!col.isBlank() && isSafeIdentifier(col)) {
                        columns.add(col);
                    }
                }
            }
        }
        return columns;
    }

    private static List<String> chooseFacetColumns(List<String> columns, String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean asksValidity = containsAny(q, List.of("有效期", "有效时间", "到期", "截止", "失效", "起止", "开始", "结束"));
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String col : columns) {
            String lower = col.toLowerCase(Locale.ROOT);
            if (matchesAnyMarker(lower, STATUS_MARKERS)) {
                selected.add(col);
            }
            if (asksValidity && matchesAnyMarker(lower, VALIDITY_MARKERS)) {
                selected.add(col);
            }
        }
        return new ArrayList<>(selected);
    }

    private static boolean matchesAnyMarker(String value, Set<String> markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String buildSnippet(ResultSet rs, List<String> columns) throws Exception {
        List<String> pairs = new ArrayList<>();
        for (String col : columns) {
            String value = safe(rs.getString(col));
            if (value.isBlank()) {
                continue;
            }
            pairs.add(col + "=" + value);
            if (pairs.size() >= 8) {
                break;
            }
        }
        return String.join("; ", pairs);
    }

    private static List<String> extractCompanyIds(List<ContextChunk> boundaries) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ContextChunk chunk : boundaries) {
            if (chunk == null) {
                continue;
            }
            String id = chunk.anchorId() == null ? "" : chunk.anchorId().trim();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private static String pickColumn(List<String> columns, List<String> candidates, String fallback) {
        for (String candidate : candidates) {
            for (String col : columns) {
                if (col.equalsIgnoreCase(candidate)) {
                    return col;
                }
            }
        }
        return fallback;
    }

    private static String buildPlaceholders(int size) {
        int n = Math.max(1, size);
        List<String> marks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            marks.add("?");
        }
        return String.join(",", marks);
    }

    private static String sanitizeIdentifier(String value, String fallback) {
        if (!isSafeIdentifier(value)) {
            return fallback;
        }
        return value;
    }

    private static boolean isSafeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+");
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
