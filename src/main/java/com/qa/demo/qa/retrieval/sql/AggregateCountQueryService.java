package com.qa.demo.qa.retrieval.sql;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.SemanticSchemaRegistry;
import com.qa.demo.qa.core.ContextChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 驱动的 COUNT 查询：用于 {@code retrievalStrategy=aggregate_count}，不经过 TOP-K 召回。
 */
@Service
public class AggregateCountQueryService {

    private static final Logger log = LoggerFactory.getLogger(AggregateCountQueryService.class);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```(?:sql|json)?\\s*(.*?)\\s*```");
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "insert", "update", "delete", "drop", "alter", "truncate", "create",
            "grant", "revoke", "replace", "merge", "call", "outfile", "load_file",
            "benchmark", "sleep"
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;
    private final SemanticSchemaRegistry semanticSchemaRegistry;

    public AggregateCountQueryService(
            ObjectMapper objectMapper,
            QaAssistantProperties properties,
            SemanticSchemaRegistry semanticSchemaRegistry
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.semanticSchemaRegistry = semanticSchemaRegistry;
        this.restClient = RestClient.builder().build();
    }

    public List<ContextChunk> retrieve(String question) {
        if (question == null || question.isBlank() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return List.of();
        }
        String schema = semanticSchemaRegistry.databaseName();
        if (schema.isBlank()) {
            return List.of();
        }
        try (Connection connection = DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword())
        ) {
            String schemaSummary = semanticSchemaRegistry.buildLlmSchemaSummary();
            if (schemaSummary.isBlank()) {
                return List.of();
            }
            String generatedSql = generateCountSql(question, schema, schemaSummary);
            String normalizedSql = normalizeAndValidateCountSql(generatedSql);
            if (normalizedSql == null) {
                return List.of();
            }
            return executeCountSql(connection, normalizedSql, question);
        } catch (Exception e) {
            log.warn("aggregate count retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String generateCountSql(String question, String schema, String schemaSummary) throws Exception {
        String userPrompt = "semantic_schema:\n" + schemaSummary + "\nschema=" + schema + "\nquestion=" + question;
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "name", "MiniMax AI",
                                "content", KnowledgeAssistantPrompts.sqlCountGeneratorSystemPrompt()),
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

    private String normalizeAndValidateCountSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String normalized = sql.replace("\n", " ").replace("\r", " ").trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (!isSafeCountSql(normalized)) {
            return null;
        }
        return normalized;
    }

    private boolean isSafeCountSql(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        if (lower.contains(";") || lower.contains("--") || lower.contains("/*") || lower.contains("#")) {
            return false;
        }
        if (!(lower.startsWith("select ") || lower.startsWith("with "))) {
            return false;
        }
        if (!lower.contains("count(")) {
            return false;
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (lower.contains(token + " ")) {
                return false;
            }
        }
        return true;
    }

    private List<ContextChunk> executeCountSql(Connection connection, String sql, String question) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return List.of();
            }
            String countValue = rs.getString(1);
            if (countValue == null) {
                countValue = "0";
            }
            String snippet = "metric=count; value=" + countValue + "; sql=" + shorten(sql, 280)
                    + "; question=" + shorten(question, 120);
            return List.of(ContextChunk.ofSystem(
                    "aggregate_count",
                    "aggregate_count",
                    snippet,
                    100.0,
                    "mysql-aggregate-count"
            ));
        } catch (Exception e) {
            log.warn("executeCountSql failed: {}", e.getMessage());
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
                if (tableName.isBlank() || columnName.isBlank() || tableName.startsWith("qa_")) {
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

    private static String schemaFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "";
        }
        int schemeEnd = jdbcUrl.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        int pathStart = jdbcUrl.indexOf('/', schemeEnd + 3);
        if (pathStart < 0 || pathStart >= jdbcUrl.length() - 1) {
            return "";
        }
        String path = jdbcUrl.substring(pathStart + 1);
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "...";
    }
}
