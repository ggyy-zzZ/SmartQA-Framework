package com.qa.demo.qa.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单字段定义（不可变 POJO）。
 *
 * <p>字段元数据来源于 {@code classpath:qa/graph-node-definitions.json}，
 * 由 Python 离线灌库与 Java CDC 写入共用同一份定义，保证两端的可入图字段、
 * 截断上限、枚举字典完全一致。</p>
 */
public final class GraphNodeFieldSpec {

    private final String name;
    private final List<String> columns;
    private final String type;
    private final int maxChars;
    private final String enumDictKey;
    private final boolean required;
    private final boolean indexed;

    public GraphNodeFieldSpec(
            String name,
            List<String> columns,
            String type,
            int maxChars,
            String enumDictKey,
            boolean required,
            boolean indexed
    ) {
        this.name = name == null ? "" : name;
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.type = type == null ? "string" : type;
        this.maxChars = Math.max(0, maxChars);
        this.enumDictKey = enumDictKey == null || enumDictKey.isBlank() ? null : enumDictKey;
        this.required = required;
        this.indexed = indexed;
    }

    /**
     * 从 Jackson 解析节点构建。
     *
     * @param node 单字段 JSON 对象（{@code {"name", "columns", "type", "maxChars", "enumDictKey", "required", "indexed"}}）
     */
    public static GraphNodeFieldSpec from(Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        String name = stringOrNull(node.get("name"));
        if (name == null) {
            return null;
        }
        Object colsRaw = node.get("columns");
        List<String> cols = colsRaw instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        String type = stringOrNull(node.get("type"));
        int maxChars = intOrZero(node.get("maxChars"));
        String enumDictKey = stringOrNull(node.get("enumDictKey"));
        boolean required = boolOrFalse(node.get("required"));
        boolean indexed = boolOrFalse(node.get("indexed"));
        return new GraphNodeFieldSpec(name, cols, type, maxChars, enumDictKey, required, indexed);
    }

    public String name() {
        return name;
    }

    public List<String> columns() {
        return Collections.unmodifiableList(columns);
    }

    public String type() {
        return type;
    }

    public int maxChars() {
        return maxChars;
    }

    /**
     * 未显式设置 maxChars 时回退到全局上限。
     */
    public int maxCharsOrDefault(int fallback) {
        return maxChars > 0 ? maxChars : fallback;
    }

    public String enumDictKey() {
        return enumDictKey;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isIndexed() {
        return indexed;
    }

    private static String stringOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    private static int intOrZero(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean boolOrFalse(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return false;
    }
}