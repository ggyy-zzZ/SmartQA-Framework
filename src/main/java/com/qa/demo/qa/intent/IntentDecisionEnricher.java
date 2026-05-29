package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import com.qa.demo.qa.domain.ScenarioRuleEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 在意图决策上补全槽位，并将敬称/别名解析为规范姓名。
 * 规则仅补全空缺或升级占位 queryType，不覆盖 LLM 已给出的具体 queryType。
 */
@Component
public class IntentDecisionEnricher {

    private static final Set<String> PLACEHOLDER_QUERY_TYPES = Set.of(
            "", "unknown", "semantic", "mixed"
    );

    private final QaAssistantProperties properties;
    private final QuestionEntityExtractor entityExtractor;
    private final PersonNameResolver personNameResolver;
    private final ScenarioRuleEngine ruleEngine;
    private final QueryTypeRoutingPolicy routingPolicy;

    public IntentDecisionEnricher(
            QaAssistantProperties properties,
            QuestionEntityExtractor entityExtractor,
            PersonNameResolver personNameResolver,
            ScenarioRuleEngine ruleEngine,
            QueryTypeRoutingPolicy routingPolicy
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
                || !routingPolicy.isRetrievalReady(decision)) {
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
        String queryType = base.queryType();
        String personName = base.personName();
        List<String> companyHints = base.companyHints() == null
                ? new ArrayList<>()
                : new ArrayList<>(base.companyHints());
        String roleFocus = base.roleFocus() == null || base.roleFocus().isBlank() ? "any" : base.roleFocus();

        if (personName == null || personName.isBlank()) {
            String rulePerson = ruleEngine.extractPersonName(question);
            personName = rulePerson == null ? "" : rulePerson;
        }
        if (companyHints.isEmpty()) {
            companyHints.addAll(entityExtractor.extractCompanyHints(question));
        }
        if (explicitCompanyHint && companyHints.isEmpty()) {
            companyHints.addAll(entityExtractor.extractCompanyHints(question));
        }

        String inferredQueryType = ruleEngine.inferQueryType(question, personName);
        queryType = resolveQueryType(queryType, inferredQueryType, source);

        if ("any".equalsIgnoreCase(roleFocus)) {
            roleFocus = entityExtractor.inferRoleFocus(question);
        }

        companyHints = filterCompanyHints(companyHints, personName);

        String intent = resolveIntent(base.intent(), queryType);
        return new IntentDecision(
                intent,
                base.confidence(),
                base.reason(),
                queryType == null ? "" : queryType,
                personName == null ? "" : personName,
                List.copyOf(companyHints),
                roleFocus,
                base.personEmployeeId()
        );
    }

    /**
     * 规则只填补空白或升级占位类型；LLM 已给出的具体 queryType 不被另一具体规则类型覆盖。
     */
    private String resolveQueryType(String current, String inferred, String source) {
        String qt = current == null ? "" : current.trim();
        String inf = inferred == null ? "" : inferred.trim();
        if (inf.isBlank()) {
            return qt;
        }
        if (isPlaceholderQueryType(qt)) {
            return inf;
        }
        if (qt.equalsIgnoreCase(inf)) {
            return qt;
        }
        if (isLlmSourced(source) && isConcreteQueryType(qt)) {
            return qt;
        }
        if (isPlaceholderQueryType(qt) || ruleEngine.isWeakQueryType(qt, inf)) {
            return inf;
        }
        return qt;
    }

    private static boolean isPlaceholderQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return true;
        }
        return PLACEHOLDER_QUERY_TYPES.contains(queryType.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isConcreteQueryType(String queryType) {
        return !isPlaceholderQueryType(queryType);
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

    private String resolveIntent(String baseIntent, String queryType) {
        String intent = baseIntent == null ? "" : baseIntent.trim();
        if (queryType == null || queryType.isBlank()) {
            return intent;
        }
        if (intent.isBlank() || "unknown".equalsIgnoreCase(intent)) {
            return routingPolicy.defaultIntentForQueryType(queryType).orElse(intent);
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
                decision.queryType(),
                personName,
                decision.companyHints(),
                decision.roleFocus(),
                personEmployeeId != null ? personEmployeeId : decision.personEmployeeId()
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
                decision.roleFocus(),
                decision.personEmployeeId()
        );
    }
}
