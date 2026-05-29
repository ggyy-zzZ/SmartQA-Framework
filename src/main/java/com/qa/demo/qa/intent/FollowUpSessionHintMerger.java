package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.ConversationSessionSupport;
import com.qa.demo.qa.domain.EntityRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 多轮追问时合并会话中的实体 hint（通用指代/接续检测，关键词来自配置）。
 */
@Component
public class FollowUpSessionHintMerger {

    private final QueryTypeRoutingPolicy routingPolicy;

    public FollowUpSessionHintMerger(QueryTypeRoutingPolicy routingPolicy) {
        this.routingPolicy = routingPolicy;
    }

    public IntentDecision merge(IntentDecision decision, String question, FollowUpIntentContext context) {
        if (decision == null || context == null || !context.active() || question == null || question.isBlank()) {
            return decision;
        }
        String q = question.strip();
        if (!ConversationSessionSupport.isContinuationUtterance(q) && !referencesPriorSubjects(q)) {
            return decision;
        }
        List<String> merged = new ArrayList<>();
        if (decision.companyHints() != null) {
            merged.addAll(decision.companyHints());
        }
        if (context.priorCompanies() != null) {
            for (EntityRef ref : context.priorCompanies()) {
                if (ref != null && ref.name() != null && !ref.name().isBlank()) {
                    String name = ref.name().trim();
                    if (!merged.contains(name)) {
                        merged.add(name);
                    }
                }
            }
        }
        if (context.priorFocusCompanyNames() != null) {
            for (String name : context.priorFocusCompanyNames()) {
                if (name != null && !name.isBlank() && !merged.contains(name.trim())) {
                    merged.add(name.trim());
                }
            }
        }
        if (merged.isEmpty() || merged.equals(decision.companyHints())) {
            return decision;
        }
        String person = decision.personName() == null ? "" : decision.personName().trim();
        List<String> filtered = merged.stream()
                .filter(h -> person.isBlank() || !person.equals(h))
                .distinct()
                .toList();
        if (filtered.isEmpty() || filtered.equals(decision.companyHints())) {
            return decision;
        }
        String reason = decision.reason() == null ? "" : decision.reason();
        if (!reason.contains("session_entity_merge")) {
            reason = (reason.isBlank() ? "" : reason + "; ") + "session_entity_merge";
        }
        return new IntentDecision(
                decision.intent(),
                decision.confidence(),
                reason,
                decision.queryType(),
                decision.personName(),
                List.copyOf(filtered),
                decision.roleFocus(),
                decision.personEmployeeId()
        );
    }

    private boolean referencesPriorSubjects(String question) {
        return routingPolicy.followUpReferenceMarkers().stream()
                .anyMatch(m -> m != null && !m.isBlank() && question.contains(m));
    }
}
