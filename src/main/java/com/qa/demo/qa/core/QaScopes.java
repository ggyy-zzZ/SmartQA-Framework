package com.qa.demo.qa.core;

/**
 * 多轮与主动学习使用的 scope 常量及归一化。
 */
public final class QaScopes {

    public static final String ENTERPRISE = "enterprise";

    private QaScopes() {
    }

    /** 统一归一化为企业知识库 scope（不再区分 personal）。 */
    public static String normalize(String scope) {
        return ENTERPRISE;
    }
}
