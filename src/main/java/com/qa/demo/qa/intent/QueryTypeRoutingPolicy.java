package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 从 {@code business-rules.json} 读取 queryType 槽位要求、闸门证据要求等，避免在 Java 中硬编码业务场景。
 */
@Component
public class QueryTypeRoutingPolicy {

    private final BusinessRulesConfig config;
    private final ConversationScopeSupport scopeSupport;

    public QueryTypeRoutingPolicy(BusinessRulesConfig config, ConversationScopeSupport scopeSupport) {
        this.config = config;
        this.scopeSupport = scopeSupport;
    }

    public boolean isCertificateQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return false;
        }
        return config.getIntentRouting().getCertificateQueryTypes().stream()
                .anyMatch(q -> q.equalsIgnoreCase(queryType.trim()));
    }

    public boolean isStructuredListQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return false;
        }
        return config.getIntentRouting().getStructuredListQueryTypes().stream()
                .anyMatch(q -> q.equalsIgnoreCase(queryType.trim()));
    }

    public Optional<String> defaultIntentForQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return Optional.empty();
        }
        String intent = config.getIntentRouting().getDefaultIntentByQueryType()
                .get(queryType.trim().toLowerCase(Locale.ROOT));
        if (intent == null || intent.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(intent.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isRetrievalReady(IntentDecision decision) {
        return isRetrievalReady(decision, "");
    }

    public boolean isRetrievalReady(IntentDecision decision, String question) {
        if (decision == null || "unknown".equalsIgnoreCase(decision.intent())) {
            return false;
        }
        String queryType = decision.queryType() == null ? "" : decision.queryType().trim();
        boolean unscopedList = scopeSupport.isUnscopedListQuestion(question);
        if (queryType.isBlank()) {
            return decision.confidence() >= 0.8;
        }
        BusinessRulesConfig.QueryTypeSlotRequirement req = findSlotRequirement(queryType);
        if (req != null) {
            if (req.isRequiresPerson() && !decision.hasPersonFocus() && !decision.hasPersonEmployeeId()) {
                return false;
            }
            if (req.isRequiresCompany() && !decision.hasCompanyHints() && !unscopedList) {
                return false;
            }
            if (req.isRequiresRoleFocus()) {
                String rf = decision.roleFocus();
                if (rf == null || rf.isBlank() || "any".equalsIgnoreCase(rf)) {
                    return false;
                }
            }
            if (!req.isRequiresPerson() && !req.isRequiresCompany() && !req.isRequiresRoleFocus()) {
                return decision.hasPersonFocus()
                        || decision.hasCompanyHints()
                        || decision.confidence() >= 0.75;
            }
            return true;
        }
        if ("graph".equalsIgnoreCase(decision.intent())) {
            return decision.hasPersonFocus() || decision.hasCompanyHints();
        }
        if ("hybrid".equalsIgnoreCase(decision.intent())) {
            return decision.hasPersonFocus() || decision.hasCompanyHints();
        }
        if ("aggregate".equalsIgnoreCase(queryType) || "sql".equalsIgnoreCase(decision.intent())) {
            return true;
        }
        if ("policy".equalsIgnoreCase(queryType) || "document".equalsIgnoreCase(decision.intent())) {
            return true;
        }
        if ("vector".equalsIgnoreCase(decision.intent()) || "semantic".equalsIgnoreCase(queryType)) {
            return true;
        }
        return decision.confidence() >= 0.8;
    }

    public Set<String> requiredEvidenceSchemaIds(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return Set.of();
        }
        for (BusinessRulesConfig.AnswerGateQueryTypeRule rule : config.getAnswerGate().getRequiredEvidenceByQueryType()) {
            if (queryType.equalsIgnoreCase(rule.getQueryType())) {
                return Set.copyOf(rule.getSchemaIds());
            }
        }
        return Set.of();
    }

    public List<String> followUpReferenceMarkers() {
        return config.getIntentRouting().getFollowUpReferenceMarkers();
    }

    private BusinessRulesConfig.QueryTypeSlotRequirement findSlotRequirement(String queryType) {
        for (BusinessRulesConfig.QueryTypeSlotRequirement req : config.getIntentRouting().getQueryTypeSlotRequirements()) {
            if (queryType.equalsIgnoreCase(req.getQueryType())) {
                return req;
            }
        }
        return null;
    }
}
