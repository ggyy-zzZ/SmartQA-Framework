package com.qa.demo.qa.cdc.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CDC 图谱同步规则（classpath:qa/cdc-graph-sync.json）。
 */
public class CdcGraphSyncCatalog {

    private final Map<String, NodeTypeDef> nodeTypes;
    private final List<RelationshipRuleDef> relationshipRules;
    private final VectorDocumentEnrichmentDef vectorDocumentEnrichment;

    public CdcGraphSyncCatalog(
            Map<String, NodeTypeDef> nodeTypes,
            List<RelationshipRuleDef> relationshipRules,
            VectorDocumentEnrichmentDef vectorDocumentEnrichment
    ) {
        this.nodeTypes = Map.copyOf(nodeTypes);
        this.relationshipRules = List.copyOf(relationshipRules);
        this.vectorDocumentEnrichment = vectorDocumentEnrichment;
    }

    public static CdcGraphSyncCatalog loadDefault(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) throws Exception {
        JsonNode root = configLoader.readTree("cdc-graph-sync");
        Map<String, NodeTypeDef> nodes = new LinkedHashMap<>();
        root.path("nodeTypes").fields().forEachRemaining(entry -> {
            JsonNode n = entry.getValue();
            nodes.put(entry.getKey(), new NodeTypeDef(
                    entry.getKey(),
                    n.path("label").asText(entry.getKey()),
                    n.path("idProperty").asText("id"),
                    n.path("personIdProperty").asText(null),
                    n.path("nameProperty").asText(null),
                    n.path("resolveEntityTable").asText(null)
            ));
        });
        List<RelationshipRuleDef> rules = new ArrayList<>();
        for (JsonNode item : root.path("relationshipRules")) {
            rules.add(RelationshipRuleDef.fromJson(item));
        }
        VectorDocumentEnrichmentDef enrich = VectorDocumentEnrichmentDef.fromJson(
                root.path("vectorDocumentEnrichment"));
        return new CdcGraphSyncCatalog(nodes, rules, enrich);
    }

    public NodeTypeDef nodeType(String key) {
        return nodeTypes.get(key);
    }

    public List<RelationshipRuleDef> rulesForTable(String table) {
        if (table == null) {
            return List.of();
        }
        String t = table.trim().toLowerCase(Locale.ROOT);
        return relationshipRules.stream()
                .filter(r -> r.tables().contains(t))
                .toList();
    }

    public VectorDocumentEnrichmentDef vectorDocumentEnrichment() {
        return vectorDocumentEnrichment;
    }

    public record NodeTypeDef(
            String key,
            String label,
            String idProperty,
            String personIdProperty,
            String nameProperty,
            String resolveEntityTable
    ) {
    }

    public record IdRefDef(
            String resolveEntityTable,
            String personKeyStrategy,
            List<String> columns
    ) {
        static IdRefDef fromJson(JsonNode node) {
            if (node == null || node.isMissingNode()) {
                return new IdRefDef(null, null, List.of());
            }
            List<String> columns = new ArrayList<>();
            for (JsonNode col : node.path("columns")) {
                columns.add(col.asText());
            }
            return new IdRefDef(
                    textOrNull(node, "resolveEntityTable"),
                    textOrNull(node, "personKeyStrategy"),
                    List.copyOf(columns)
            );
        }
    }

    public record PersonIdBindingsDef(
            String mode,
            String catalog,
            boolean exclusiveRoleLabel,
            boolean stampSourceColumn
    ) {
        static PersonIdBindingsDef fromJson(JsonNode node) {
            if (node == null || node.isMissingNode()) {
                return null;
            }
            return new PersonIdBindingsDef(
                    node.path("mode").asText(""),
                    node.path("catalog").asText("sql-role-columns"),
                    node.path("exclusiveRoleLabel").asBoolean(true),
                    node.path("stampSourceColumn").asBoolean(false)
            );
        }
    }

    public record RoleLabelDef(List<String> columns, String defaultLabel) {
        static RoleLabelDef fromJson(JsonNode node) {
            if (node == null || node.isMissingNode()) {
                return null;
            }
            List<String> columns = new ArrayList<>();
            for (JsonNode col : node.path("columns")) {
                columns.add(col.asText());
            }
            return new RoleLabelDef(List.copyOf(columns), textOrNull(node, "default"));
        }
    }

