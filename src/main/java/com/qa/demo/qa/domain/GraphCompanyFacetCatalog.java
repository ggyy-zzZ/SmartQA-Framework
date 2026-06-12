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
 * 公司图谱证据片段应包含的 facet（classpath:qa/graph-company-facets.json）。
 *
 * <p>facet → 白名单字段名映射（{@code facetFields}）由 graph-node-definitions.json
 * 的 property 名集合裁剪，避免出现「白名单里没声明但仍输出」的情况。snippet 阶段
 * 按 {@link #fieldsForFacet(String)} 投影，仅输出该 facet 对应的字段。</p>
 */
public class GraphCompanyFacetCatalog {

    private final Map<String, String> facetLabels;
    private final Map<String, List<String>> facetsByQueryType;
    private final Map<String, List<String>> facetFields;

    public GraphCompanyFacetCatalog(
            Map<String, String> facetLabels,
            Map<String, List<String>> facetsByQueryType,
            Map<String, List<String>> facetFields
    ) {
        this.facetLabels = Map.copyOf(facetLabels);
        this.facetsByQueryType = Map.copyOf(facetsByQueryType);
        this.facetFields = facetFields == null ? Map.of() : Map.copyOf(facetFields);
    }

    public static GraphCompanyFacetCatalog loadDefault(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) throws Exception {
        JsonNode root = configLoader.readTree("graph-company-facets");
        Map<String, String> labels = new LinkedHashMap<>();
        root.path("facetLabels").fields().forEachRemaining(entry ->
                labels.put(entry.getKey(), entry.getValue().asText(entry.getKey()))
        );
        Map<String, List<String>> byType = new LinkedHashMap<>();
        JsonNode byQuery = root.path("facetsByQueryType");
        byQuery.fieldNames().forEachRemaining(type -> {
            List<String> facets = new ArrayList<>();
            for (JsonNode item : byQuery.path(type)) {
                facets.add(item.asText());
            }
            byType.put(type, List.copyOf(facets));
        });
        Map<String, List<String>> fields = new LinkedHashMap<>();
        root.path("facetFields").fields().forEachRemaining(entry -> {
            List<String> list = new ArrayList<>();
            for (JsonNode item : entry.getValue()) {
                list.add(item.asText());
            }
            fields.put(entry.getKey(), List.copyOf(list));
        });
        return new GraphCompanyFacetCatalog(labels, byType, fields);
    }

    public List<String> facetsForQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return facetsByQueryType.getOrDefault("default", List.of());
        }
        String key = queryType.trim().toLowerCase(Locale.ROOT);
        return facetsByQueryType.getOrDefault(key, facetsByQueryType.getOrDefault("default", List.of()));
    }

    public String label(String facetKey) {
        return facetLabels.getOrDefault(facetKey, facetKey);
    }

    /**
     * 单个 facet 涉及的「白名单字段名」列表；未声明时返回空列表（snippet 阶段
     * 会按 facetsByQueryType 顺序遍历，不应命中未声明的 facet）。
     */
    public List<String> fieldsForFacet(String facetKey) {
        if (facetKey == null) {
            return List.of();
        }
        return facetFields.getOrDefault(facetKey, List.of());
    }

    /**
     * 合并多个 facet 的字段名（去重，按声明顺序）。用于 GraphCompanyFullProfileQuery
     * 按公司 id 一次性拉取「全 facet 字段」。
     */
    public List<String> fieldsForFacets(List<String> facetKeys) {
        if (facetKeys == null || facetKeys.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (String k : facetKeys) {
            for (String f : fieldsForFacet(k)) {
                seen.putIfAbsent(f, Boolean.TRUE);
            }
        }
        return List.copyOf(seen.keySet());
    }

    public Map<String, List<String>> facetFields() {
        return facetFields;
    }
}
