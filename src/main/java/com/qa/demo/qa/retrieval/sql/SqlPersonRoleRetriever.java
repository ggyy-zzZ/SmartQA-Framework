package com.qa.demo.qa.retrieval.sql;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.domain.EnterpriseLexicon;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import com.qa.demo.qa.domain.SqlRoleColumnCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

/**
 * MySQL 人员-角色 UNION 检索（与图谱人-任职互补；列表类问题由图谱主答时跳过）。
 */
@Component
public class SqlPersonRoleRetriever {

    private static final Logger log = LoggerFactory.getLogger(SqlPersonRoleRetriever.class);
    private static final Pattern PERSON_NAME_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4}?)(?:现在|目前|现任|是|担任|作为|在|的|有哪些|哪些)"
    );
    private static final Pattern CJK_NAME_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}");

    private final QaAssistantProperties properties;
    private final EnterpriseLexicon lexicon;
    private final QuestionEntityExtractor entityExtractor;
    private final SqlRoleColumnCatalog roleColumnCatalog;

    public SqlPersonRoleRetriever(
            QaAssistantProperties properties,
            EnterpriseLexicon lexicon,
            QuestionEntityExtractor entityExtractor,
            SqlRoleColumnCatalog roleColumnCatalog
    ) {
        this.properties = properties;
        this.lexicon = lexicon;
        this.entityExtractor = entityExtractor;
        this.roleColumnCatalog = roleColumnCatalog;
    }

    public boolean skipForPlan(RetrievalPlan plan) {
        return false;
    }

    public List<ContextChunk> retrieve(
            Connection connection,
            String question,
            IntentDecision intent,
            int topK
    ) {
        if (!isPersonRoleQuestion(question, intent)) {
            return List.of();
        }
        String roleFocus = intent != null && intent.roleFocus() != null && !intent.roleFocus().isBlank()
                ? intent.roleFocus().toLowerCase(Locale.ROOT)
                : "any";
        List<EmployeeHit> employees = resolveEmployees(connection, question, intent);
        String personName = entityExtractor.resolvePersonName(question, intent);
        if (personName.isBlank()) {
            personName = extractPersonNameRegexFallback(question);
        }
        if (employees.isEmpty()) {
            return List.of(buildEmployeeNotFoundChunk(personName));
        }
        Map<String, String> existingRoleColumns = filterColumnsByRoleFocus(
                loadExistingRoleColumns(connection), roleFocus);
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
                .sorted(Comparator.comparingInt(e -> roleColumnCatalog.priority(e.getKey())))
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
                    """.formatted(
                    column,
                    escapeSqlLiteral(label),
                    companyTableQuoted(),
                    column,
                    idPlaceholders,
                    whereDelete
            ));
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
                    chunks.add(ContextChunk.ofCompany(
                            companyId.isBlank() ? "unknown" : companyId,
                            companyName.isBlank() ? "unknown" : companyName,
                            "person_role",
                            shorten(snippet, 220),
                            25.0 - rowIndex * 0.5,
                            "mysql-sql-person-role"
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("SqlPersonRoleRetriever failed: {}", e.getMessage());
            return List.of();
        }
        if (chunks.isEmpty()) {
            return List.of(buildNoRoleHitChunk(personName));
        }
        return chunks;
    }

    private boolean isPersonRoleQuestion(String question, IntentDecision intent) {
        if (question == null || question.isBlank()) {
            return false;
        }
        if (intent != null && intent.isPersonRoleListQuery()) {
            return intent.hasPersonFocus() || intent.hasPersonEmployeeId();
        }
        if (!lexicon.containsAny(question, lexicon.sqlPersonRoleKeywords())) {
            return false;
        }
        String person = entityExtractor.resolvePersonName(question, intent);
        if (!person.isBlank()) {
            return true;
        }
        return !extractPersonNameRegexFallback(question).isBlank();
    }

    private String extractPersonNameRegexFallback(String question) {
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
            if (candidate != null) {
                String name = sanitizeNameCandidate(candidate);
                if (!name.isBlank()) {
                    return name;
                }
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
        for (String prefix : lexicon.namePrefixNoise()) {
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
        boolean stopword = lexicon.nameStopwords().stream().anyMatch(name::contains);
        return stopword ? "" : name;
    }

    private int locateRoleKeywordIndex(String normalizedQuestion) {
        int idx = -1;
        for (String marker : lexicon.roleKeywordMarkers()) {
            int current = normalizedQuestion.indexOf(marker);
            if (current >= 0 && (idx < 0 || current < idx)) {
                idx = current;
            }
        }
        return idx;
    }

    private List<EmployeeHit> resolveEmployees(Connection connection, String question, IntentDecision intent) {
        if (intent != null && intent.hasPersonEmployeeId()) {
            int id = intent.personEmployeeId();
            EmployeeHit hit = findEmployeeById(connection, id);
            if (hit != null) {
                return List.of(hit);
            }
        }
        String personName = entityExtractor.resolvePersonName(question, intent);
        if (personName.isBlank()) {
            personName = extractPersonNameRegexFallback(question);
        }
        if (personName.isBlank()) {
            return List.of();
        }
        return findEmployeesByName(connection, personName, 20);
    }

    private EmployeeHit findEmployeeById(Connection connection, int employeeId) {
        String sql = """
                SELECT id, name
                FROM %s
                WHERE id = ?
                LIMIT 1
                """.formatted(employeeTableQuoted());
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, employeeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new EmployeeHit(rs.getInt("id"), safe(rs.getString("name")));
                }
            }
        } catch (Exception e) {
            log.debug("findEmployeeById failed: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, String> filterColumnsByRoleFocus(Map<String, String> columns, String roleFocus) {
        if (columns == null || columns.isEmpty() || roleFocus == null || "any".equalsIgnoreCase(roleFocus)) {
            return columns == null ? Map.of() : columns;
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            boolean match = switch (roleFocus) {
                case "legal_rep" -> key.contains("legal_rep");
                case "director" -> key.contains("director") || key.contains("chairman");
                case "supervisor" -> key.contains("supervisor");
                case "shareholder" -> key.contains("shareholder");
                default -> true;
            };
            if (match) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private Map<String, String> loadExistingRoleColumns(Connection connection) {
        String sql = """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                """;
        Map<String, String> columns = new LinkedHashMap<>();
        Map<String, String> labels = roleColumnCatalog.columnLabels();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, businessSchemaFromUrl());
            ps.setString(2, personRoleCompanyTablePhysical());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String original = safe(rs.getString("column_name"));
                    String normalized = original.toLowerCase(Locale.ROOT);
                    if (labels.containsKey(normalized)) {
                        columns.put(original, labels.get(normalized));
                    }
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return columns;
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
        } catch (Exception e) {
            log.debug("findEmployeesByName failed: {}", e.getMessage());
            return List.of();
        }
        return hits;
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
            ps.setString(1, businessSchemaFromUrl());
            ps.setString(2, personRoleCompanyTablePhysical());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private String businessSchemaFromUrl() {
        String url = properties.getBusinessMysqlUrl();
        if (url == null || url.isBlank()) {
            return properties.getMysqlSchema();
        }
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash >= url.length() - 1) {
            return properties.getMysqlSchema();
        }
        String tail = url.substring(slash + 1);
        int q = tail.indexOf('?');
        return q > 0 ? tail.substring(0, q) : tail;
    }

    private ContextChunk buildEmployeeNotFoundChunk(String personName) {
        String et = personRoleEmployeeTablePhysical();
        String snippet = "表「" + et + "」未匹配到人员：" + personName + "。建议确认姓名是否准确，或提供工号/别名。";
        return ContextChunk.ofSystem("employee_not_found", "person_lookup", snippet, 18.0, "mysql-employee-precheck");
    }

    private ContextChunk buildNoRoleHitChunk(String personName) {
        String et = personRoleEmployeeTablePhysical();
        String snippet = "已在表「" + et + "」命中人员：" + personName + "，但在公司内部角色字段未查到关联记录。";
        return ContextChunk.ofSystem("person_role_empty", "person_role", snippet, 17.0, "mysql-person-role-empty");
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

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
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
