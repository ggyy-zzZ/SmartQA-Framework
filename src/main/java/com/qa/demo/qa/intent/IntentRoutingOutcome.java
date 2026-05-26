package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;

/**
 * 意图路由完整结果：决策 + 人名解析（供编排层澄清分支使用，避免重复 resolve）。
 */
public record IntentRoutingOutcome(
        IntentDecision decision,
        PersonNameResolution personResolution
) {
    public boolean needsPersonClarification() {
        return personResolution != null && personResolution.needsClarification();
    }

    public static IntentRoutingOutcome of(IntentDecision decision, PersonNameResolution personResolution) {
        return new IntentRoutingOutcome(
                decision,
                personResolution == null ? PersonNameResolution.resolved("") : personResolution
        );
    }
}
