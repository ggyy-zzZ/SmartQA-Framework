package com.qa.demo.qa.retrieval.graph;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.GraphCompanyFacetCatalog;
import org.neo4j.driver.Record;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Neo4j 公司查询 Record 格式化为 {@code ContextChunk} 用的 snippet（按 queryType 裁剪 facet）。
 */
public final class GraphCompanySnippetBuilder {

    private GraphCompanySnippetBuilder() {
    }

    public static String buildSnippet(
            Record record,
            IntentDecision intent,
            GraphCompanyFacetCatalog facetCatalog
    ) {
        Map<String, String> scalars = new LinkedHashMap<>();
        scalars.put("status", safeString(record, "status"));
        scalars.put("entityType", safeString(record, "entityType"));
        scalars.put("entityCategory", safeString(record, "entityCategory"));
        scalars.put("registeredAddress", safeString(record, "registeredAddress"));
        scalars.put("businessScope", safeString(record, "businessScope"));

        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("productLines", safeList(record, "productLines"));
        lists.put("shareholders", safeList(record, "shareholders"));
        lists.put("roles", safeList(record, "roles"));
        lists.put("certificates", safeList(record, "certificates"));
        lists.put("seals", safeList(record, "seals"));

        String queryType = intent == null ? "" : intent.queryType();
        List<String> facets = facetCatalog.facetsForQueryType(queryType);
        return buildSnippet(scalars, lists, facets, facetCatalog);
    }

    static String buildSnippet(
            Map<String, String> scalars,
            Map<String, String> lists,
            List<String> facetKeys,
            GraphCompanyFacetCatalog facetCatalog
    ) {
        List<String> parts = new ArrayList<>();
        for (String key : facetKeys) {
            String value = scalars.containsKey(key) ? scalars.get(key) : lists.get(key);
            if (!hasContent(value)) {
                continue;
            }
            parts.add(facetCatalog.label(key) + "=" + value);
        }
        return String.join("; ", parts);
    }

    private static boolean hasContent(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return !"[]".equals(trimmed) && !"null".equalsIgnoreCase(trimmed);
    }

    private static String safeString(Record record, String key) {
        if (record.get(key).isNull()) {
            return "";
        }
        return record.get(key).asString("");
    }

    private static String safeList(Record record, String key) {
        if (record.get(key).isNull()) {
            return "";
        }
        List<Object> raw = record.get(key).asList();
        if (raw.isEmpty()) {
            return "";
        }
        return raw.toString();
    }
}
