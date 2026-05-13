package com.qa.demo.qa.retrieval;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SqlQueryService {

    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)\\blimit\\s+\\d+");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```(?:sql|json)?\\s*(.*?)\\s*```");
    private static final Pattern PERSON_NAME_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,4}?)(?:现在|目前|现任|是|担任|作为|在|的|有哪些|哪些)");
    private static final Pattern CJK_NAME_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}");
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "insert", "update", "delete", "drop", "alter", "truncate", "create",
            "grant", "revoke", "replace", "merge", "call", "outfile", "load_file",
            "benchmark", "sleep"
    );
    private static final List<String> NAME_STOPWORDS = List.of(
            "哪些", "哪个",
            "角色", "企业", "什么", "有啥", "请问", "帮我", "现在", "内部", "担任"
    );
    private static final List<String> NAME_PREFIX_NOISE = List.of(
            "请问", "请帮我", "帮我", "麻烦", "查询", "查一下", "我想知道", "想知道", "请"
    );
    private static final Map<String, String> ROLE_COLUMN_LABELS = Map.ofEntries(
            Map.entry("legal_rep_id", "法定代表人"),
            Map.entry("manager_id", "经理"),
            Map.entry("financial_manager_id", "财务负责人"),
            Map.entry("finance_manager_id", "财务负责人"),
            Map.entry("tax_handler_id", "办税人"),
            Map.entry("taxer_id", "办税人"),
            Map.entry("tax_manager_id", "办税人"),
            Map.entry("ticket_purchaser_id", "购票人"),
            Map.entry("invoice_buyer_id", "购票人"),
            Map.entry("company_contact_id", "联络人"),
            Map.entry("contact_id", "联络人"),
            Map.entry("company_supervisor_id", "监事"),
            Map.entry("assigned_accountant_id", "会计"),
            Map.entry("assigned_cashier_id", "出纳"),
            Map.entry("accounting_supervisor_id", "会计主管"),
            Map.entry("chairman_exec_director_id", "执行董事/董事长"),
            Map.entry("ssc_payroll_manager_id", "SSC薪资负责人"),
            Map.entry("it_owner_id", "IT负责人"),
            Map.entry("ssc_salary_owner_id", "SSC薪资负责人"),
            Map.entry("certificate_keeper_id", "证照保管人"),
            Map.entry("certificate_supervisor_id", "证照监管人"),
            Map.entry("certificate_executor_id", "证照执行人")
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;

    public SqlQueryService(ObjectMapper objectMapper, QaAssistantProperties properties) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK) {
        if (!properties.isMysqlEnabled()) {
            return List.of();
        }
        int limitedTopK = Math.max(1, Math.min(topK, 20));
        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword())
        ) {
            List<ContextChunk> roleChunks = retrievePersonRoleChunks(connection, question, limitedTopK);
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
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<ContextChunk> retrievePersonRoleChunks(Connection connection, String question, int topK) {
        if (!isPersonRoleQuestion(question)) {
            return List.of();
        }
        String personName = extractPersonName(question);
        if (personName.isBlank()) {
            return List.of();
        }
        List<EmployeeHit> employees = findEmployeesByName(connection, personName, 20);
        if (employees.isEmpty()) {
            return List.of(buildEmployeeNotFoundChunk(personName));
        }
        Map<String, String> existingRoleColumns = loadExistingRoleColumns(connection);
        if (existingRoleColumns.isEmpty()) {
            return List.of();
        }
        Set<Integer> personIds = new HashSet<>();
        Map<Integer, String> personNameById = new LinkedHashMap<>();
        for (EmployeeHit employee : employees) {
            personIds.add(employee.id());
            personNameById.put(employee.id(), employee.name());
        }
        String idPlaceholders = buildPlaceholders(personIds.size());
        boolean hasDeleteFlag = hasCompanyDeleteFlag(connection);
        List<Map.Entry<String, String>> ordered = existingRoleColumns.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> rolePriority(e.getKey())))
                .toList();
        List<String> unionParts = new ArrayList<>();
        for (Map.Entry<String, String> entry : ordered) {
            String column = entry.getKey();
            String label = entry.getValue();
            String whereDelete = hasDeleteFlag ? " AND c.deleteflag = 0" : "";
            unionParts.add("""
                    SELECT
                      c.id AS company_id,
                      c.company_name,
                      c.`%s` AS person_id,
                      '%s' AS role_name
                    FROM %s c
                    WHERE c.`%s` IN (%s)%s
                    """.formatted(column, escapeSqlLiteral(label), companyTableQuoted(), column, idPlaceholders, whereDelete));
        }
        if (unionParts.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM (" + String.join(" UNION ALL ", unionParts) + ") x ORDER BY company_id LIMIT ?";
        List<ContextChunk> chunks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            for (int i = 0; i < unionParts.size(); i++) {
                for (Integer id : personIds) {
                    ps.setInt(idx++, id);
                }
            }
            ps.setInt(idx, Math.max(1, topK));
            try (ResultSet rs = ps.executeQuery()) {
                int rowIndex = 0;
                while (rs.next()) {
                    rowIndex++;
                    String companyId = safe(rs.getString("company_id"));
                    String companyName = safe(rs.getString("company_name"));
                    String roleName = safe(rs.getString("role_name"));
                    String personId = safe(rs.getString("person_id"));
                    String person = personNameById.getOrDefault(parseIntSafe(personId), personName);
                    String snippet = "人员=" + person + "; 角色=" + roleName + "; 人员ID=" + personId;
                    chunks.add(new ContextChunk(
                            companyId.isBlank() ? "unknown" : companyId,
                            companyName.isBlank() ? "unknown" : companyName,
                            "person_role",
                            shorten(snippet, 220),
                            25.0 - rowIndex * 0.5,
                            "mysql-sql-person-role"
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        if (chunks.isEmpty()) {
            return List.of(buildNoRoleHitChunk(personName));
        }
        return chunks;
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
        } catch (Exception ignored) {
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
                    String snippet = "sql=" + sql + "; row=" + flattenRow(row);
                    double score = 20.0 - rowIndex * 0.5;
                    chunks.add(new ContextChunk(
                            companyId,
                            companyName,
                            "sql_query",
                            shorten(snippet, 360),
                            score,
                            "mysql-sql"
                    ));
                }
            }
        } catch (Exception ignored) {
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

    private boolean isPersonRoleQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        boolean hasRoleKeyword = q.contains("角色")
                || q.contains("担任")
                || q.contains("职位")
                || q.contains("负责人")
                || q.contains("联络人")
                || q.contains("经理")
                || q.contains("法人")
                || q.contains("法定代表人");
        if (!hasRoleKeyword) {
            return false;
        }
        return !extractPersonName(question).isBlank();
    }

    private String extractPersonName(String question) {
        if (question == null) {
            return "";
        }
        String normalized = question
                .replace("？", "")
                .replace("?", "")
                .replace("，", "")
                .replace(",", "")
                .replace("。", "")
                .replace(".", "")
                .replace(" ", "")
                .trim();
        Matcher matcher = PERSON_NAME_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (candidate == null) {
                continue;
            }
            String name = sanitizeNameCandidate(candidate);
            if (!name.isBlank()) {
                return name;
            }
        }

        int keywordIndex = locateRoleKeywordIndex(normalized);
        if (keywordIndex > 0) {
            int start = Math.max(0, keywordIndex - 24);
            String prefix = normalized.substring(start, keywordIndex);
            Matcher nameMatcher = CJK_NAME_PATTERN.matcher(prefix);
            String fallback = "";
            while (nameMatcher.find()) {
                String candidate = sanitizeNameCandidate(nameMatcher.group());
                if (!candidate.isBlank()) {
                    fallback = candidate;
                }
            }
            if (!fallback.isBlank()) {
                return fallback;
            }
        }
        return "";
    }

    private String sanitizeNameCandidate(String candidate) {
        if (candidate == null) {
            return "";
        }
        String name = candidate.trim();
        for (String prefix : NAME_PREFIX_NOISE) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
        }
        while (name.length() > 2 && "在现请帮我查问麻烦".indexOf(name.charAt(0)) >= 0) {
            name = name.substring(1);
        }
        while (name.length() > 2 && "在的是有中现任内部".indexOf(name.charAt(name.length() - 1)) >= 0) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.length() > 4) {
            name = name.substring(name.length() - 4);
        }
        if (name.length() < 2 || name.length() > 4) {
            return "";
        }
        if (!name.matches("[\\u4e00-\\u9fa5]{2,4}")) {
            return "";
        }
        boolean stopword = NAME_STOPWORDS.stream().anyMatch(name::contains);
        return stopword ? "" : name;
    }

    private int locateRoleKeywordIndex(String normalizedQuestion) {
        List<String> markers = List.of("法定代表人", "法人", "角色", "职位", "职务", "负责人", "担任");
        int idx = -1;
        for (String marker : markers) {
            int current = normalizedQuestion.indexOf(marker);
            if (current >= 0 && (idx < 0 || current < idx)) {
                idx = current;
            }
        }
        return idx;
    }

    private List<EmployeeHit> findEmployeesByName(Connection connection, String personName, int limit) {
        String sql = """
                SELECT id, name
                FROM %s
                WHERE name LIKE ?
                ORDER BY id
                LIMIT ?
                """.formatted(employeeTableQuoted());
        List<EmployeeHit> hits = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, "%" + personName + "%");
            ps.setInt(2, Math.max(1, Math.min(limit, 50)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hits.add(new EmployeeHit(rs.getInt("id"), safe(rs.getString("name"))));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return hits;
    }

    private String buildPlaceholders(int size) {
        int n = Math.max(1, size);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    private int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private ContextChunk buildEmployeeNotFoundChunk(String personName) {
        String et = personRoleEmployeeTablePhysical();
        String snippet = "表「" + et + "」未匹配到人员：" + personName + "。建议确认姓名是否准确，或提供工号/别名。";
        return new ContextChunk(
                "employee_not_found",
                et,
                "person_lookup",
                snippet,
                18.0,
                "mysql-employee-precheck"
        );
    }

    private ContextChunk buildNoRoleHitChunk(String personName) {
        String et = personRoleEmployeeTablePhysical();
        String snippet = "已在表「" + et + "」命中人员：" + personName + "，但在公司内部角色字段未查到关联记录。";
        return new ContextChunk(
                "person_role_empty",
                personRoleCompanyTablePhysical(),
                "person_role",
                snippet,
                17.0,
                "mysql-person-role-empty"
        );
    }

    private Map<String, String> loadExistingRoleColumns(Connection connection) {
        String sql = """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                """;
        Map<String, String> columns = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, properties.getMysqlSchema());
            ps.setString(2, personRoleCompanyTablePhysical());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String original = safe(rs.getString("column_name"));
                    String normalized = original.toLowerCase(Locale.ROOT);
                    if (ROLE_COLUMN_LABELS.containsKey(normalized)) {
                        columns.put(original, ROLE_COLUMN_LABELS.get(normalized));
                    }
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return columns;
    }

    private boolean hasCompanyDeleteFlag(Connection connection) {
        String sql = """
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND lower(column_name) = 'deleteflag'
                LIMIT 1
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, properties.getMysqlSchema());
            ps.setString(2, personRoleCompanyTablePhysical());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private int rolePriority(String columnName) {
        String c = columnName == null ? "" : columnName.toLowerCase(Locale.ROOT);
        if (c.contains("legal_rep")) {
            return 0;
        }
        if (c.contains("manager")) {
            return 1;
        }
        if (c.contains("financial") || c.contains("finance") || c.contains("tax") || c.contains("invoice")) {
            return 2;
        }
        if (c.contains("contact") || c.contains("it") || c.contains("ssc")) {
            return 3;
        }
        return 4;
    }

    private String escapeSqlLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "...";
    }

    private String personRoleEmployeeTablePhysical() {
        return sanitizeTableName(properties.getMysqlPersonRoleEmployeeTable(), "employee");
    }

    private String personRoleCompanyTablePhysical() {
        return sanitizeTableName(properties.getMysqlPersonRoleCompanyTable(), "company");
    }

    private String employeeTableQuoted() {
        return "`" + personRoleEmployeeTablePhysical() + "`";
    }

    private String companyTableQuoted() {
        return "`" + personRoleCompanyTablePhysical() + "`";
    }

    private static String sanitizeTableName(String value, String fallback) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            return fallback;
        }
        return value;
    }

    private record EmployeeHit(int id, String name) {
    }
}
