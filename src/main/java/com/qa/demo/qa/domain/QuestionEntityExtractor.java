package com.qa.demo.qa.domain;

import com.qa.demo.qa.core.IntentDecision;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从自然语言问句抽取人名、公司 hint（规则兜底，与 LLM 抽取互补）。
 */
@Component
public class QuestionEntityExtractor {

    private static final Pattern PREFIX_NOISE = Pattern.compile(
            "^(请问|查询|帮我查|谁|什么人)"
    );

    private final EnterpriseLexicon lexicon;

    public QuestionEntityExtractor(EnterpriseLexicon lexicon) {
        this.lexicon = lexicon;
    }

    public String resolvePersonName(String question, IntentDecision intent) {
        if (intent != null && intent.hasPersonFocus()) {
            return intent.personName().trim();
        }
        String extracted = extractPersonName(question);
        return extracted == null ? "" : extracted;
    }

    public String extractPersonName(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String q = question.trim();
        for (String marker : lexicon.personMarkers()) {
            int idx = q.indexOf(marker);
            if (idx > 1) {
                String name = personNameBefore(q, idx);
                if (name != null) {
                    return name;
                }
            }
        }
        if (q.contains("法人")
                && (q.contains("哪些主体") || q.contains("哪些公司") || q.contains("哪些企业"))) {
            int whichIdx = q.indexOf("哪些");
            if (whichIdx > 1) {
                return personNameBefore(q, whichIdx);
            }
        }
        return null;
    }

    public List<String> extractCompanyHints(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        String q = question;
        Set<String> hints = new HashSet<>();
        String[] tokens = q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        for (String token : tokens) {
            if (token.length() >= 4 && token.matches("\\d+")) {
                hints.add(token);
            }
        }
        int window = lexicon.companyHintWindowChars();
        for (String suffix : lexicon.companySuffixes()) {
            int idx = question.indexOf(suffix);
            if (idx > 0) {
                int start = Math.max(0, idx - window);
                String hint = question.substring(start, idx + suffix.length()).trim();
                if (hint.length() >= 4) {
                    hints.add(hint);
                }
            }
        }
        return new ArrayList<>(hints);
    }

    public boolean isRoleRelationQuery(String question) {
        return lexicon.containsAny(question, lexicon.roleRelationTriggers());
    }

    public String inferRoleFocus(String question) {
        return lexicon.inferRoleFocus(question);
    }

    public String inferQueryType(String question, String personName) {
        return lexicon.inferQueryType(question, personName == null ? "" : personName);
    }

    private String personNameBefore(String q, int markerStart) {
        String name = q.substring(0, markerStart).trim();
        name = PREFIX_NOISE.matcher(name).replaceFirst("").trim();
        if (name.endsWith("是") && name.length() > 1) {
            name = name.substring(0, name.length() - 1).trim();
        }
        if (name.length() >= 2 && name.length() <= 12) {
            return name;
        }
        return null;
    }
}
