package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 从 Debezium 行事件中解析实体主键（兼容 tdcomp 物理列名 id 与规范名 company_id 等）。
 */
public final class CdcEntityIdResolver {

    private CdcEntityIdResolver() {
    }

    public static JsonNode dataRow(JsonNode after, JsonNode before, String op) {
        if ("d".equals(op)) {
            return hasRowData(before) ? before : after;
        }
        return hasRowData(after) ? after : before;
    }

    public static boolean hasRowData(JsonNode row) {
        return row != null && !row.isNull() && !row.isMissingNode() && row.isObject() && !row.isEmpty();
    }

    public static String resolveEntityId(String table, JsonNode row) {
        if (!hasRowData(row)) {
            return null;
        }
        return switch (table) {
            case "company" -> firstNonBlank(row, "company_id", "id", "corp_id", "enterprise_id");
            case "employee" -> firstNonBlank(row, "employee_id", "id", "emp_id");
            case "branch" -> firstNonBlank(row, "branch_id", "id");
            case "partner" -> firstNonBlank(row, "partner_id", "id");
            default -> firstNonBlank(row, "id");
        };
    }

    private static String firstNonBlank(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode node = row.path(field);
            if (node.isMissingNode() || node.isNull()) {
                continue;
            }
            String text = node.asText(null);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
