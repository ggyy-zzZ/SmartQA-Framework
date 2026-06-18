package com.qa.demo.qa.core;

import java.util.Locale;
import java.util.Set;

/**
 * LLM 决定的检索执行策略（与业务场景解耦，描述「怎么查」而非「查什么」）。
 */
public enum RetrievalStrategy {
    AGGREGATE_COUNT,
    STRUCTURED_LIST,
    TYPE_CATALOG,
    INSTANCE_FACT,
    GRAPH_RELATIONAL,
    SEMANTIC_RAG,
    CLARIFY,
    UNKNOWN;

    private static final Set<String> KNOWN = Set.of(
            "aggregate_count",
            "structured_list",
            "type_catalog",
            "instance_fact",
            "graph_relational",
            "semantic_rag",
            "clarify",
            "unknown"
    );

    public static RetrievalStrategy fromToken(String token) {
        if (token == null || token.isBlank()) {
            return UNKNOWN;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN.contains(normalized)) {
            return UNKNOWN;
        }
        return RetrievalStrategy.valueOf(normalized.toUpperCase(Locale.ROOT));
    }

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean skipsTopKRecall() {
        return this == AGGREGATE_COUNT || this == TYPE_CATALOG;
    }
}
