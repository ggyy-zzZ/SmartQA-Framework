package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 在路由完成后按问句范围重置意图槽位（断链、全局列表时不继承人物/公司 hints）。
 */
@Component
public class IntentScopeNormalizer {

    private final ConversationScopeSupport scopeSupport;

    public IntentScopeNormalizer(ConversationScopeSupport scopeSupport) {
        this.scopeSupport = scopeSupport;
    }

    public IntentDecision normalize(IntentDecision decision, String rawQuestion) {
        if (decision == null) {
            return null;
        }
        if (!shouldResetSessionBinding(rawQuestion)) {
            return decision;
        }
        String tag = scopeSupport.isCatalogQuestion(rawQuestion)
                ? "scope_reset_catalog_question"
                : scopeSupport.isUnscopedListQuestion(rawQuestion)
                ? "scope_reset_unscoped_list"
                : "scope_reset_break_context";
        String reason = decision.reason() == null ? "" : decision.reason();
        if (!reason.contains(tag)) {
            reason = (reason.isBlank() ? "" : reason + "; ") + tag;
        }
        return new IntentDecision(
                decision.intent(),
                decision.confidence(),
                reason,
                "",
                "",
                List.of(),
                decision.roleFocus(),
                null,
                decision.retrievalStrategy()
        );
    }

    private boolean shouldResetSessionBinding(String question) {
        return scopeSupport.explicitlyBreaksContext(question)
                || scopeSupport.isUnscopedListQuestion(question)
                || scopeSupport.isCatalogQuestion(question);
    }
}
