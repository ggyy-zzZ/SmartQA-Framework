package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 意图槽位校验与轻量规范化（LLM 输出后、规则 enrich 前）。
 */
public final class IntentSlots {

    private static final Pattern PERSON_NAME = Pattern.compile("^[\\p{IsHan}·]{2,12}$");
    private static final Pattern PERSON_NOISE_SUFFIX = Pattern.compile("(是|的|在)$");

    static final Set<String> VALID_INTENTS = Set.of(
            "graph", "document", "vector", "mysql", "sql", "hybrid", "unknown"
    );
    static final Set<String> VALID_QUERY_TYPES = Set.of(
            "person_role_list", "person_certificate_list", "company_profile", "company_certificate",
            "company_seal", "shareholder", "relation", "aggregate", "policy", "semantic", "mixed", "unknown"
    );
    static final Set<String> VALID_ROLE_FOCUS = Set.of(
            "legal_rep", "director", "supervisor", "shareholder", "any"
    );

    private IntentSlots() {
    }

    public static IntentDecision normalize(IntentDecision raw) {
        if (raw == null) {
            return new IntentDecision("unknown", 0.0, "null_decision");
        }
        String intent = normalizeToken(raw.intent(), VALID_INTENTS, "unknown");
        String queryType = normalizeToken(raw.queryType(), VALID_QUERY_TYPES, "");
        String personName = sanitizePersonName(raw.personName());
        String roleFocus = normalizeToken(raw.roleFocus(), VALID_ROLE_FOCUS, "any");
        double confidence = clamp(raw.confidence());
        String reason = raw.reason() == null ? "" : raw.reason().trim();
        var companyHints = raw.companyHints() == null ? java.util.List.<String>of() : raw.companyHints().stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        return new IntentDecision(intent, confidence, reason, queryType, personName, companyHints, roleFocus);
    }

    /**
     * 高置信 LLM 结果是否已具备检索所需槽位，可跳过规则 enrich。
     */
    public static boolean isRetrievalReady(IntentDecision d) {
        if (d == null || "unknown".equalsIgnoreCase(d.intent())) {
            return false;
        }
        if (d.isPersonRoleListQuery()) {
            return d.hasPersonFocus() && hasRoleFocus(d);
        }
        if (d.isPersonCertificateListQuery()) {
            return d.hasPersonFocus();
        }
        if ("company_profile".equalsIgnoreCase(d.queryType())
                || "company_certificate".equalsIgnoreCase(d.queryType())
                || "company_seal".equalsIgnoreCase(d.queryType())) {
            return d.hasCompanyHints();
        }
        if ("shareholder".equalsIgnoreCase(d.queryType()) || "relation".equalsIgnoreCase(d.queryType())) {
            return d.hasCompanyHints() || d.hasPersonFocus();
        }
        if ("aggregate".equalsIgnoreCase(d.queryType()) || "sql".equalsIgnoreCase(d.intent())) {
            return true;
        }
        if ("policy".equalsIgnoreCase(d.queryType()) || "document".equalsIgnoreCase(d.intent())) {
            return true;
        }
        if ("graph".equalsIgnoreCase(d.intent())) {
            return d.hasPersonFocus() || d.hasCompanyHints();
        }
        if ("vector".equalsIgnoreCase(d.intent()) || "semantic".equalsIgnoreCase(d.queryType())) {
            if (d.isPersonRoleListQuery()) {
                return d.hasPersonFocus() && hasRoleFocus(d);
            }
            return true;
        }
        if ("hybrid".equalsIgnoreCase(d.intent())) {
            return d.hasPersonFocus() || d.hasCompanyHints();
        }
        return d.confidence() >= 0.8;
    }

    public static String sanitizePersonName(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim();
        n = PERSON_NOISE_SUFFIX.matcher(n).replaceAll("").trim();
        if (n.isEmpty()) {
            return "";
        }
        return PERSON_NAME.matcher(n).matches() ? n : "";
    }

    private static boolean hasRoleFocus(IntentDecision d) {
        String rf = d.roleFocus();
        return rf != null && !rf.isBlank() && !"any".equalsIgnoreCase(rf);
    }

    private static String normalizeToken(String value, Set<String> allowed, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String token = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(token) ? token : fallback;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
