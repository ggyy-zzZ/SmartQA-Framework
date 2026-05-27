package com.qa.demo.qa.retrieval;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.config.CacheConfig;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.retrieval.sql.SqlPersonRoleRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SqlQueryService {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryService.class);
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)\\blimit\\s+\\d+");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```(?:sql|json)?\\s*(.*?)\\s*```");
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "insert", "update", "delete", "drop", "alter", "truncate", "create",
            "grant", "revoke", "replace", "merge", "call", "outfile", "load_file",
            "benchmark", "sleep"
    );
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;
    private final SqlPersonRoleRetriever personRoleRetriever;
    private final SqlTopKResolver sqlTopKResolver;

    public SqlQueryService(
            ObjectMapper objectMapper,
            QaAssistantProperties properties,
            SqlPersonRoleRetriever personRoleRetriever,
            SqlTopKResolver sqlTopKResolver
    ) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.personRoleRetriever = personRoleRetriever;
        this.sqlTopKResolver = sqlTopKResolver;
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK) {
        return retrieveTopChunks(question, RetrievalPlan.of(null, topK, topK));
    }

    public List<ContextChunk> retrieveTopChunks(String question, RetrievalPlan plan) {
        if (!properties.isMysqlEnabled()) {
            return List.of();
        }
        if (personRoleRetriever.skipForPlan(plan)) {
            return List.of();
        }
        int limitedTopK = Math.max(1, Math.min(sqlTopKResolver.resolve(question, plan), 20));
        String cacheKey = question + ":" + limitedTopK + ":" + planKey(plan);
        IntentDecision intent = plan != null ? plan.intent() : null;
        return retrieveTopChunksCached(cacheKey, question, limitedTopK, intent);
    }

    private static String planKey(RetrievalPlan plan) {
        if (plan == null || plan.intent() == null) {
            return "none";
        }
        IntentDecision i = plan.intent();
        return i.queryType() + "|" + i.personName() + "|" + i.roleFocus();
    }

    @Cacheable(value = CacheConfig.RETRIEVAL_CACHE, key = "#root.method.name + ':' + #key")
    public List<ContextChunk> retrieveTopChunksCached(String key, String question, int topK, IntentDecision intent) {
        int limitedTopK = Math.max(1, Math.min(topK, 20));
        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword())
        ) {
            List<ContextChunk> roleChunks = personRoleRetriever.retrieve(connection, question, intent, limitedTopK);
            if (!roleChunks.isEmpty()) {
                return roleChunks;
            }
            String schemaSummary = loadSchemaSummary(connection, properties.getMysqlSchema(), 18);
            if (schemaSummary.isBlank()) {
                return List.of();
            }
            String generatedSql = generateSql(question, schemaSummary, limitedTopK);
            String normalizedSql = normalizeAndValidateSql(generatedSql, limitedTopK);
            if (normalizedSql == null) {
                return List.of();
            }
            return executeSqlToChunks(connection, normalizedSql, limitedTopK);
        } catch (Exception e) {
            log.warn("retrieveTopChunksCached failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String loadSchemaSummary(Connection connection, String schema, int maxTables) {
        String sql = """
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE table_schema = '%s'
                ORDER BY table_name, ordinal_position
                """.formatted(schema.replace("'", "''"));
        Map<String, List<String>> tableColumns = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String tableName = safe(rs.getString("table_name"));
                String columnName = safe(rs.getString("column_name"));
                if (tableName.isBlank() || columnName.isBlank()) {
                    continue;
                }
                if (tableName.startsWith("qa_")) {
                    continue;
                }
                tableColumns.computeIfAbsent(tableName, k -> new ArrayList<>()).add(columnName);
            }
        } catch (Exception e) {
            log.debug("loadSchemaSummary failed: {}", e.getMessage());
            return "";
        }

        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, List<String>> entry : tableColumns.entrySet()) {
            if (count >= maxTables) {
                break;
            }
            List<String> cols = entry.getValue();
            int colLimit = Math.min(cols.size(), 30);
            builder.append("table=").append(entry.getKey()).append(", columns=");
            for (int i = 0; i < colLimit; i++) {
                if (i > 0) {
                    builder.append(",");
                }
                builder.append(cols.get(i));
            }
            builder.append("\n");
            count++;
        }
        return builder.toString();
    }

    private String generateSql(String question, String schemaSummary, int limit) throws Exception {
        String systemPrompt = KnowledgeAssistantPrompts.sqlGeneratorSystemPrompt(limit);
        String userPrompt = "schema=" + properties.getMysqlSchema() + "\n"
                + "question=" + question + "\n"
                + "tables:\n" + schemaSummary;
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "name", "MiniMax AI", "content", systemPrompt),
                        Map.of("role", "user", "name", "User", "content", userPrompt)
                )
        );
        String response = restClient.post()
                .uri(properties.getApiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        JsonNode root = objectMapper.readTree(response);
        JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
        String content = contentNode.isTextual() ? contentNode.asText().trim() : "";
        if (content.isBlank()) {
            content = root.path("reply").asText("");
        }
        content = stripCodeBlock(content);
        JsonNode decision = objectMapper.readTree(content);
        return decision.path("sql").asText("");
    }

    private String normalizeAndValidateSql(String sql, int limit) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String normalized = sql.replace("\n", " ").replace("\r", " ").trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (!isSafeSelectSql(normalized)) {
            return null;
        }
        if (!LIMIT_PATTERN.matcher(normalized).find()) {
            normalized = normalized + " LIMIT " + limit;
        }
        return normalized;
    }

    private boolean isSafeSelectSql(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        if (lower.contains(";") || lower.contains("--") || lower.contains("/*") || lower.contains("#")) {
            return false;
        }
        if (!(lower.startsWith("select ") || lower.startsWith("with "))) {
            return false;
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (lower.contains(token + " ")) {
                return false;
            }
        }
        return true;
    }

    private List<ContextChunk> executeSqlToChunks(Connection connection, String sql, int topK) {
        List<ContextChunk> chunks = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(topK);
            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int columnCount = md.getColumnCount();
                int rowIndex = 0;
                while (rs.next() && rowIndex < topK) {
                    rowIndex++;
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String column = safe(md.getColumnLabel(i));
                        if (column.isBlank()) {
                            column = "col" + i;
                        }
                        row.put(column, safe(rs.getString(i)));
                    }
                    String companyId = pickByColumns(row, "company_id", "companyid", "id");
                    String companyName = pickByColumns(row, "company_name", "name");
                    if (companyId.isBlank()) {
                        companyId = "unknown";
                    }
                    if (companyName.isBlank()) {
                        companyName = "unknown";
                    }
                    String snippet = "row=" + flattenRow(row);
                    double score = 20.0 - rowIndex * 0.5;
                    chunks.add(ContextChunk.ofCompany(
                            companyId,
                            companyName,
                            "sql_query",
                            shorten(snippet, 360),
                            score,
                            "mysql-sql"
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("executeSqlToChunks failed: {}", e.getMessage());
            return List.of();
        }
        return chunks;
    }

    private String pickByColumns(Map<String, String> row, String... markers) {
        for (String marker : markers) {
            String m = marker.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : row.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).contains(m) && !entry.getValue().isBlank()) {
                    return entry.getValue();
                }
            }
        }
        return "";
    }

    private String flattenRow(Map<String, String> row) {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (count > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append("=").append(entry.getValue());
            count++;
        }
        return builder.toString();
    }

    private String stripCodeBlock(String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text.trim());
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "...";
    }
}
