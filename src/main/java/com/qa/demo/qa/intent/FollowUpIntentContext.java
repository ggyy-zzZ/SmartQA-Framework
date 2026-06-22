package com.qa.demo.qa.intent;

import com.qa.demo.qa.domain.EntityRef;

import java.util.List;

/**
 * 多轮追问时传给意图路由的会话上下文（不含具体业务分支逻辑）。
 */
public record FollowUpIntentContext(
        boolean active,
        String priorQuestion,
        String priorRetrievalStrategy,
        String priorAnswer,
        String focusPersonName,
        List<EntityRef> priorCompanies,
        List<String> priorFocusCompanyNames
) {
    public static FollowUpIntentContext inactive() {
        return new FollowUpIntentContext(false, "", "", "", "", List.of(), List.of());
    }

    public static FollowUpIntentContext of(
            String priorQuestion,
            String priorRetrievalStrategy,
            String priorAnswer,
            String focusPersonName,
            List<EntityRef> priorCompanies,
            List<String> priorFocusCompanyNames
    ) {
        return new FollowUpIntentContext(
                true,
                priorQuestion == null ? "" : priorQuestion,
                priorRetrievalStrategy == null ? "" : priorRetrievalStrategy,
                priorAnswer == null ? "" : priorAnswer,
                focusPersonName == null ? "" : focusPersonName,
                priorCompanies == null ? List.of() : List.copyOf(priorCompanies),
                priorFocusCompanyNames == null ? List.of() : List.copyOf(priorFocusCompanyNames)
        );
    }
}
