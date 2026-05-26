package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.PersonCertificateIntentHeuristics;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 在意图决策上补全 personName、queryType 等槽位，并将敬称/别名解析为规范姓名；高置信 LLM 可跳过规则覆盖。
 */
@Component
public class IntentDecisionEnricher {

    private final QaAssistantProperties properties;
    private final QuestionEntityExtractor entityExtractor;
    private final PersonNameResolver personNameResolver;

    public IntentDecisionEnricher(
            QaAssistantProperties properties,
            QuestionEntityExtractor entityExtractor,
            PersonNameResolver personNameResolver
    ) {
        this.properties = properties;
        this.entityExtractor = entityExtractor;
        this.personNameResolver = personNameResolver;
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
            working = withSourcePrefix(fillMissingSlots(normalized, question, explicitCompanyHint), source);
        }
        return applyCanonicalPersonName(working, learnedForAlias);
    }

    private boolean shouldSkipRuleEnrich(IntentDecision decision, String source, String question) {
        if (!"llm".equals(source)) {
            return false;
        }
        if (decision.confidence() < properties.getIntentLlmEnrichMinConfidence()
                || !IntentSlots.isRetrievalReady(decision)) {
            return false;
        }
        // 问句里能抽到「戴先生」等人名但 LLM 未填 personName 时，仍须规则补槽
        String rulePerson = entityExtractor.extractPersonName(question);
        if (rulePerson != null && !rulePerson.isBlank() && !decision.hasPersonFocus()) {
            return false;
        }
        if (entityExtractor.isRoleRelationQuery(question)
                && !decision.isPersonRoleListQuery()
                && !decision.isPersonCertificateListQuery()
                && rulePerson != null) {
            return false;
        }
        if ((PersonCertificateIntentHeuristics.isPersonCertificateListQuestion(question, decision.personName())
                || PersonCertificateIntentHeuristics.isPersonStewardshipListWithoutCertKeyword(
                        question, decision.personName()))
                && !decision.isPersonCertificateListQuery()) {
            return false;
        }
        return true;
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
            if (rulePerson == null || rulePerson.isBlank()) {
                rulePerson = PersonCertificateIntentHeuristics.extractPersonNameFromQuestion(question);
            }
            personName = rulePerson == null ? "" : rulePerson;
        }
        if (companyHints.isEmpty()) {
            companyHints.addAll(entityExtractor.extractCompanyHints(question));
        }
        if (explicitCompanyHint && companyHints.isEmpty()) {
            companyHints.addAll(entityExtractor.extractCompanyHints(question));
        }
        String inferredQueryType = entityExtractor.inferQueryType(question, personName);
        if (PersonCertificateIntentHeuristics.isPersonCertificateListQuestion(question, personName)
                || PersonCertificateIntentHeuristics.isPersonStewardshipListWithoutCertKeyword(
                        question, personName)) {
            queryType = "person_certificate_list";
        } else if (queryType == null || queryType.isBlank()) {
            queryType = inferredQueryType;
        } else if (!inferredQueryType.isBlank()
                && "person_role_list".equals(inferredQueryType)
                && isWeakQueryTypeForPersonRole(queryType)) {
            queryType = inferredQueryType;
        } else if (!inferredQueryType.isBlank()
                && "person_certificate_list".equals(inferredQueryType)
                && isWeakQueryTypeForPersonCertificate(queryType)) {
            queryType = inferredQueryType;
        }
        if ("any".equalsIgnoreCase(roleFocus)) {
            roleFocus = entityExtractor.inferRoleFocus(question);
        }

        String intent = base.intent();
        if ("person_certificate_list".equalsIgnoreCase(queryType)
                && (intent == null || intent.isBlank() || "unknown".equalsIgnoreCase(intent))) {
            intent = "hybrid";
        }
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

    private static boolean isWeakQueryTypeForPersonRole(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return true;
        }
        String q = queryType.trim().toLowerCase();
        return "semantic".equals(q) || "unknown".equals(q) || "mixed".equals(q);
    }

    private static boolean isWeakQueryTypeForPersonCertificate(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return true;
        }
        String q = queryType.trim().toLowerCase();
        return "company_certificate".equals(q)
                || "company_profile".equals(q)
                || "semantic".equals(q)
                || "unknown".equals(q)
                || "mixed".equals(q);
    }

    private IntentRoutingOutcome applyCanonicalPersonName(IntentDecision decision, List<ContextChunk> learned) {
        if (!decision.hasPersonFocus()) {
            return IntentRoutingOutcome.of(decision, PersonNameResolution.resolved(""));
        }
        PersonNameResolution resolution = personNameResolver.resolve(
                decision.personName(),
                learned,
                decision.roleFocus()
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
