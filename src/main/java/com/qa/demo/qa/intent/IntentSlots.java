package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalStrategy;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 意图槽位校验与轻量规范化（LLM 输出后、规则 enrich 前）。
 */
public final class IntentSlots {

    private static final Pattern PERSON_NAME = Pattern.compile("^[\\p{IsHan}·]{2,12}$");
    private static final Pattern PERSON_NOISE_SUFFIX = Pattern.compile("(是|的|在)$");
    private static final Set<String> PERSON_NAME_BLOCKLIST = Set.of(
            "现在", "刚才", "这些", "那些", "上面", "下面", "它们", "他们", "她们", "我们", "你们",
            "这里", "那里", "这个", "那个", "哪些", "什么", "怎么", "为何", "为什么",
            "只要", "只要不", "不是", "并非", "如果", "凡是", "其他",
            "无法确定", "不确定", "未知", "不详", "无", "没有", "未找到", "查不到"
    );

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
    static final Set<String> VALID_RETRIEVAL_STRATEGIES = Set.of(
            "aggregate_count", "structured_list", "type_catalog", "instance_fact",
            "graph_relational", "semantic_rag", "clarify", "unknown"
    );

    private IntentSlots() {
    }

    public static IntentDecision normalize(IntentDecision raw) {
        if (raw == null) {
            return new IntentDecision("unknown", 0.0, "null_decision");
        }
        String personName = sanitizePersonName(raw.personName());
        String roleFocus = normalizeToken(raw.roleFocus(), VALID_ROLE_FOCUS, "any");
        double confidence = clamp(raw.confidence());
        String reason = raw.reason() == null ? "" : raw.reason().trim();
        var companyHints = raw.companyHints() == null ? java.util.List.<String>of() : raw.companyHints().stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        Integer personEmployeeId = raw.personEmployeeId();
        if (personEmployeeId != null && personEmployeeId <= 0) {
            personEmployeeId = null;
        }
        String queryTypeHint = normalizeToken(raw.queryType(), VALID_QUERY_TYPES, "");
        String intentHint = normalizeToken(raw.intent(), VALID_INTENTS, "");
        String retrievalStrategy = normalizeRetrievalStrategy(raw.retrievalStrategy(), queryTypeHint, intentHint);
        DerivedChannels derived = deriveChannels(retrievalStrategy, personName, !companyHints.isEmpty(), roleFocus);
        String intent = intentHint.isBlank() || "unknown".equals(intentHint)
                ? derived.intent()
                : intentHint;
        String queryType = queryTypeHint.isBlank()
                ? derived.queryType()
                : queryTypeHint;
        if (intent.isBlank()) {
            intent = "unknown";
        }
        return new IntentDecision(
                intent, confidence, reason, queryType, personName, companyHints, roleFocus, personEmployeeId,
                retrievalStrategy);
    }

    private static String normalizeRetrievalStrategy(String strategy, String queryType, String intent) {
        String token = normalizeToken(strategy, VALID_RETRIEVAL_STRATEGIES, "");
        if (!token.isBlank()) {
            return token;
        }
        if ("aggregate".equalsIgnoreCase(queryType)) {
            return RetrievalStrategy.AGGREGATE_COUNT.token();
        }
        if ("sql".equalsIgnoreCase(intent) && "aggregate".equalsIgnoreCase(queryType)) {
            return RetrievalStrategy.AGGREGATE_COUNT.token();
        }
        return "";
    }

