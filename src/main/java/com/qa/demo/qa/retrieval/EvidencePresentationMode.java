package com.qa.demo.qa.retrieval;

import java.util.Locale;

/**
 * 证据呈现模式：控制检索上限与送入 LLM 的条数。
 */
public enum EvidencePresentationMode {
    /** 尽量完整呈现（默认） */
    FULL,
    /** 摘要模式，受 {@code retrieval-top-k} 等紧凑上限约束 */
    COMPACT;

    public static EvidencePresentationMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("full".equals(normalized) || "complete".equals(normalized) || "完整".equals(normalized)) {
            return FULL;
        }
        if ("compact".equals(normalized) || "summary".equals(normalized) || "摘要".equals(normalized)) {
            return COMPACT;
        }
        return null;
    }
}
