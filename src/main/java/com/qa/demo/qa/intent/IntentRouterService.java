package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import org.springframework.stereotype.Service;

import java.util.List;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 意图路由：优先 MiniMax 槽位抽取，失败或超时则 {@link IntentRuleEngine} 规则兜底，再经 {@link IntentDecisionEnricher} 补全。
 */
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

    /**
     * @param explicitCompanyHint 问句或改写后的检索句中是否已含明确公司/信用代码，影响澄清与 enrich
     */
    public IntentRoutingOutcome decide(String question, boolean explicitCompanyHint) {
        return decide(question, explicitCompanyHint, List.of());
    }

    /**
     * @param learnedForAlias 本轮主动学习命中，用于别名→实名解析
     */
    public IntentRoutingOutcome decide(String question, boolean explicitCompanyHint, List<ContextChunk> learnedForAlias) {
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
                    return enricher.enrich(llmDecision, question, explicitCompanyHint, "llm", learnedForAlias);
                }
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof TimeoutException) {
                    return enricher.enrich(
                            ruleEngine.classify(question, explicitCompanyHint, "llm_timeout"),
                            question,
                            explicitCompanyHint,
                            "rule",
                            learnedForAlias
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
                "rule",
                learnedForAlias
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
