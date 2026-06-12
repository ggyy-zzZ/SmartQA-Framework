package com.qa.demo.qa.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载 {@code classpath:qa/graph-node-definitions.json} 并提供按 Label 的检索入口。
 *
 * <p>本类是字段白名单的 Java 侧镜像，与 Python {@code scripts/enterprise_pipeline/graph_node_definitions.py}
 * 消费同一份 JSON；任何字段名、截断上限、枚举字典的变更必须同步两侧。</p>
 *
 * <p>不在 {@link com.qa.demo.qa.config.store.AssistantConfigJsonLoader} 注册是为了避免
 * 影响 {@code config_bundle}（MySQL 缓存）的写入路径；本 JSON 仅 classpath 读取。</p>
 */
@Service
public class GraphNodeDefinitionsProperties {

    private static final String CLASSPATH_PATH = "qa/graph-node-definitions.json";

    private final Map<String, GraphNodeDefinition> nodeTypes;
    private final Map<String, String> enumDictRefs;
    private final int defaultMaxChars;
    private final String truncationSuffix;
    private final List<String> excludeKeys;

    /**
     * Spring 反射注入用的无参构造。
     * <p>默认从 classpath 加载 JSON；若 classpath 资源缺失则回退为空白名单（节点定义将
     * 全部落空，CdcFieldTruncator 退化为"不过滤"路径，不抛错）。</p>
     */
    public GraphNodeDefinitionsProperties() {
        this(loadFromClasspathOrNull());
    }

    private static JsonNode loadFromClasspathOrNull() {
        try (InputStream in = new ClassPathResource(CLASSPATH_PATH).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException | IllegalStateException ex) {
            return null;
        }
    }

    public GraphNodeDefinitionsProperties(ObjectMapper objectMapper) throws IOException {
        this(loadFromClasspath(objectMapper));
    }

    /**
     * 测试/构造入口：直接给定已解析的 JSON 树。
     */
    public GraphNodeDefinitionsProperties(JsonNode root) {
        if (root == null) {
            this.nodeTypes = Map.of();
            this.enumDictRefs = Map.of();
            this.defaultMaxChars = 4000;
            this.truncationSuffix = "…[Truncated]";
            this.excludeKeys = List.of();
            return;
        }
        JsonNode global = root.path("global");
        JsonNode trunc = global.path("truncation");
        this.defaultMaxChars = intOr(trunc.path("maxChars").asText("4000"), 4000);
        this.truncationSuffix = trunc.path("suffix").asText("…[Truncated]");

        List<String> excludes = new ArrayList<>();
        global.path("exclude").forEach(n -> excludes.add(n.asText()));
        this.excludeKeys = Collections.unmodifiableList(excludes);

        Map<String, String> enumRefs = new LinkedHashMap<>();
        root.path("enumDicts").fields().forEachRemaining(e ->
                enumRefs.put(e.getKey(), e.getValue().path("ref").asText(""))
        );
        this.enumDictRefs = Collections.unmodifiableMap(enumRefs);

        ObjectMapper mapper = new ObjectMapper();
        Map<String, GraphNodeDefinition> defs = new LinkedHashMap<>();
        JsonNode nt = root.path("nodeTypes");
        nt.fields().forEachRemaining(entry -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = mapper.convertValue(entry.getValue(), Map.class);
            GraphNodeDefinition def = GraphNodeDefinition.from(raw);
            if (def != null) {
                defs.put(def.label(), def);
            }
        });
        this.nodeTypes = Collections.unmodifiableMap(defs);
    }

    private static JsonNode loadFromClasspath(ObjectMapper objectMapper) throws IOException {
        try (InputStream in = new ClassPathResource(CLASSPATH_PATH).getInputStream()) {
            return objectMapper.readTree(in);
        }
    }

    public Map<String, GraphNodeDefinition> nodeTypes() {
        return nodeTypes;
    }

    public Map<String, String> enumDictRefs() {
        return enumDictRefs;
    }

    public int defaultMaxChars() {
        return defaultMaxChars;
    }

    public String truncationSuffix() {
        return truncationSuffix;
    }

    public List<String> excludeKeys() {
        return excludeKeys;
    }

    /**
     * 按 Label 取节点定义；找不到时返回 {@code null}。
     */
    public GraphNodeDefinition definition(String label) {
        if (label == null) {
            return null;
        }
        return nodeTypes.get(label);
    }

    /**
     * 取 Company 节点定义的便捷方法。
     */
    public GraphNodeDefinition companyDef() {
        return definition("Company");
    }

    /**
     * 取 Person 节点定义的便捷方法。
     */
    public GraphNodeDefinition personDef() {
        return definition("Person");
    }

    /**
     * 字段白名单内查找：物理列名 → 字段 spec。
     */
    public GraphNodeFieldSpec findFieldByPhysicalColumn(String label, String physicalColumn) {
        GraphNodeDefinition def = definition(label);
        if (def == null || physicalColumn == null) {
            return null;
        }
        for (GraphNodeFieldSpec spec : def.properties()) {
            if (spec.columns().contains(physicalColumn)) {
                return spec;
            }
        }
        return null;
    }

    private static int intOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}