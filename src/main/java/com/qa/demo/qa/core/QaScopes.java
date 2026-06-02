package com.qa.demo.qa.core;

/**
 * 多轮与主动学习使用的 scope 常量及归一化。
 */
public final class QaScopes {

    public static final String ENTERPRISE = "enterprise";

    private QaScopes() {
    }

    /** 归一化 scope；空值默认 enterprise。 */
    public static String normalize(String scope) {
        if (scope == null || scope.isBlank()) {
            return ENTERPRISE;
        }
        return scope.trim().toLowerCase();
    }
}