    /** 由 LLM retrievalStrategy + 槽位推导内部 intent/queryType（规则引擎路径仍可直接传入）。 */
    static DerivedChannels deriveChannels(
            String strategyToken,
            String personName,
            boolean hasCompanyHints,
            String roleFocus
    ) {
        RetrievalStrategy strategy = RetrievalStrategy.fromToken(strategyToken);
        boolean hasPerson = personName != null && !personName.isBlank();
        return switch (strategy) {
            case AGGREGATE_COUNT -> new DerivedChannels("sql", "aggregate");
            case TYPE_CATALOG -> new DerivedChannels("mysql", "semantic");
            case STRUCTURED_LIST -> {
                if (hasPerson) {
                    if (hasRoleFocusValue(roleFocus)) {
                        yield new DerivedChannels("hybrid", "person_role_list");
                    }
                    yield new DerivedChannels("mysql", "person_certificate_list");
                }
                if (hasCompanyHints) {
                    yield new DerivedChannels("mysql", "company_profile");
                }
                yield new DerivedChannels("hybrid", "semantic");
            }
            case INSTANCE_FACT -> {
                if (hasCompanyHints) {
                    yield new DerivedChannels("mysql", "company_profile");
                }
                yield new DerivedChannels("vector", "semantic");
            }
            case GRAPH_RELATIONAL -> {
                if (hasPerson) {
                    if (hasRoleFocusValue(roleFocus)) {
                        yield new DerivedChannels("hybrid", "person_role_list");
                    }
                    yield new DerivedChannels("hybrid", "person_certificate_list");
                }
                if (hasCompanyHints) {
                    yield new DerivedChannels("hybrid", "relation");
                }
                yield new DerivedChannels("hybrid", "semantic");
            }
            case SEMANTIC_RAG -> new DerivedChannels("vector", "semantic");
            case CLARIFY -> new DerivedChannels("unknown", "unknown");
            case UNKNOWN -> new DerivedChannels("", "");
        };
    }

    record DerivedChannels(String intent, String queryType) {
    }

    /** 会话遗留 queryType 转 retrievalStrategy，供多轮上下文传递。 */
    public static String strategyHintFromQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return "";
        }
        return switch (queryType.trim().toLowerCase(Locale.ROOT)) {
            case "aggregate" -> RetrievalStrategy.AGGREGATE_COUNT.token();
            case "person_role_list", "person_certificate_list", "shareholder", "relation" ->
                    RetrievalStrategy.GRAPH_RELATIONAL.token();
            case "company_profile", "company_certificate", "company_seal" ->
                    RetrievalStrategy.INSTANCE_FACT.token();
            default -> "";
        };
    }

    /**
     * 高置信 LLM 结果是否已具备检索所需槽位，可跳过规则 enrich。
     */
    public static boolean isRetrievalReady(IntentDecision d) {
        if (d == null) {
            return false;
        }
        RetrievalStrategy strategy = d.resolvedRetrievalStrategy();
        if (strategy == RetrievalStrategy.AGGREGATE_COUNT || strategy == RetrievalStrategy.TYPE_CATALOG) {
            return true;
        }
        if (strategy == RetrievalStrategy.SEMANTIC_RAG) {
            return d.confidence() >= 0.5;
        }
        if (strategy == RetrievalStrategy.CLARIFY) {
            return false;
        }
        if ("unknown".equalsIgnoreCase(d.intent())) {
            return strategy != RetrievalStrategy.UNKNOWN && d.confidence() >= 0.7;
        }
        if (d.isPersonRoleListQuery()) {
            return d.hasPersonFocus() && hasRoleFocus(d);
        }
        if (d.isPersonCertificateListQuery()) {
            return d.hasPersonEmployeeId() || d.hasPersonFocus();
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
        if (RetrievalStrategy.AGGREGATE_COUNT.token().equalsIgnoreCase(d.retrievalStrategy())) {
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
        if (PERSON_NAME_BLOCKLIST.contains(n)) {
            return "";
        }
        if (looksLikeRuleClause(n)) {
            return "";
        }
        return PERSON_NAME.matcher(n).matches() ? n : "";
    }

    private static boolean looksLikeRuleClause(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return token.startsWith("只要")
                || token.startsWith("不是")
                || token.startsWith("并非")
                || token.startsWith("如果")
                || token.contains("失效")
                || token.contains("存续")
                || token.contains("在业");
    }

    private static boolean hasRoleFocus(IntentDecision d) {
        return hasRoleFocusValue(d.roleFocus());
    }

    private static boolean hasRoleFocusValue(String roleFocus) {
        return roleFocus != null && !roleFocus.isBlank() && !"any".equalsIgnoreCase(roleFocus);
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
