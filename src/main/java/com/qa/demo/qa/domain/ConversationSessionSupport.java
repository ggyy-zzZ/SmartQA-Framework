package com.qa.demo.qa.domain;

import com.qa.demo.qa.core.IntentDecision;

import java.util.List;
import java.util.Locale;

/**
 * 多轮会话：追问判定、检索问句改写、意图槽位继承（与 {@code QaConversationService} 配合）。
 */
public final class ConversationSessionSupport {

    private ConversationSessionSupport() {
    }

    public static boolean isContinuationUtterance(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String t = question.strip();
        if (t.length() > 36) {
            return false;
        }
        return containsAny(t,
                "它", "其", "该", "这家", "那家", "上面", "刚才", "之前", "接着", "继续", "再", "还有", "同样",
                "那", "然后", "另外", "顺便", "对吧", "是不是", "对吗", "呢", "吗", "嘛", "啥", "多少", "几个",
                "具体", "详细", "分别", "哪些", "什么", "谁", "不对", "不是", "错了");
    }

    /**
     * 显式公司名不应单独阻断多轮；长句独立新问题除外。
     */
    public static boolean shouldTreatAsFollowUpDespiteCompanyHint(String question, boolean hasPriorTurn, boolean isCorrection) {
        if (!hasPriorTurn || question == null) {
            return false;
        }
        String t = question.strip();
        if (isCorrection) {
            return true;
        }
        if (isContinuationUtterance(t)) {
            return true;
        }
        return t.length() <= 28;
    }

    public static String primaryCompanyFocus(List<String> focusCompanyNames) {
        if (focusCompanyNames == null || focusCompanyNames.isEmpty()) {
            return "";
        }
        for (String name : focusCompanyNames) {
            if (name != null && !name.isBlank() && !name.contains("分公司")) {
                return stripCompanyIdSuffix(name.trim());
            }
        }
        return stripCompanyIdSuffix(focusCompanyNames.get(0).trim());
    }

    public static String stripCompanyIdSuffix(String displayLabel) {
        if (displayLabel == null) {
            return "";
        }
        String s = displayLabel.trim();
        int idIdx = s.indexOf("（ID ");
        if (idIdx > 0) {
            return s.substring(0, idIdx).trim();
        }
        int ascii = s.indexOf("(ID ");
        if (ascii > 0) {
            return s.substring(0, ascii).trim();
        }
        return s;
    }

    public static IntentDecision inheritIntentSlots(
            IntentDecision current,
            String question,
            String priorQuestion,
            String priorQueryType,
            String focusPersonName
    ) {
        if (current == null) {
            return null;
        }
        String person = current.personName() == null ? "" : current.personName().trim();
        String queryType = current.queryType() == null ? "" : current.queryType().trim();
        String priorQt = priorQueryType == null ? "" : priorQueryType.trim();
        String focus = focusPersonName == null ? "" : focusPersonName.trim();

        if (person.isBlank() && !focus.isBlank()) {
            person = focus;
        }
        if (queryType.isBlank() && !priorQt.isBlank() && isContinuationUtterance(question)) {
            queryType = priorQt;
        }

        if (person.equals(current.personName()) && queryType.equals(current.queryType())) {
            return current;
        }
        String reason = current.reason() == null ? "" : current.reason();
        if (!reason.contains("session_inherit")) {
            reason = (reason.isBlank() ? "" : reason + "; ") + "session_inherit";
        }
        return new IntentDecision(
                current.intent(),
                current.confidence(),
                reason,
                queryType,
                person,
                current.companyHints(),
                current.roleFocus(),
                current.personEmployeeId(),
                current.retrievalStrategy()
        );
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (n != null && (text.contains(n) || lower.contains(n.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }
}
