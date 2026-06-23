package com.qa.demo.qa.config;

/**
 * 语义 Schema 中「子公司 → 母公司」引用列（来自 relationships 配置）。
 */
public record SemanticSchemaChildCompanyRef(
        String childTable,
        String childColumn,
        String parentColumn,
        String softDeleteColumn
) {
}
