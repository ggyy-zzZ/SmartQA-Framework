package com.qa.demo.qa.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 「人物 × 证照」问句启发式（规则层），与 {@link EnterpriseLexicon#inferQueryType} 配合。
 */
public final class PersonCertificateIntentHeuristics {

    private static final Pattern PERSON_BEFORE_STEWARD = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,12})\\s*(负责|管|监管|保管|执行)"
    );

    private PersonCertificateIntentHeuristics() {
    }

    public static boolean isPersonCertificateListQuestion(String question, String personName) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String person = personName == null ? "" : personName.trim();
        if (person.isBlank()) {
            person = extractPersonNameFromQuestion(question);
        }
        if (person.isBlank()) {
            return false;
        }
        String t = question.strip();
        if (!containsCertKeyword(t)) {
            return false;
        }
        return containsAny(t, "哪些", "负责", "管", "列出", "多少", "什么", "啥", "涉及")
                || PERSON_BEFORE_STEWARD.matcher(t).find();
    }

    public static String extractPersonNameFromQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        var matcher = PERSON_BEFORE_STEWARD.matcher(question.strip());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    public static boolean isCertificateTypeFollowUp(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String t = question.strip();
        if (t.length() > 24) {
            return false;
        }
        return containsAny(t, "类型", "哪些", "什么", "啥", "具体", "详细", "涉及")
                && !containsAny(t, "主体", "公司类型", "实体类型", "有限责任公司");
    }

    private static boolean containsCertKeyword(String text) {
        return containsAny(text, "证照", "许可证", "执照", "资质", "备案", "证书");
    }

    private static boolean containsAny(String text, String... needles) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
