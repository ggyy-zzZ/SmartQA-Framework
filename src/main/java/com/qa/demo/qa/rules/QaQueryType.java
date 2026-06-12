package com.qa.demo.qa.rules;

import java.util.Map;
import java.util.Objects;

/**
 * 业务 queryType 枚举。
 * <p>
 * 历史上 {@code IntentDecision.queryType} 是字符串（P0 之前），如
 * {@code "person_role_list"} / {@code "company_certificate"}。
 * 引入本枚举后进入 6 个月双写期：
 * <ul>
 *   <li>{@code IntentDecision} / {@code InformationNeed} 同步持有 {@code String} 与枚举字段</li>
 *   <li>{@link #from(String)} 把历史字符串映射到枚举，缺省回退 {@link #UNKNOWN}</li>
 *   <li>业务比较统一走 {@code .name()} 或枚举相等</li>
 * </ul>
 * 6 个月后看板确认双写期不一致率 < 0.1% 才下掉 String 字段。
 */
public enum QaQueryType {
    COMPANY_PROFILE,
    COMPANY_CERTIFICATE,
    COMPANY_SEAL,
    COMPANY_COMPLIANCE,
    OPERATING_STATUS,

    PERSON_CERTIFICATE_LIST,
    PERSON_ROLE_LIST,
    PERSON_IDENTITY,

    TYPE_CATALOG_CERT,
    TYPE_CATALOG_PROFILE,
    TYPE_CATALOG_RELATION,
    TYPE_CATALOG_ROLE,
    PRODUCT_LINE_CATALOG,
    CURRENCY_CATALOG,
    SHAREHOLDER_TYPE_CATALOG,

    SEMANTIC,
    MIXED,
    UNKNOWN;

    private static final Map<String, QaQueryType> LEGACY = Map.ofEntries(
            Map.entry("company_profile",            COMPANY_PROFILE),
            Map.entry("company_certificate",        COMPANY_CERTIFICATE),
            Map.entry("company_seal",               COMPANY_SEAL),
            Map.entry("company_compliance",         COMPANY_COMPLIANCE),
            Map.entry("operating_status",           OPERATING_STATUS),

            Map.entry("person_certificate_list",    PERSON_CERTIFICATE_LIST),
            Map.entry("person_role_list",           PERSON_ROLE_LIST),
            Map.entry("person_identity",            PERSON_IDENTITY),

            Map.entry("type_catalog_cert",          TYPE_CATALOG_CERT),
            Map.entry("type_catalog_profile",       TYPE_CATALOG_PROFILE),
            Map.entry("type_catalog_relation",      TYPE_CATALOG_RELATION),
            Map.entry("type_catalog_role",          TYPE_CATALOG_ROLE),
            Map.entry("product_line_catalog",       PRODUCT_LINE_CATALOG),
            Map.entry("currency_catalog",           CURRENCY_CATALOG),
            Map.entry("shareholder_type_catalog",   SHAREHOLDER_TYPE_CATALOG),

            Map.entry("semantic",                   SEMANTIC),
            Map.entry("mixed",                      MIXED)
    );

    /**
     * 历史字符串 → 枚举。null / 空白 / 未知回退 {@link #UNKNOWN}。
     * <p>
     * 注意：与 {@link #name()} 严格区分大小写不同，本方法对入参 trim + toLowerCase。
     */
    public static QaQueryType from(String legacy) {
        if (legacy == null) {
            return UNKNOWN;
        }
        String key = legacy.trim().toLowerCase();
        if (key.isEmpty()) {
            return UNKNOWN;
        }
        QaQueryType mapped = LEGACY.get(key);
        return Objects.requireNonNullElse(mapped, UNKNOWN);
    }
}
