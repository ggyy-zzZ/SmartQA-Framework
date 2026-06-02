package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MySQL 公司表「人员角色列」映射（classpath:qa/sql-role-columns.json）。
 */
public class SqlRoleColumnCatalog {

    private final Map<String, String> columnLabels;
    private final List<PriorityPattern> priorityPatterns;
    private final int defaultPriority;

    public SqlRoleColumnCatalog(
            Map<String, String> columnLabels,
            List<PriorityPattern> priorityPatterns,
            int defaultPriority
    ) {
        this.columnLabels = Map.copyOf(columnLabels);
        this.priorityPatterns = List.copyOf(priorityPatterns);
        this.defaultPriority = defaultPriority;
    }

    public static SqlRoleColumnCatalog loadDefault(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) throws Exception {
        JsonNode root = configLoader.readTree("sql-role-columns");
        Map<String, String> columns = new LinkedHashMap<>();
            root.path("columns").fields().forEachRemaining(e ->
                    columns.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asText()));
            List<PriorityPattern> patterns = new ArrayList<>();
            for (JsonNode item : root.path("priorityPatterns")) {
                patterns.add(new PriorityPattern(
                        item.path("contains").asText(""),
                        item.path("priority").asInt(99)
                ));
            }
        return new SqlRoleColumnCatalog(
                columns,
                patterns,
                root.path("defaultPriority").asInt(4)
        );
    }

    public Map<String, String> columnLabels() {
        return columnLabels;
    }

    public int priority(String columnName) {
        String c = columnName == null ? "" : columnName.toLowerCase(Locale.ROOT);
        for (PriorityPattern pattern : priorityPatterns) {
            if (!pattern.contains.isBlank() && c.contains(pattern.contains)) {
                return pattern.priority;
            }
        }
        return defaultPriority;
    }

    public record PriorityPattern(String contains, int priority) {
    }
}
