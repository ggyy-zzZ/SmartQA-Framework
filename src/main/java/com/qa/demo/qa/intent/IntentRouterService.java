package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class IntentRouterService {

    private final QaAssistantProperties properties;
    private final IntentLlmClassifier llmClassifier;
    private final IntentRuleEngine ruleEngine;
    private final IntentDecisionEnricher enricher;

    public IntentRouterService(
            QaAssistantProperties properties,
            IntentLlmClassifier llmClassifier,
            IntentRuleEngine ruleEngine,
            IntentDecisionEnricher enricher
    ) {
        this.properties = properties;
        this.llmClassifier = llmClassifier;
        this.ruleEngine = ruleEngine;
        this.enricher = enricher;
    }

    public IntentDecision decide(String question, boolean explicitCompanyHint) {
        if (properties.isIntentLlmEnabled() && hasMinimaxKey()) {
            try {
                int timeoutMs = Math.max(5_000, properties.getIntentLlmTimeoutMs());
                IntentDecision llmDecision = CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return llmClassifier.classify(question, explicitCompanyHint);
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        })
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .get();
                if (IntentSlots.VALID_INTENTS.contains(llmDecision.intent())
                        && !"unknown".equalsIgnoreCase(llmDecision.intent())) {
                    return enricher.enrich(llmDecision, question, explicitCompanyHint, "llm");
                }
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof TimeoutException) {
                    return enricher.enrich(
                            ruleEngine.classify(question, explicitCompanyHint, "llm_timeout"),
                            question,
                            explicitCompanyHint,
                            "rule"
                    );
                }
            } catch (Exception ignored) {
                // fallback to rule route
            }
        }
        return enricher.enrich(
                ruleEngine.classify(question, explicitCompanyHint, llmRouteReasonPrefix()),
                question,
                explicitCompanyHint,
                "rule"
        );
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
}
