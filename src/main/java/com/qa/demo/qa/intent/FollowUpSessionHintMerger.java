package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalStrategy;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import com.qa.demo.qa.domain.ConversationScopeSupport.OperatingStatusScope;
import com.qa.demo.qa.domain.EntityRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多轮追问时合并会话中的实体 hint（通用指代/接续检测，规则来自配置）。
 */
@Component
public class FollowUpSessionHintMerger {

    private final ConversationScopeSupport scopeSupport;

    public FollowUpSessionHintMerger(ConversationScopeSupport scopeSupport) {
        this.scopeSupport = scopeSupport;
    }

    public IntentDecision merge(IntentDecision decision, String question, FollowUpIntentContext context) {
        if (decision == null || context == null || !context.active() || question == null || question.isBlank()) {
            return decision;
        }
        String q = question.strip();
        if (scopeSupport.explicitlyBreaksContext(q)
                || scopeSupport.isUnscopedListQuestion(q)
                || scopeSupport.isCatalogQuestion(q)) {
            return decision;
        }
        decision = switchFollowUpStrategy(decision, q, context);
        boolean continuation = scopeSupport.isContinuationUtterance(q) || scopeSupport.referencesPriorSubjects(q);
        IntentDecision normalized = normalizeContinuationStrategy(decision, q, context, continuation);
        if (!continuation) {
            return normalized;
        }

        boolean inheritEntities = scopeSupport.referencesPriorSubjects(q)
                || containsPronounReference(q);
        if (!inheritEntities) {
            return normalized;
        }

        InheritedCompanyScope inheritedScope = resolveInheritedCompanies(context.priorCompanies(), q);
        List<String> merged = new ArrayList<>();
        if (normalized.companyHints() != null) {
            merged.addAll(normalized.companyHints());
        }
        if (merged.isEmpty()) {
            merged.addAll(inheritedScope.names());
        } else {
            appendMissing(merged, inheritedScope.names());
        }
        if (merged.isEmpty() && context.priorFocusCompanyNames() != null) {
            for (String name : context.priorFocusCompanyNames()) {
                if (name != null && !name.isBlank() && !merged.contains(name.trim())) {
                    merged.add(name.trim());
                }
            }
        }
        if (merged.isEmpty() || merged.equals(normalized.companyHints())) {
            return normalized;
        }
        String person = normalized.personName() == null ? "" : normalized.personName().trim();
        List<String> filtered = merged.stream()
                .filter(h -> person.isBlank() || !person.equals(h))
                .distinct()
                .toList();
        if (filtered.isEmpty() || filtered.equals(normalized.companyHints())) {
            return normalized;
        }
        String reason = normalized.reason() == null ? "" : normalized.reason();
        if (!reason.contains("session_entity_merge")) {
            reason = (reason.isBlank() ? "" : reason + "; ") + "session_entity_merge";
        }
        if (inheritedScope.statusScoped() && !reason.contains("session_status_scope")) {
            reason = reason + "; session_status_scope";
        }
        return new IntentDecision(
                normalized.intent(),
                normalized.confidence(),
                reason,
                normalized.personName(),
                List.copyOf(filtered),
                normalized.roleFocus(),
                normalized.personEmployeeId(),
                normalized.retrievalStrategy()
        );
    }

    private static boolean containsPronounReference(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.strip();
        return q.contains("他") || q.contains("她") || q.contains("其") || q.contains("它们") || q.contains("他们");
    }

    private IntentDecision normalizeContinuationStrategy(
            IntentDecision decision,
            String question,
            FollowUpIntentContext context,
            boolean continuation
    ) {
        if (!continuation || scopeSupport.isCatalogQuestion(question)) {
            return decision;
        }
        String priorStrategy = context.priorRetrievalStrategy() == null ? "" : context.priorRetrievalStrategy().trim();
        if (priorStrategy.isBlank()) {
            return decision;
        }
        String currentStrategy = decision.retrievalStrategy() == null ? "" : decision.retrievalStrategy().trim();
        if (!currentStrategy.isBlank() && !"unknown".equalsIgnoreCase(currentStrategy)) {
            return decision;
        }
        String reason = decision.reason() == null ? "" : decision.reason();
        if (!reason.contains("followup_strategy_inherit")) {
            reason = (reason.isBlank() ? "" : reason + "; ") + "followup_strategy_inherit";
        }
        return new IntentDecision(
                decision.intent(),
                decision.confidence(),
                reason,
                decision.personName(),
                decision.companyHints(),
                decision.roleFocus(),
                decision.personEmployeeId(),
                priorStrategy
        );
    }

