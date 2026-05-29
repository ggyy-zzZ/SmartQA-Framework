package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.ConversationSessionSupport;
import com.qa.demo.qa.domain.EntityRef;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 意图路由：LLM 辅助槽位抽取 + 配置化规则兜底 + enrich；多轮追问在同一入口二次解析。
 */
@Service
public class IntentRouterService {

    private final QaAssistantProperties properties;
    private final IntentLlmClassifier llmClassifier;
    private final IntentRuleEngine ruleEngine;
    private final IntentDecisionEnricher enricher;
    private final QueryTypeRoutingPolicy routingPolicy;
    private final FollowUpSessionHintMerger sessionHintMerger;

    public IntentRouterService(
            QaAssistantProperties properties,
            IntentLlmClassifier llmClassifier,
            IntentRuleEngine ruleEngine,
            IntentDecisionEnricher enricher,
            QueryTypeRoutingPolicy routingPolicy,
            FollowUpSessionHintMerger sessionHintMerger
    ) {
        this.properties = properties;
        this.llmClassifier = llmClassifier;
        this.ruleEngine = ruleEngine;
        this.enricher = enricher;
        this.routingPolicy = routingPolicy;
        this.sessionHintMerger = sessionHintMerger;
    }

    public IntentRoutingOutcome decide(String question, boolean explicitCompanyHint) {
        return decide(question, explicitCompanyHint, List.of(), FollowUpIntentContext.inactive());
    }

    public IntentRoutingOutcome decide(String question, boolean explicitCompanyHint, List<ContextChunk> learnedForAlias) {
        return decide(question, explicitCompanyHint, learnedForAlias, FollowUpIntentContext.inactive());
    }

    /**
     * @param followUp 非 inactive 时在本轮单轮路由之后做追问 LLM 解析并 enrich
     */
    public IntentRoutingOutcome decide(
            String question,
            boolean explicitCompanyHint,
            List<ContextChunk> learnedForAlias,
            FollowUpIntentContext followUp
    ) {
        if (followUp == null || !followUp.active()) {
            return decideSingleTurn(question, explicitCompanyHint, learnedForAlias);
        }
        // 追问：规则打底 + 专用 LLM 解析，避免与单轮 LLM 重复调用
        IntentRoutingOutcome ruleBase = enricher.enrich(
                ruleEngine.classify(question, explicitCompanyHint, "rule_followup_seed"),
                question,
                explicitCompanyHint,
                "rule",
                learnedForAlias
        );
        return applyFollowUp(ruleBase, question, explicitCompanyHint, learnedForAlias, followUp);
    }

    private IntentRoutingOutcome decideSingleTurn(
            String question,
            boolean explicitCompanyHint,
            List<ContextChunk> learnedForAlias
    ) {
        IntentDecision ruleFirst = ruleEngine.classify(question, explicitCompanyHint, "rule");
        if (properties.isIntentRuleFirstForStructured() && isStructuredListReady(ruleFirst)) {
            return enricher.enrich(ruleFirst, question, explicitCompanyHint, "rule", learnedForAlias);
        }
        if (properties.isIntentLlmEnabled() && hasMinimaxKey()) {
            try {
                IntentDecision llmDecision = classifyWithTimeout(question, explicitCompanyHint);
                if (IntentSlots.VALID_INTENTS.contains(llmDecision.intent())
                        && !"unknown".equalsIgnoreCase(llmDecision.intent())) {
                    return enricher.enrich(llmDecision, question, explicitCompanyHint, "llm", learnedForAlias);
                }
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof TimeoutException || cause instanceof java.util.concurrent.CancellationException) {
                    return enricher.enrich(
                            ruleEngine.classify(question, explicitCompanyHint, "llm_timeout"),
                            question,
                            explicitCompanyHint,
                            "rule",
                            learnedForAlias
                    );
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // fallback to rule route
            }
        }
        return enricher.enrich(
                ruleEngine.classify(question, explicitCompanyHint, llmRouteReasonPrefix()),
                question,
                explicitCompanyHint,
                "rule",
                learnedForAlias
        );
    }

