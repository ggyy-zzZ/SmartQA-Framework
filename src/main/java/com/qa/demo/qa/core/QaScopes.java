package com.qa.demo.qa.core;

/**
 * 多轮与主动学习使用的 scope 常量及归一化。
 */
public final class QaScopes {

    public static final String ENTERPRISE = "enterprise";
    public static final String PERSONAL = "personal";

    private QaScopes() {
    }

    public static String normalize(String scope) {
        if (scope == null || scope.isBlank()) {
            return ENTERPRISE;
        }
        String raw = scope.trim().toLowerCase();
        if (raw.contains("个人") || raw.equals("personal") || raw.equals("me")) {
            return PERSONAL;
        }
        return ENTERPRISE;
    }
}
