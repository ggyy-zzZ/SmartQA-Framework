package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalStrategy;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import com.qa.demo.qa.domain.ScenarioRuleEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 在意图决策上补全槽位，并将敬称/别名解析为规范姓名。
 */
@Component
public class IntentDecisionEnricher {

    private final QaAssistantProperties properties;
    private final QuestionEntityExtractor entityExtractor;
    private final PersonNameResolver personNameResolver;
    private final ScenarioRuleEngine ruleEngine;
    private final IntentRoutingPolicy routingPolicy;

    public IntentDecisionEnricher(
            QaAssistantProperties properties,
            QuestionEntityExtractor entityExtractor,
            PersonNameResolver personNameResolver,
            ScenarioRuleEngine ruleEngine,
            IntentRoutingPolicy routingPolicy
    ) {
        this.properties = properties;
        this.entityExtractor = entityExtractor;
        this.personNameResolver = personNameResolver;
        this.ruleEngine = ruleEngine;
        this.routingPolicy = routingPolicy;
    }

    public IntentRoutingOutcome enrich(
            IntentDecision base,
            String question,
            boolean explicitCompanyHint,
            String source
    ) {
        return enrich(base, question, explicitCompanyHint, source, List.of());
    }

    public IntentRoutingOutcome enrich(
            IntentDecision base,
            String question,
            boolean explicitCompanyHint,
            String source,
            List<ContextChunk> learnedForAlias
    ) {
        IntentDecision normalized = IntentSlots.normalize(base);
        IntentDecision working;
        if (shouldSkipRuleEnrich(normalized, source, question)) {
            working = withSourcePrefix(normalized, source);
        } else {
            working = withSourcePrefix(fillMissingSlots(normalized, question, explicitCompanyHint, source), source);
        }
        return applyCanonicalPersonName(working, learnedForAlias, question);
    }

    private boolean shouldSkipRuleEnrich(IntentDecision decision, String source, String question) {
        if (!isLlmSourced(source)) {
            return false;
        }
        if (decision.confidence() < properties.getIntentLlmEnrichMinConfidence()
                || !routingPolicy.isRetrievalReady(decision, question)) {
            return false;
        }
        String rulePerson = ruleEngine.extractPersonName(question);
        if (rulePerson != null && !rulePerson.isBlank() && !decision.hasPersonFocus()) {
            return false;
        }
        return true;
    }

    private static boolean isLlmSourced(String source) {
        return "llm".equals(source) || "followup_llm".equals(source);
    }

    private IntentDecision fillMissingSlots(
            IntentDecision base,
            String question,
            boolean explicitCompanyHint,
            String source
    ) {
        String retrievalStrategy = base.retrievalStrategy();
        String personName = base.personName();
        List<String> companyHints = base.companyHints() == null
                ? new ArrayList<>()
                : new ArrayList<>(base.companyHints());
        String roleFocus = base.roleFocus() == null || base.roleFocus().isBlank() ? "any" : base.roleFocus();

        if (personName == null || personName.isBlank()) {
            String rulePerson = ruleEngine.extractPersonName(question);
            personName = rulePerson == null ? "" : rulePerson;
        }
        if (personName != null && !personName.isBlank()) {
            personName = personNameResolver.guardPersonSlotCandidate(personName, question);
        }
        if (companyHints.isEmpty()) {
            companyHints.addAll(entityExtractor.extractCompanyHints(question));
        }
        if (explicitCompanyHint && companyHints.isEmpty()) {
            companyHints.addAll(entityExtractor.extractCompanyHints(question));
        }

        if (retrievalStrategy == null || retrievalStrategy.isBlank()) {
            retrievalStrategy = entityExtractor.inferRetrievalStrategy(question, personName);
        }

        if ("any".equalsIgnoreCase(roleFocus)) {
            roleFocus = entityExtractor.inferRoleFocus(question);
        }

        companyHints = filterCompanyHints(companyHints, personName);

        String intent = resolveIntent(base.intent(), retrievalStrategy);
        return new IntentDecision(
                intent,
                base.confidence(),
                base.reason(),
                personName == null ? "" : personName,
                List.copyOf(companyHints),
                roleFocus,
                base.personEmployeeId(),
                retrievalStrategy == null ? "" : retrievalStrategy
        );
    }

    private static List<String> filterCompanyHints(List<String> hints, String personName) {
        if (hints == null || hints.isEmpty()) {
            return List.of();
        }
        String person = personName == null ? "" : personName.trim();
        return hints.stream()
                .map(h -> h == null ? "" : h.trim())
                .filter(h -> !h.isBlank())
                .filter(h -> person.isBlank() || !person.equals(h))
                .distinct()
                .toList();
    }

    private String resolveIntent(String baseIntent, String retrievalStrategy) {
        String intent = baseIntent == null ? "" : baseIntent.trim();
        RetrievalStrategy strategy = RetrievalStrategy.fromToken(retrievalStrategy);
        if (strategy == RetrievalStrategy.UNKNOWN) {
            return intent;
        }
        if (intent.isBlank() || "unknown".equalsIgnoreCase(intent)) {
            return routingPolicy.defaultIntentForStrategy(strategy).orElse(intent);
        }
        return intent;
    }

    private IntentRoutingOutcome applyCanonicalPersonName(
            IntentDecision decision,
            List<ContextChunk> learned,
            String question
    ) {
        if (!decision.hasPersonFocus()) {
            return IntentRoutingOutcome.of(decision, PersonNameResolution.resolved(""));
        }
        PersonNameResolution resolution = personNameResolver.resolve(
                decision.personName(),
                learned,
                decision.roleFocus(),
                question
        );
        String resolved = resolution.canonicalName();
        if (!IntentSlots.sanitizePersonName(resolved).equals(resolved)) {
            resolved = "";
        }
        String displayName = resolved.isBlank() ? decision.personName() : resolved;
        if (resolved.isBlank() || resolved.equals(decision.personName())) {
            if (resolution.needsClarification()) {
                String reason = decision.reason() == null ? "" : decision.reason();
                reason = reason + "|person_ambiguous";
                IntentDecision ambiguous = copyPersonFields(decision, decision.personName(), null, reason);
                return IntentRoutingOutcome.of(ambiguous, resolution);
            }
            return IntentRoutingOutcome.of(
                    copyPersonFields(decision, displayName, resolution.employeeId(), decision.reason()),
                    resolution
            );
        }
        String reason = decision.reason() == null ? "" : decision.reason();
        reason = reason + "|person_resolved:" + decision.personName() + "->" + resolved;
        IntentDecision updated = copyPersonFields(decision, resolved, resolution.employeeId(), reason);
        return IntentRoutingOutcome.of(updated, resolution);
    }

    private static IntentDecision copyPersonFields(
            IntentDecision decision,
            String personName,
            Integer personEmployeeId,
            String reason
    ) {
        return new IntentDecision(
                decision.intent(),
                decision.confidence(),
                reason,
                personName,
                decision.companyHints(),
                decision.roleFocus(),
                personEmployeeId != null ? personEmployeeId : decision.personEmployeeId(),
                decision.retrievalStrategy()
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
                decision.personName(),
                decision.companyHints(),
                decision.roleFocus(),
                decision.personEmployeeId(),
                decision.retrievalStrategy()
        );
    }
}
