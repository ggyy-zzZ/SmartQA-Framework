package com.qa.demo.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 证据片段字段名与展示标签（classpath:qa/evidence-schemas.json）。
 * 检索层按 schema 组装 snippet；闸门与生成契约通过 schemaId 识别形态，不解析具体中文键名。
 */
@Component
public class EvidenceSchemaRegistry {

    private final Map<String, Map<String, String>> schemaFields;
    private final Map<String, String> sourceToSchema;

    public EvidenceSchemaRegistry(ObjectMapper objectMapper) {
        Loaded loaded = load(objectMapper);
        this.schemaFields = loaded.schemaFields();
        this.sourceToSchema = loaded.sourceToSchema();
    }

    public String schemaForSource(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        return sourceToSchema.getOrDefault(source.trim(), "");
    }

    public boolean hasSchema(String schemaId) {
        return schemaId != null && !schemaId.isBlank() && schemaFields.containsKey(schemaId.trim());
    }

    public String formatSnippet(String schemaId, Map<String, String> values) {
        if (schemaId == null || schemaId.isBlank() || values == null || values.isEmpty()) {
            return "";
        }
        Map<String, String> fields = schemaFields.get(schemaId.trim());
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String value = values.get(entry.getKey());
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(entry.getValue()).append("=").append(value.trim());
        }
        return sb.toString();
    }

    public String formatEmployeeIdentity(String legalName, String alias) {
        Map<String, String> values = new LinkedHashMap<>();
        if (legalName != null && !legalName.isBlank()) {
            values.put("legalName", legalName.trim());
        }
        if (alias != null && !alias.isBlank()) {
            values.put("alias", alias.trim());
        }
        return formatSnippet("employee_identity_v1", values);
    }

    public Set<String> requiredFieldLabels(String schemaId) {
        Map<String, String> fields = schemaFields.get(schemaId == null ? "" : schemaId.trim());
        if (fields == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(fields.values());
    }

    private static Loaded load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("qa/evidence-schemas.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            Map<String, Map<String, String>> schemas = new LinkedHashMap<>();
            JsonNode schemaNode = root.path("schemas");
            schemaNode.fieldNames().forEachRemaining(schemaId -> {
                Map<String, String> fields = new LinkedHashMap<>();
                JsonNode fieldDefs = schemaNode.path(schemaId).path("fields");
                fieldDefs.fieldNames().forEachRemaining(key ->
                        fields.put(key, fieldDefs.path(key).asText(key))
                );
                schemas.put(schemaId, Map.copyOf(fields));
            });
            Map<String, String> bySource = new LinkedHashMap<>();
            root.path("sourceToSchema").fields().forEachRemaining(entry ->
                    bySource.put(entry.getKey(), entry.getValue().asText(""))
            );
            return new Loaded(Map.copyOf(schemas), Map.copyOf(bySource));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load qa/evidence-schemas.json", ex);
        }
    }

    private record Loaded(Map<String, Map<String, String>> schemaFields, Map<String, String> sourceToSchema) {
    }
}