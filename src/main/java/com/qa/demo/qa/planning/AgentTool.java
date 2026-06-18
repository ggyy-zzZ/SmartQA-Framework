package com.qa.demo.qa.planning;

/**
 * Agent 子任务可调用的检索/计算工具（与具体业务表无耦合）。
 */
public enum AgentTool {
    STRUCTURED_RETRIEVE,
    AGGREGATE_COUNT,
    DOCUMENT_RETRIEVE,
    SYNTHESIZE;

    public static AgentTool parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return STRUCTURED_RETRIEVE;
        }
        return switch (raw.trim().toLowerCase()) {
            case "aggregate_count", "count", "sql_count" -> AGGREGATE_COUNT;
            case "document_retrieve", "document", "vector" -> DOCUMENT_RETRIEVE;
            case "synthesize", "compute", "compare" -> SYNTHESIZE;
            default -> STRUCTURED_RETRIEVE;
        };
    }

    public String wireName() {
        return name().toLowerCase();
    }
}
