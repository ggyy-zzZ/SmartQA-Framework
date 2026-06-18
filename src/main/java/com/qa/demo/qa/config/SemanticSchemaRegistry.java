package com.qa.demo.qa.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.domain.EnterpriseEnumLabelService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 语义 Schema 注册表：从 {@code qa/semantic-schema.json} 加载实体/字段/角色引用/关系，
 * 为 LLM SQL 生成提供摘要，为 CDC 角色列提供标签映射。
 */
@Component
public class SemanticSchemaRegistry {

    private final AssistantConfigJsonLoader configLoader;
    private final EnterpriseEnumLabelService enumLabels;

    private volatile JsonNode root;

    public SemanticSchemaRegistry(
            AssistantConfigJsonLoader configLoader,
            EnterpriseEnumLabelService enumLabels
    ) {
        this.configLoader = configLoader;
        this.enumLabels = enumLabels;
    }

    public JsonNode root() {
        ensureLoaded();
        return root;
    }

    public String databaseName() {
        return root().path("database").asText("tdcomp");
    }

    public Map<String, String> companyRoleColumnLabels() {
        Map<String, String> map = new LinkedHashMap<>();
        JsonNode refs = root().path("entities").path("company").path("roleReferences");
        for (JsonNode ref : refs) {
            String column = ref.path("column").asText("");
            String label = ref.path("label").asText("");
            if (!column.isBlank() && !label.isBlank()) {
                map.put(column.toLowerCase(Locale.ROOT), label);
            }
        }
        return Map.copyOf(map);
    }

    public int roleColumnPriority(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return root().path("defaultRoleColumnPriority").asInt(4);
        }
        String lower = columnName.toLowerCase(Locale.ROOT);
        for (JsonNode pattern : root().path("roleColumnPriority")) {
            String contains = pattern.path("contains").asText("");
            if (!contains.isBlank() && lower.contains(contains)) {
                return pattern.path("priority").asInt(99);
            }
        }
        return root().path("defaultRoleColumnPriority").asInt(4);
    }

    /**
     * 供 LLM SQL 生成使用的语义摘要（含中文标签、枚举提示、JOIN 关系）。
     */
    public String buildLlmSchemaSummary() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder();
        sb.append("database=").append(databaseName()).append("\n");
        JsonNode entities = root().path("entities");
        entities.fields().forEachRemaining(entry -> {
            String entityId = entry.getKey();
            JsonNode entity = entry.getValue();
            sb.append("\n[entity ").append(entityId).append("] ")
                    .append(entity.path("label").asText(entityId)).append("\n");
            sb.append("table=").append(entity.path("table").asText())
                    .append(", id=").append(entity.path("idColumn").asText("id"));
            String softDelete = entity.path("softDeleteColumn").asText("");
            if (!softDelete.isBlank()) {
                sb.append(", soft_delete=").append(softDelete).append("=0");
            }
            sb.append("\n");
            for (JsonNode attr : entity.path("attributes")) {
                if (!attr.path("queryable").asBoolean(true)) {
                    continue;
                }
                sb.append("  - ").append(attr.path("column").asText())
                        .append(" (").append(attr.path("label").asText()).append(")");
                String enumField = attr.path("enumField").asText("");
                if (!enumField.isBlank()) {
                    sb.append(" enum=").append(enumField);
                    appendEnumSamples(sb, enumField);
                }
                JsonNode ref = attr.path("reference");
                if (ref.isObject()) {
                    sb.append(" -> ").append(ref.path("entity").asText())
                            .append(".").append(ref.path("column").asText());
                }
                sb.append("\n");
            }
            for (JsonNode role : entity.path("roleReferences")) {
                sb.append("  - ").append(role.path("column").asText())
                        .append(" (").append(role.path("label").asText()).append(")")
                        .append(" -> ").append(role.path("targetEntity").asText())
                        .append(".").append(role.path("targetDisplayColumn").asText())
                        .append(" via ").append(role.path("type").asText("employee_id"))
                        .append("\n");
            }
        });
        sb.append("\n[joins]\n");
        for (JsonNode rel : root().path("relationships")) {
            sb.append("  ").append(rel.path("fromEntity").asText())
                    .append(".").append(rel.path("fromColumn").asText())
                    .append(" = ").append(rel.path("toEntity").asText())
                    .append(".").append(rel.path("toColumn").asText())
                    .append(" (").append(rel.path("label").asText()).append(")\n");
        }
        sb.append("\n[rules]\n");
        sb.append("  - 查人员角色姓名时 JOIN employee ON company.<role_id> = employee.id\n");
        sb.append("  - 查证照保管人/监管人时解析 certificate_management 逗号分隔 ID 列\n");
        sb.append("  - 默认过滤 deleteflag=0（若表有软删列）\n");
        sb.append("  - 枚举列展示可用 CASE 或保留原码，证据中尽量带中文标签\n");
        return sb.toString();
    }

    public List<String> missingEnumCatalogNotes() {
        ensureLoaded();
        List<String> notes = new ArrayList<>();
        for (JsonNode item : root().path("missingEnumCatalog")) {
            notes.add(item.toString());
        }
        return List.copyOf(notes);
    }

    private void appendEnumSamples(StringBuilder sb, String enumField) {
        Map<String, String> labels = enumLabels.dictEntries(enumField);
        if (labels == null || labels.isEmpty()) {
            sb.append(" {未配置枚举}");
            return;
        }
        int count = 0;
        sb.append(" {");
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (count++ >= 6) {
                sb.append("…");
                break;
            }
            if (count > 1) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append("}");
    }

    private void ensureLoaded() {
        if (root != null) {
            return;
        }
        synchronized (this) {
            if (root != null) {
                return;
            }
            try {
                root = configLoader.readTree("semantic-schema");
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load semantic-schema", e);
            }
        }
    }
}
