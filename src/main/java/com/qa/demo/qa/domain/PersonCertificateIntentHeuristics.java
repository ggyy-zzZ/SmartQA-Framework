package com.qa.demo.qa.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 「人物 × 证照」问句启发式（规则层），与 {@link EnterpriseLexicon#inferQueryType} 配合。
 */
public final class PersonCertificateIntentHeuristics {

    private static final Pattern PERSON_BEFORE_STEWARD = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4})\\s*(负责|管|监管|保管|执行)"
    );
    private static final Set<String> PRONOUN_NAME_BLOCKLIST = Set.of(
            "她", "他", "它", "其", "这", "那", "谁", "哪", "啥", "什"
    );
    private static final Pattern PERSON_BEFORE_RESIGN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4})\\s*离职"
    );
    private static final Pattern LEADING_PERSON_NAME = Pattern.compile(
            "^([\\u4e00-\\u9fa5]{2,3})(?=[，,。；;：:？?！!、\\s]|离职|负责|管|是|在|的|有)"
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
        if (containsCertKeyword(t)) {
            return containsAny(t, "哪些", "负责", "管", "列出", "多少", "什么", "啥", "涉及")
                    || PERSON_BEFORE_STEWARD.matcher(t).find();
        }
        return isPersonStewardshipListWithoutCertKeyword(t, person);
    }

    /**
     * 「某人负责/管了哪些东西」等口语化职责问句（无「证照」字样）。
     */
    public static boolean isPersonStewardshipListWithoutCertKeyword(String question, String personName) {
        if (question == null || question.isBlank() || personName == null || personName.isBlank()) {
            return false;
        }
        String t = question.strip();
        if (!containsAny(t, "负责", "管", "监管", "保管", "执行")) {
            return false;
        }
        return containsAny(t, "哪些", "什么", "啥", "东西", "涉及", "列出", "多少");
    }

    public static String extractPersonNameFromQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String t = question.strip();
        var resignMatcher = PERSON_BEFORE_RESIGN.matcher(t);
        if (resignMatcher.find()) {
            return resignMatcher.group(1);
        }
        var stewardMatcher = PERSON_BEFORE_STEWARD.matcher(t);
        while (stewardMatcher.find()) {
            String name = stewardMatcher.group(1);
            if (!PRONOUN_NAME_BLOCKLIST.contains(name)) {
                return name;
            }
        }
        var leadingMatcher = LEADING_PERSON_NAME.matcher(t);
        if (leadingMatcher.find()) {
            String name = leadingMatcher.group(1);
            if (!PRONOUN_NAME_BLOCKLIST.contains(name)) {
                return name;
            }
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
