package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class IntentDecisionEnricher {

    private final QaAssistantProperties properties;
    private final QuestionEntityExtractor entityExtractor;

    public IntentDecisionEnricher(QaAssistantProperties properties, QuestionEntityExtractor entityExtractor) {
        this.properties = properties;
        this.entityExtractor = entityExtractor;
    }

    public IntentDecision enrich(
            IntentDecision base,
            String question,
            boolean explicitCompanyHint,
            String source
    ) {
        IntentDecision normalized = IntentSlots.normalize(base);
        if (shouldSkipRuleEnrich(normalized, source)) {
            return withSourcePrefix(normalized, source);
        }
        return withSourcePrefix(fillMissingSlots(normalized, question, explicitCompanyHint), source);
    }

    private boolean shouldSkipRuleEnrich(IntentDecision decision, String source) {
        if (!"llm".equals(source)) {
            return false;
        }
        return decision.confidence() >= properties.getIntentLlmEnrichMinConfidence()
                && IntentSlots.isRetrievalReady(decision);
    }

    private IntentDecision fillMissingSlots(IntentDecision base, String question, boolean explicitCompanyHint) {
        String queryType = base.queryType();
        String personName = base.personName();
        List<String> companyHints = base.companyHints() == null
                ? new ArrayList<>()
                : new ArrayList<>(base.companyHints());
        String roleFocus = base.roleFocus() == null || base.roleFocus().isBlank() ? "any" : base.roleFocus();

        if (personName == null || personName.isBlank()) {
            String rulePerson = entityExtractor.extractPersonName(question);
            personName = rulePerson == null ? "" : rulePerson;
        }
        if (companyHints.isEmpty()) {
            companyHints.addAll(entityExtractor.extractCompanyHints(question));
        }
        if (explicitCompanyHint && companyHints.isEmpty()) {
            companyHints.addAll(entityExtractor.extractCompanyHints(question));
        }
        if (queryType == null || queryType.isBlank()) {
            queryType = entityExtractor.inferQueryType(question, personName);
        }
        if ("any".equalsIgnoreCase(roleFocus)) {
            roleFocus = entityExtractor.inferRoleFocus(question);
        }

        return new IntentDecision(
                base.intent(),
                base.confidence(),
                base.reason(),
                queryType == null ? "" : queryType,
                personName == null ? "" : personName,
                List.copyOf(companyHints),
                roleFocus
        );
    }

    private IntentDecision withSourcePrefix(IntentDecision decision, String source) {
        String reason = decision.reason() == null ? "" : decision.reason();
        if (!reason.contains(source)) {
            reason = source + "_" + (reason.isBlank() ? "route" : reason);
        }
        return new IntentDecision(
                decision.intent(),
                decision.confidence(),
                reason,
                decision.queryType(),
                decision.personName(),
                decision.companyHints(),
                decision.roleFocus()
        );
    }
}
