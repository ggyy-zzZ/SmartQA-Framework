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
 */
public class GraphCompanyFacetCatalog {

    private final Map<String, String> facetLabels;
    private final Map<String, List<String>> facetsByQueryType;

    public GraphCompanyFacetCatalog(Map<String, String> facetLabels, Map<String, List<String>> facetsByQueryType) {
        this.facetLabels = Map.copyOf(facetLabels);
        this.facetsByQueryType = Map.copyOf(facetsByQueryType);
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
        return new GraphCompanyFacetCatalog(labels, byType);
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
}
