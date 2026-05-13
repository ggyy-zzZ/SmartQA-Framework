package com.qa.demo.qa.core;

public record IntentDecision(
        String intent,
        double confidence,
        String reason
) {
}
