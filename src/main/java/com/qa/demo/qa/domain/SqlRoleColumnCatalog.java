package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.SemanticSchemaRegistry;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 公司表人员角色列映射，从 {@code qa/semantic-schema.json} 加载（供 CDC 等复用）。
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

    public static SqlRoleColumnCatalog loadDefault(
            ObjectMapper objectMapper,
            AssistantConfigJsonLoader configLoader
    ) throws Exception {
        JsonNode root = configLoader.readTree("semantic-schema");
        Map<String, String> columns = new LinkedHashMap<>();
        for (JsonNode ref : root.path("entities").path("company").path("roleReferences")) {
            String column = ref.path("column").asText("");
            String label = ref.path("label").asText("");
            if (!column.isBlank() && !label.isBlank()) {
                columns.put(column.toLowerCase(Locale.ROOT), label);
            }
        }
        List<PriorityPattern> patterns = new ArrayList<>();
        for (JsonNode item : root.path("roleColumnPriority")) {
            patterns.add(new PriorityPattern(
                    item.path("contains").asText(""),
                    item.path("priority").asInt(99)
            ));
        }
        return new SqlRoleColumnCatalog(
                columns,
                patterns,
                root.path("defaultRoleColumnPriority").asInt(4)
        );
    }

    public static SqlRoleColumnCatalog fromRegistry(SemanticSchemaRegistry registry) {
        Map<String, String> columns = new LinkedHashMap<>();
        registry.companyRoleColumnLabels().forEach(columns::put);
        List<PriorityPattern> patterns = new ArrayList<>();
        JsonNode root = registry.root();
        for (JsonNode item : root.path("roleColumnPriority")) {
            patterns.add(new PriorityPattern(
                    item.path("contains").asText(""),
                    item.path("priority").asInt(99)
            ));
        }
        return new SqlRoleColumnCatalog(
                columns,
                patterns,
                root.path("defaultRoleColumnPriority").asInt(4)
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
