package com.qa.demo.qa.domain;

import com.qa.demo.qa.config.BusinessRulesConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 会话范围与主体经营状态推断（规则来自 business-rules.json {@code conversationScope}）。
 * 不绑定单一业务域（证照/CRM 等），供多轮合并、检索过滤、意图槽位重置共用。
 */
@Component
public class ConversationScopeSupport {

    public enum OperatingStatusScope {
        ALL,
        ACTIVE,
        INACTIVE
    }

    private final BusinessRulesConfig.ConversationScope scope;
    private final List<String> followUpReferenceMarkers;

    public ConversationScopeSupport(BusinessRulesConfig businessRulesConfig) {
        this.scope = businessRulesConfig.getConversationScope();
        this.followUpReferenceMarkers = businessRulesConfig.getIntentRouting().getFollowUpReferenceMarkers();
    }

    public boolean explicitlyBreaksContext(String question) {
        return containsAnyPhrase(question, scope.getBreakContextPhrases());
    }

    /**
     * 无会话实体锚点的全库/全局列表问法（如「所有…证照」），不应继承上轮人物/公司 hints。
     */
    public boolean isUnscopedListQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.strip();
        if (!containsAnyPhrase(q, scope.getGlobalListMarkers())) {
            return false;
        }
        return scope.getGlobalListContextKeywords().isEmpty()
                || containsAnyPhrase(q, scope.getGlobalListContextKeywords());
    }

    /**
     * 枚举/类型目录问法（如「经营状态包含哪些种类」），不应继承上轮 region/主体/queryType。
     * 含「这些/那些/它们」等指代时视为接续，不算全新 catalog。
     */
    public boolean isCatalogQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.strip();
        if (explicitlyBreaksContext(q)) {
            return true;
        }
        if (!containsAnyPhrase(q, scope.getCatalogQuestionMarkers())) {
            return false;
        }
        return !referencesPriorSubjects(q);
    }

    public boolean isContinuationUtterance(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String t = question.strip();
        if (explicitlyBreaksContext(t) || isUnscopedListQuestion(t)) {
            return false;
        }
        if (matchesAnyPattern(t, scope.getContinuationExcludePatterns())) {
            return false;
        }
        if (t.length() > scope.getContinuationMaxLength()) {
            return false;
        }
        return containsAnyPhrase(t, scope.getContinuationMarkers());
    }

    public OperatingStatusScope inferOperatingStatusScope(String question) {
        if (question == null || question.isBlank()) {
            return OperatingStatusScope.ALL;
        }
        String q = question.strip();
        String lower = q.toLowerCase(Locale.ROOT);
        BusinessRulesConfig.OperatingStatusScopeRules rules = scope.getOperatingStatus();

        if (hasNegatedActiveStatus(lower, rules)) {
            return OperatingStatusScope.INACTIVE;
        }
        boolean askInactive = containsAnyMarker(lower, rules.getInactiveMarkers());
        boolean askActive = containsAnyMarker(lower, rules.getActiveMarkers())
                && !isCertificateStatusContext(lower, rules);
        if (askActive == askInactive) {
            return OperatingStatusScope.ALL;
        }
        return askActive ? OperatingStatusScope.ACTIVE : OperatingStatusScope.INACTIVE;
    }

    /**
     * 结构化检索：是否仅查经营状态为「存续/在业」类的主体。
     * {@link java.util.Optional#empty()} 表示不按经营状态过滤。
     */
    public java.util.Optional<Boolean> resolveActiveCompaniesOnly(String question) {
        return switch (inferOperatingStatusScope(question)) {
            case ACTIVE -> java.util.Optional.of(true);
            case INACTIVE -> java.util.Optional.of(false);
            case ALL -> java.util.Optional.empty();
        };
    }

    public boolean referencesPriorSubjects(String question) {
        return containsAnyPhrase(question, followUpReferenceMarkers);
    }

    private boolean hasNegatedActiveStatus(String lower, BusinessRulesConfig.OperatingStatusScopeRules rules) {
        for (String prefix : rules.getNegationPrefixes()) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            String p = prefix.toLowerCase(Locale.ROOT);
            for (String marker : rules.getActiveMarkers()) {
                if (marker == null || marker.isBlank()) {
                    continue;
                }
                String m = marker.toLowerCase(Locale.ROOT);
                if (lower.contains(p + m)) {
                    return true;
                }
            }
        }
        for (String phrase : rules.getInactivePhrases()) {
            if (phrase != null && !phrase.isBlank() && lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCertificateStatusContext(
            String lower,
            BusinessRulesConfig.OperatingStatusScopeRules rules
    ) {
        if (!lower.contains("有效") && !lower.contains("失效")) {
            return false;
        }
        for (String kw : rules.getCertificateContextKeywords()) {
            if (kw != null && !kw.isBlank() && lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyMarker(String lower, List<String> markers) {
        for (String marker : markers) {
            if (marker != null && !marker.isBlank() && lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyPhrase(String text, List<String> phrases) {
        if (text == null || text.isBlank() || phrases == null) {
            return false;
        }
        String t = text.strip();
        for (String phrase : phrases) {
            if (phrase != null && !phrase.isBlank() && t.contains(phrase.strip())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyPattern(String text, List<String> patterns) {
        if (text == null || patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank() && text.contains(pattern.strip())) {
                return true;
            }
        }
        return false;
    }
}
