package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import com.qa.demo.qa.domain.ConversationScopeSupport.OperatingStatusScope;
import com.qa.demo.qa.domain.EntityRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多轮追问时合并会话中的实体 hint（通用指代/接续检测，规则来自配置）。
 */
@Component
public class FollowUpSessionHintMerger {

    private static final Set<String> PLACEHOLDER_QUERY_TYPES = Set.of(
            "", "unknown", "semantic", "mixed"
    );

    private final ConversationScopeSupport scopeSupport;

    public FollowUpSessionHintMerger(ConversationScopeSupport scopeSupport) {
        this.scopeSupport = scopeSupport;
    }

    public IntentDecision merge(IntentDecision decision, String question, FollowUpIntentContext context) {
        if (decision == null || context == null || !context.active() || question == null || question.isBlank()) {
            return decision;
        }
        String q = question.strip();
        if (scopeSupport.explicitlyBreaksContext(q) || scopeSupport.isUnscopedListQuestion(q)) {
            return decision;
        }
        boolean continuation = scopeSupport.isContinuationUtterance(q) || scopeSupport.referencesPriorSubjects(q);
        IntentDecision normalized = normalizeContinuationQueryType(decision, q, context, continuation);
        if (!continuation) {
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
                normalized.queryType(),
                normalized.personName(),
                List.copyOf(filtered),
                normalized.roleFocus(),
                normalized.personEmployeeId()
        );
    }

    private static IntentDecision normalizeContinuationQueryType(
            IntentDecision decision,
            String question,
            FollowUpIntentContext context,
            boolean continuation
    ) {
        if (!continuation) {
            return decision;
        }
        String priorQt = context.priorQueryType() == null ? "" : context.priorQueryType().trim();
        if (priorQt.isBlank()) {
            return decision;
        }
        String currentQt = decision.queryType() == null ? "" : decision.queryType().trim();
        if (!isPlaceholderQueryType(currentQt)) {
            return decision;
        }
        String reason = decision.reason() == null ? "" : decision.reason();
        if (!reason.contains("followup_querytype_inherit")) {
            reason = (reason.isBlank() ? "" : reason + "; ") + "followup_querytype_inherit";
        }
        return new IntentDecision(
                decision.intent(),
                decision.confidence(),
                reason,
                priorQt,
                decision.personName(),
                decision.companyHints(),
                decision.roleFocus(),
                decision.personEmployeeId()
        );
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

    private static boolean isPlaceholderQueryType(String queryType) {
        if (queryType == null || queryType.isBlank()) {
            return true;
        }
        return PLACEHOLDER_QUERY_TYPES.contains(queryType.toLowerCase(Locale.ROOT));
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
