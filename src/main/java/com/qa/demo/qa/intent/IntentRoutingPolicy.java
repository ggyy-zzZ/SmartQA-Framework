package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalStrategy;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 意图槽位与闸门策略（配置驱动，基于 {@link RetrievalStrategy} + 槽位）。
 */
@Component
public class IntentRoutingPolicy {

    private final BusinessRulesConfig config;
    private final ConversationScopeSupport scopeSupport;

    public IntentRoutingPolicy(BusinessRulesConfig config, ConversationScopeSupport scopeSupport) {
        this.config = config;
        this.scopeSupport = scopeSupport;
    }

    public boolean isStructuredListStrategy(IntentDecision decision) {
        return decision != null && decision.isStructuredListStrategy();
    }

    public boolean isCertificateInstanceNeed(InformationNeed need) {
        return need != null
                && "certificate".equalsIgnoreCase(need.facet())
                && InformationNeed.GRANULARITY_INSTANCE.equalsIgnoreCase(need.granularity());
    }

    public Optional<String> defaultIntentForStrategy(RetrievalStrategy strategy) {
        if (strategy == null || strategy == RetrievalStrategy.UNKNOWN) {
            return Optional.empty();
        }
        String intent = config.getIntentRouting().getDefaultIntentByStrategy()
                .get(strategy.token().trim().toLowerCase(Locale.ROOT));
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
        boolean unscopedList = scopeSupport.isUnscopedListQuestion(question);
        RetrievalStrategy strategy = decision.resolvedRetrievalStrategy();
        if (strategy == RetrievalStrategy.UNKNOWN) {
            return decision.confidence() >= 0.8;
        }
        BusinessRulesConfig.StrategySlotRequirement req = findSlotRequirement(strategy, decision);
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
        return IntentSlots.isRetrievalReady(decision);
    }

    public Set<String> requiredEvidenceSchemaIds(InformationNeed need) {
        if (need == null || !need.hasFacet()) {
            return Set.of();
        }
        if (isCertificateInstanceNeed(need)) {
            return Set.of("person_certificate_v1");
        }
        return Set.of();
    }

    public List<String> followUpReferenceMarkers() {
        return config.getIntentRouting().getFollowUpReferenceMarkers();
    }

    private BusinessRulesConfig.StrategySlotRequirement findSlotRequirement(
            RetrievalStrategy strategy,
            IntentDecision decision
    ) {
        if (strategy == RetrievalStrategy.UNKNOWN) {
            return null;
        }
        String token = strategy.token();
        BusinessRulesConfig.StrategySlotRequirement fallback = null;
        for (BusinessRulesConfig.StrategySlotRequirement req : config.getIntentRouting().getStrategySlotRequirements()) {
            if (req.getRetrievalStrategy() == null || !token.equalsIgnoreCase(req.getRetrievalStrategy().trim())) {
                continue;
            }
            if (!matchesSlotShape(req, decision)) {
                continue;
            }
            if (req.isRequiresRoleFocus() || req.isRequiresPerson() || req.isRequiresCompany()) {
                return req;
            }
            fallback = req;
        }
        return fallback;
    }

    private static boolean matchesSlotShape(
            BusinessRulesConfig.StrategySlotRequirement req,
            IntentDecision decision
    ) {
        if (req.isRequiresPerson() && !decision.hasPersonFocus() && !decision.hasPersonEmployeeId()) {
            return false;
        }
        if (!req.isRequiresPerson() && decision.hasPersonFocus() && req.isRequiresCompany()) {
            return false;
        }
        if (req.isRequiresCompany() && !decision.hasCompanyHints()) {
            return false;
        }
        if (req.isRequiresRoleFocus() && !hasRoleFocusValue(decision.roleFocus())) {
            return false;
        }
        if (!req.isRequiresRoleFocus() && hasRoleFocusValue(decision.roleFocus()) && req.isRequiresPerson()) {
            return false;
        }
        return true;
    }

    private static boolean hasRoleFocusValue(String roleFocus) {
        return roleFocus != null && !roleFocus.isBlank() && !"any".equalsIgnoreCase(roleFocus);
    }
}
