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
    /** 角色焦点 token 格式（具体枚举见 enterprise-lexicon.json，由问句推断，不由 LLM 输出）。 */
    private static final Pattern ROLE_FOCUS_TOKEN = Pattern.compile("^[a-z][a-z0-9_]*$");
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
        String roleFocus = normalizeRoleFocus(raw.roleFocus());
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
        String retrievalStrategy = normalizeToken(raw.retrievalStrategy(), VALID_RETRIEVAL_STRATEGIES, "");
        String intentHint = normalizeToken(raw.intent(), VALID_INTENTS, "");
        String intent = intentHint.isBlank() || "unknown".equals(intentHint)
                ? deriveIntent(retrievalStrategy, raw.intent())
                : intentHint;
        if (intent.isBlank()) {
            intent = "unknown";
        }
        return new IntentDecision(
                intent,
                confidence,
                reason,
                personName,
                companyHints,
                roleFocus,
                personEmployeeId,
                retrievalStrategy
        );
    }

    private static String deriveIntent(String retrievalStrategy, String fallbackIntent) {
        RetrievalStrategy strategy = RetrievalStrategy.fromToken(retrievalStrategy);
        if (strategy == RetrievalStrategy.UNKNOWN) {
            return fallbackIntent == null ? "" : fallbackIntent.trim();
        }
        return switch (strategy) {
            case AGGREGATE_COUNT -> "sql";
            case TYPE_CATALOG -> "mysql";
            case STRUCTURED_LIST -> "mysql";
            case INSTANCE_FACT -> "mysql";
            case GRAPH_RELATIONAL -> "hybrid";
            case SEMANTIC_RAG -> "vector";
            case CLARIFY -> "unknown";
            case UNKNOWN -> fallbackIntent == null ? "" : fallbackIntent.trim();
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
        if (strategy == RetrievalStrategy.GRAPH_RELATIONAL) {
            if (hasRoleFocusValue(d.roleFocus())) {
                return d.hasPersonFocus() && hasRoleFocus(d);
            }
            return d.hasPersonEmployeeId() || d.hasPersonFocus();
        }
        if (strategy == RetrievalStrategy.STRUCTURED_LIST) {
            if (d.hasPersonFocus()) {
                return d.hasPersonEmployeeId() || d.hasPersonFocus();
            }
            return d.hasCompanyHints();
        }
        if (strategy == RetrievalStrategy.INSTANCE_FACT) {
            return d.hasCompanyHints() || d.hasPersonFocus();
        }
        if ("graph".equalsIgnoreCase(d.intent())) {
            return d.hasPersonFocus() || d.hasCompanyHints();
        }
        if ("hybrid".equalsIgnoreCase(d.intent())) {
            return d.hasPersonFocus() || d.hasCompanyHints();
        }
        if ("vector".equalsIgnoreCase(d.intent())) {
            return true;
        }
        if ("document".equalsIgnoreCase(d.intent())) {
            return true;
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

    /**
     * 角色焦点仅接受规则/问句推断的 snake_case token；非法值回落 any，由 enrich 从问句再推断。
     */
    static String normalizeRoleFocus(String value) {
        if (value == null || value.isBlank()) {
            return "any";
        }
        String token = value.trim().toLowerCase(Locale.ROOT);
        if ("any".equals(token)) {
            return "any";
        }
        return ROLE_FOCUS_TOKEN.matcher(token).matches() ? token : "any";
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