    /**
     * 上轮任职列表 + 本轮证照语义 → 切换为证照结构化列表（Q-02）。
     */
    private IntentDecision switchFollowUpStrategy(
            IntentDecision decision,
            String question,
            FollowUpIntentContext context
    ) {
        String priorStrategy = context.priorRetrievalStrategy() == null ? "" : context.priorRetrievalStrategy().trim();
        if (!RetrievalStrategy.GRAPH_RELATIONAL.token().equalsIgnoreCase(priorStrategy)) {
            return decision;
        }
        if (RetrievalStrategy.STRUCTURED_LIST.token().equalsIgnoreCase(decision.retrievalStrategy())) {
            return decision;
        }
        if (!looksLikeCertificateInstanceFollowUp(question)) {
            return decision;
        }
        String person = decision.hasPersonFocus()
                ? decision.personName()
                : context.focusPersonName();
        String reason = decision.reason() == null ? "" : decision.reason();
        if (!reason.contains("followup_strategy_switch")) {
            reason = (reason.isBlank() ? "" : reason + "; ") + "followup_strategy_switch:structured_list";
        }
        return new IntentDecision(
                "mysql",
                Math.max(decision.confidence(), 0.85),
                reason,
                person == null ? "" : person,
                decision.companyHints(),
                decision.roleFocus() == null || decision.roleFocus().isBlank() ? "any" : decision.roleFocus(),
                decision.personEmployeeId(),
                RetrievalStrategy.STRUCTURED_LIST.token()
        );
    }

    private boolean looksLikeCertificateInstanceFollowUp(String question) {
        if (question == null || question.isBlank() || scopeSupport.isCatalogQuestion(question)) {
            return false;
        }
        String q = question.strip();
        boolean certContext = q.contains("证照") || q.contains("许可证") || q.contains("执照")
                || q.contains("资质") || q.contains("备案");
        if (certContext) {
            return true;
        }
        return q.contains("证") && !q.contains("公司") && !q.contains("主体");
    }

    private InheritedCompanyScope resolveInheritedCompanies(List<EntityRef> priorCompanies, String question) {
        if (priorCompanies == null || priorCompanies.isEmpty()) {
            return InheritedCompanyScope.empty();
        }
        OperatingStatusScope scope = scopeSupport.inferOperatingStatusScope(question);
        List<String> names = new ArrayList<>();
        boolean scoped = false;
        for (EntityRef ref : priorCompanies) {
            if (ref == null || ref.name() == null || ref.name().isBlank()) {
                continue;
            }
            if (scope == OperatingStatusScope.ALL) {
                if (!names.contains(ref.name().trim())) {
                    names.add(ref.name().trim());
                }
                continue;
            }
            String status = ref.status() == null ? "" : ref.status().trim().toLowerCase(Locale.ROOT);
            if (status.isBlank()) {
                continue;
            }
            boolean match = scope == OperatingStatusScope.ACTIVE
                    ? containsAny(status, "存续", "在业", "开业", "有效", "正常")
                    : containsAny(status, "吊销", "注销", "失效", "停业", "撤销", "清算", "迁出", "异常");
            if (match) {
                if (!names.contains(ref.name().trim())) {
                    names.add(ref.name().trim());
                }
                scoped = true;
            }
        }
        if (names.isEmpty()) {
            for (EntityRef ref : priorCompanies) {
                if (ref != null && ref.name() != null && !ref.name().isBlank() && !names.contains(ref.name().trim())) {
                    names.add(ref.name().trim());
                }
            }
            return new InheritedCompanyScope(List.copyOf(names), false);
        }
        return new InheritedCompanyScope(List.copyOf(names), scoped);
    }

    private static void appendMissing(List<String> merged, List<String> extras) {
        if (extras == null || extras.isEmpty()) {
            return;
        }
        for (String name : extras) {
            if (name != null && !name.isBlank() && !merged.contains(name)) {
                merged.add(name);
            }
        }
    }

    private static boolean containsAny(String text, String... markers) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private record InheritedCompanyScope(List<String> names, boolean statusScoped) {
        static InheritedCompanyScope empty() {
            return new InheritedCompanyScope(List.of(), false);
        }
    }
}
