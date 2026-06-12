package com.qa.demo.qa.retrieval.graph;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.CertificateSealEnumCatalog;
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
            GraphCompanyFacetCatalog facetCatalog,
            CertificateSealEnumCatalog enumCatalog
    ) {
        Map<String, String> scalars = new LinkedHashMap<>();
        scalars.put("status", pickDisplay(record, "statusDisplay", "status"));
        scalars.put("entityType", pickDisplay(record, "entityTypeDisplay", "entityType"));
        scalars.put("entityCategory", pickDisplay(record, "entityCategoryDisplay", "entityCategory"));
        scalars.put("registeredAddress", safeString(record, "registeredAddress"));
        scalars.put("officeAddress", safeString(record, "officeAddress"));
        scalars.put("businessScope", safeString(record, "businessScope"));
        scalars.put("registeredCapital", buildCapital(record));
        scalars.put("alias", safeString(record, "alias"));
        scalars.put("contactPhone", safeString(record, "contactPhone"));
        scalars.put("contactEmail", safeString(record, "contactEmail"));
        scalars.put("establishedDate", safeString(record, "establishedDate"));
        scalars.put("modifytime", safeString(record, "modifytime"));

        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("productLines", safeList(record, "productLines"));
        lists.put("shareholders", safeList(record, "shareholders"));
        lists.put("roles", safeList(record, "roles"));
        lists.put("certificates", safeList(record, "certificates"));
        lists.put("seals", safeList(record, "seals"));
        lists.put("attachments", safeList(record, "attachments"));
        lists.put("certificatePersons", safeList(record, "certificatePersons"));
        lists.put("changeEvents", safeList(record, "changeEvents"));

        String queryType = intent == null ? "" : intent.queryType();
        List<String> facets = facetCatalog.facetsForQueryType(queryType);
        return buildSnippet(scalars, lists, facets, facetCatalog, enumCatalog);
    }

    /**
     * 直接按 facetKeys 投影（不需要 IntentDecision）。其它辅助调用方使用。
     */
    public static String buildSnippet(
            Record record,
            List<String> facetKeys,
            GraphCompanyFacetCatalog facetCatalog,
            CertificateSealEnumCatalog enumCatalog
    ) {
        Map<String, String> scalars = new LinkedHashMap<>();
        scalars.put("status", pickDisplay(record, "statusDisplay", "status"));
        scalars.put("entityType", pickDisplay(record, "entityTypeDisplay", "entityType"));
        scalars.put("entityCategory", pickDisplay(record, "entityCategoryDisplay", "entityCategory"));
        scalars.put("registeredAddress", safeString(record, "registeredAddress"));
        scalars.put("officeAddress", safeString(record, "officeAddress"));
        scalars.put("businessScope", safeString(record, "businessScope"));
        scalars.put("registeredCapital", buildCapital(record));
        scalars.put("alias", safeString(record, "alias"));
        scalars.put("contactPhone", safeString(record, "contactPhone"));
        scalars.put("contactEmail", safeString(record, "contactEmail"));
        scalars.put("establishedDate", safeString(record, "establishedDate"));
        scalars.put("modifytime", safeString(record, "modifytime"));

        Map<String, String> lists = new LinkedHashMap<>();
        lists.put("productLines", safeList(record, "productLines"));
        lists.put("shareholders", safeList(record, "shareholders"));
        lists.put("roles", safeList(record, "roles"));
        lists.put("certificates", safeList(record, "certificates"));
        lists.put("seals", safeList(record, "seals"));
        lists.put("attachments", safeList(record, "attachments"));
        lists.put("certificatePersons", safeList(record, "certificatePersons"));
        lists.put("changeEvents", safeList(record, "changeEvents"));

        return buildSnippet(scalars, lists, facetKeys, facetCatalog, enumCatalog);
    }

    /**
     * 注册资本 + 币种合并到同一字符串；币种缺失时仅返回数字。
     */
    private static String buildCapital(Record record) {
        String amount = safeString(record, "registeredCapital");
        if (amount == null || amount.isBlank()) {
            return "";
        }
        String currency = safeString(record, "capitalCurrency");
        return currency == null || currency.isBlank() ? amount : (amount + " " + currency);
    }

    public static String buildSnippet(
            Map<String, String> scalars,
            Map<String, String> lists,
            List<String> facetKeys,
            GraphCompanyFacetCatalog facetCatalog,
            CertificateSealEnumCatalog enumCatalog
    ) {
        Map<String, String> displayLists = applyEnumLabels(lists, enumCatalog);
        List<String> parts = new ArrayList<>();
        for (String key : facetKeys) {
            String value = scalars.containsKey(key) ? scalars.get(key) : displayLists.get(key);
            if (!hasContent(value)) {
                continue;
            }
            parts.add(facetCatalog.label(key) + "=" + value);
        }
        return String.join("; ", parts);
    }

    private static Map<String, String> applyEnumLabels(
            Map<String, String> lists,
            CertificateSealEnumCatalog enumCatalog
    ) {
        if (enumCatalog == null || lists == null || lists.isEmpty()) {
            return lists == null ? Map.of() : lists;
        }
        Map<String, String> out = new LinkedHashMap<>(lists);
        if (out.containsKey("certificates")) {
            out.put("certificates", enumCatalog.formatCertificateListForSnippet(out.get("certificates")));
        }
        if (out.containsKey("seals")) {
            out.put("seals", enumCatalog.formatSealListForSnippet(out.get("seals")));
        }
        return out;
    }

    private static boolean hasContent(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return !"[]".equals(trimmed) && !"null".equalsIgnoreCase(trimmed);
    }

    private static String pickDisplay(Record record, String displayKey, String fallbackKey) {
        String display = safeString(record, displayKey);
        if (!display.isBlank()) {
            return display;
        }
        return safeString(record, fallbackKey);
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
