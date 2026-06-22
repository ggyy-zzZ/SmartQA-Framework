package com.qa.demo.qa.config;

/**
 * 语义 Schema 中可 DISTINCT 查询的列引用（表/列/标签来自 semantic-schema.json）。
 */
public record SemanticSchemaColumnRef(
        String entityId,
        String table,
        String column,
        String label,
        String enumField,
        String softDeleteColumn
) {
}