    public record RelPropertyDef(String name, List<String> columns) {
        static RelPropertyDef fromJson(JsonNode node) {
            List<String> columns = new ArrayList<>();
            for (JsonNode col : node.path("columns")) {
                columns.add(col.asText());
            }
            return new RelPropertyDef(node.path("name").asText(), List.copyOf(columns));
        }
    }

    public record RelationshipRuleDef(
            String id,
            List<String> tables,
            String relationshipType,
            String fromNodeType,
            String toNodeType,
            IdRefDef fromId,
            IdRefDef toId,
            PersonIdBindingsDef personIdBindings,
            RoleLabelDef roleLabel,
            boolean exclusiveRoleLabel,
            boolean stampSourceColumn,
            List<RelPropertyDef> properties
    ) {
        static RelationshipRuleDef fromJson(JsonNode node) {
            List<String> tables = new ArrayList<>();
            for (JsonNode t : node.path("tables")) {
                tables.add(t.asText().toLowerCase(Locale.ROOT));
            }
            List<RelPropertyDef> props = new ArrayList<>();
            for (JsonNode p : node.path("properties")) {
                props.add(RelPropertyDef.fromJson(p));
            }
            PersonIdBindingsDef bindings = PersonIdBindingsDef.fromJson(node.path("personIdBindings"));
            boolean exclusive = node.path("exclusiveRoleLabel").asBoolean(
                    bindings != null && bindings.exclusiveRoleLabel());
            boolean stamp = node.path("stampSourceColumn").asBoolean(
                    bindings != null && bindings.stampSourceColumn());
            return new RelationshipRuleDef(
                    node.path("id").asText(""),
                    List.copyOf(tables),
                    node.path("relationshipType").asText(""),
                    node.path("fromNodeType").asText(""),
                    node.path("toNodeType").asText(""),
                    IdRefDef.fromJson(node.path("fromId")),
                    IdRefDef.fromJson(node.path("toId")),
                    bindings,
                    RoleLabelDef.fromJson(node.path("roleLabel")),
                    exclusive,
                    stamp,
                    List.copyOf(props)
            );
        }
    }

    public record VectorRoleSectionDef(String label, List<String> roleLabels, String format, String join) {
        static VectorRoleSectionDef fromJson(JsonNode node) {
            List<String> roleLabels = new ArrayList<>();
            for (JsonNode r : node.path("roleLabels")) {
                roleLabels.add(r.asText());
            }
            return new VectorRoleSectionDef(
                    node.path("label").asText(""),
                    List.copyOf(roleLabels),
                    node.path("format").asText("{role}:{personId}"),
                    node.path("join").asText(", ")
            );
        }
    }

    public record VectorTableEnrichmentDef(
            String roleBindingsRef,
            List<VectorRoleSectionDef> sections
    ) {
    }

    public record VectorDocumentEnrichmentDef(Map<String, VectorTableEnrichmentDef> byTable) {
        static VectorDocumentEnrichmentDef fromJson(JsonNode root) {
            Map<String, VectorTableEnrichmentDef> map = new LinkedHashMap<>();
            if (root == null || root.isMissingNode()) {
                return new VectorDocumentEnrichmentDef(Map.of());
            }
            root.fields().forEachRemaining(entry -> {
                JsonNode n = entry.getValue();
                List<VectorRoleSectionDef> sections = new ArrayList<>();
                for (JsonNode s : n.path("sections")) {
                    sections.add(VectorRoleSectionDef.fromJson(s));
                }
                map.put(entry.getKey().toLowerCase(Locale.ROOT), new VectorTableEnrichmentDef(
                        n.path("roleBindingsRef").asText("sql-role-columns"),
                        List.copyOf(sections)
                ));
            });
            return new VectorDocumentEnrichmentDef(Map.copyOf(map));
        }

        public VectorTableEnrichmentDef forTable(String table) {
            if (table == null) {
                return null;
            }
            return byTable.get(table.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String text = v.asText(null);
        return text == null || text.isBlank() ? null : text;
    }
}