    private IntentRoutingOutcome applyFollowUp(
            IntentRoutingOutcome baseOutcome,
            String question,
            boolean explicitCompanyHint,
            List<ContextChunk> learnedForAlias,
            FollowUpIntentContext followUp
    ) {
        IntentDecision base = baseOutcome == null ? null : baseOutcome.decision();
        IntentDecision routed = base;
        String personName = base != null && base.hasPersonFocus()
                ? base.personName()
                : followUp.focusPersonName();

        if (properties.isIntentLlmEnabled() && hasMinimaxKey()) {
            try {
                IntentDecision llmDecision = classifyFollowUpWithTimeout(
                        question,
                        followUp.priorQuestion(),
                        followUp.priorQueryType(),
                        followUp.priorAnswer(),
                        personName,
                        followUp.priorCompanies()
                );
                routed = mergeFollowUpDecision(llmDecision, personName, followUp, base);
            } catch (Exception ignored) {
                routed = fallbackFollowUp(base, question, followUp);
            }
        } else {
            routed = fallbackFollowUp(base, question, followUp);
        }

        IntentRoutingOutcome enriched = enricher.enrich(
                routed,
                question,
                explicitCompanyHint,
                "followup_llm",
                learnedForAlias
        );
        IntentDecision merged = sessionHintMerger.merge(
                enriched.decision(),
                question,
                followUp
        );
        if (merged == enriched.decision()) {
            return enriched;
        }
        return IntentRoutingOutcome.of(merged, enriched.personResolution());
    }

    private IntentDecision fallbackFollowUp(IntentDecision base, String question, FollowUpIntentContext followUp) {
        IntentDecision inherited = ConversationSessionSupport.inheritIntentSlots(
                base,
                question,
                followUp.priorQuestion(),
                followUp.priorQueryType(),
                followUp.focusPersonName()
        );
        return sessionHintMerger.merge(inherited, question, followUp);
    }

    private IntentDecision mergeFollowUpDecision(
            IntentDecision llmDecision,
            String fallbackPerson,
            FollowUpIntentContext followUp,
            IntentDecision base
    ) {
        String reason = llmDecision.reason() == null ? "" : llmDecision.reason();
        if (!reason.contains("followup_llm")) {
            reason = (reason.isBlank() ? "" : reason + "; ") + "followup_llm";
        }
        List<String> mergedHints = new ArrayList<>();
        if (llmDecision.companyHints() != null) {
            mergedHints.addAll(llmDecision.companyHints());
        }
        if (followUp.priorCompanies() != null) {
            for (EntityRef ref : followUp.priorCompanies()) {
                if (ref.name() != null && !ref.name().isBlank() && !mergedHints.contains(ref.name())) {
                    mergedHints.add(ref.name());
                }
            }
        }
        String person = llmDecision.hasPersonFocus() ? llmDecision.personName() : fallbackPerson;
        Integer employeeId = base != null ? base.personEmployeeId() : null;
        return new IntentDecision(
                llmDecision.intent(),
                llmDecision.confidence(),
                reason,
                llmDecision.queryType(),
                person == null ? "" : person,
                mergedHints,
                llmDecision.roleFocus(),
                employeeId
        );
    }

    private IntentDecision classifyWithTimeout(String question, boolean explicitCompanyHint)
            throws ExecutionException, InterruptedException {
        int timeoutMs = Math.max(5_000, properties.getIntentLlmTimeoutMs());
        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return llmClassifier.classify(question, explicitCompanyHint);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .get();
    }

    private IntentDecision classifyFollowUpWithTimeout(
            String currentQuestion,
            String priorQuestion,
            String priorQueryType,
            String priorAnswer,
            String personName,
            List<EntityRef> priorCompanies
    ) throws ExecutionException, InterruptedException {
        int timeoutMs = Math.max(5_000, properties.getIntentLlmTimeoutMs());
        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return llmClassifier.classifyWithEntities(
                                currentQuestion,
                                priorQuestion,
                                priorQueryType,
                                priorAnswer,
                                personName,
                                priorCompanies
                        );
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .get();
    }

    private String llmRouteReasonPrefix() {
        if (!properties.isIntentLlmEnabled()) {
            return "intent_llm_disabled";
        }
        if (!hasMinimaxKey()) {
            return "no_minimax_key";
        }
        return "llm_failed";
    }

    private boolean hasMinimaxKey() {
        String key = properties.getApiKey();
        return key != null && !key.isBlank();
    }

    private boolean isStructuredListReady(IntentDecision decision) {
        if (decision == null) {
            return false;
        }
        String queryType = decision.queryType();
        if (!routingPolicy.isStructuredListQueryType(queryType)) {
            return false;
        }
        return routingPolicy.isRetrievalReady(decision);
    }
}
