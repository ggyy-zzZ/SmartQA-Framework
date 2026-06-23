package com.qa.demo.qa.config;

import java.util.List;

/**
 * 问句中命中的枚举属性筛选（列 + 匹配到的码值列表）。
 */
public record SemanticSchemaEnumFilter(
        String column,
        String enumField,
        List<String> matchedCodes
) {
}
