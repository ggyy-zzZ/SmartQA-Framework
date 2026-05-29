package com.qa.demo.qa.cdc.graph;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 从 Debezium 行 JSON 按列名（含大小写不敏感）读取字段。
 */
final class CdcRowFieldReader {

    private CdcRowFieldReader() {
    }

    static String firstText(JsonNode row, Iterable<String> columns) {
        if (row == null || row.isNull() || columns == null) {
            return null;
        }
        for (String column : columns) {
            String value = textAt(row, column);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String textAt(JsonNode row, String column) {
        if (row == null || column == null || column.isBlank()) {
            return null;
        }
        JsonNode direct = row.path(column);
        if (!direct.isMissingNode() && !direct.isNull()) {
            return normalize(direct);
        }
        String lower = column.toLowerCase(Locale.ROOT);
        Iterator<Map.Entry<String, JsonNode>> fields = row.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(lower)) {
                return normalize(entry.getValue());
            }
        }
        return null;
    }

    static boolean hasColumn(JsonNode row, String column) {
        if (row == null || column == null) {
            return false;
        }
        if (!row.path(column).isMissingNode()) {
            return true;
        }
        String lower = column.toLowerCase(Locale.ROOT);
        Iterator<String> names = row.fieldNames();
        while (names.hasNext()) {
            if (names.next().equalsIgnoreCase(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.isNumber() ? node.asText() : node.asText(null);
        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text.trim();
    }
}
