package com.qa.demo.qa.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单节点类型定义（不可变 POJO）。
 *
 * <p>对应 {@code graph-node-definitions.json#nodeTypes.<Label>}：
 * 节点 Label、id 属性、key 策略、可入图字段清单。</p>
 */
public final class GraphNodeDefinition {

    private final String label;
    private final String idProperty;
    private final String keyStrategy;
    private final String keyColumn;
    private final String keyTemplate;
    private final List<String> labels;
    private final boolean cdcWritable;
    private final List<GraphNodeFieldSpec> properties;
    private final Map<String, GraphNodeFieldSpec> propertiesByName;

    public GraphNodeDefinition(
            String label,
            String idProperty,
            String keyStrategy,
            String keyColumn,
            String keyTemplate,
            List<String> labels,
            boolean cdcWritable,
            List<GraphNodeFieldSpec> properties
    ) {
        this.label = label == null ? "" : label;
        this.idProperty = idProperty == null ? "" : idProperty;
        this.keyStrategy = keyStrategy == null ? "physical_column" : keyStrategy;
        this.keyColumn = keyColumn == null ? "" : keyColumn;
        this.keyTemplate = keyTemplate == null ? "" : keyTemplate;
        this.labels = labels == null || labels.isEmpty() ? List.of(this.label) : List.copyOf(labels);
        this.cdcWritable = cdcWritable;
        this.properties = properties == null ? List.of() : List.copyOf(properties);
        Map<String, GraphNodeFieldSpec> byName = new LinkedHashMap<>();
        for (GraphNodeFieldSpec spec : this.properties) {
            if (spec != null && spec.name() != null && !spec.name().isBlank()) {
                byName.put(spec.name(), spec);
            }
        }
        this.propertiesByName = Collections.unmodifiableMap(byName);
    }

    /**
     * 从 Jackson 解析节点构建。
     */
    @SuppressWarnings("unchecked")
    public static GraphNodeDefinition from(Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        String label = stringOrNull(node.get("Label"));
        if (label == null) {
            return null;
        }
        String idProperty = stringOrNull(node.get("idProperty"));
        String keyStrategy = stringOrNull(node.get("keyStrategy"));
        String keyColumn = stringOrNull(node.get("keyColumn"));
        String keyTemplate = stringOrNull(node.get("keyTemplate"));
        Object labelsRaw = node.get("labels");
        List<String> labels = labelsRaw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of(label);
        boolean cdcWritable = boolOrFalse(node.get("cdcWritable"));

        List<GraphNodeFieldSpec> specs = List.of();
        Object propsRaw = node.get("properties");
        if (propsRaw instanceof List<?> list) {
            specs = list.stream()
                    .filter(java.util.Map.class::isInstance)
                    .map(o -> GraphNodeFieldSpec.from((Map<String, Object>) o))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        return new GraphNodeDefinition(label, idProperty, keyStrategy, keyColumn, keyTemplate, labels, cdcWritable, specs);
    }

    public String label() {
        return label;
    }

    public String idProperty() {
        return idProperty;
    }

    public String keyStrategy() {
        return keyStrategy;
    }

    public String keyColumn() {
        return keyColumn;
    }

    public String keyTemplate() {
        return keyTemplate;
    }

    public List<String> labels() {
        return Collections.unmodifiableList(labels);
    }

    public boolean isCdcWritable() {
        return cdcWritable;
    }

    public List<GraphNodeFieldSpec> properties() {
        return Collections.unmodifiableList(properties);
    }

    public Map<String, GraphNodeFieldSpec> propertiesByName() {
        return propertiesByName;
    }

    private static String stringOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private static boolean boolOrFalse(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return false;
    }
}