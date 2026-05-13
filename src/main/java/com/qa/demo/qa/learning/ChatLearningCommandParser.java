package com.qa.demo.qa.learning;

import com.qa.demo.qa.core.QaScopes;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 从聊天问句中识别「记住/学习」类指令并抽取待写入内容。
 */
@Component
public class ChatLearningCommandParser {

    public record LearningCommand(String triggerWord, String content, String scope) {
    }

    public Optional<LearningCommand> parse(String question, String defaultScope) {
        if (question == null) {
            return Optional.empty();
        }
        String raw = question.trim();
        if (raw.isBlank()) {
            return Optional.empty();
        }
        for (String trigger : List.of("记住", "学习", "学一下", "请学习", "请记住", "记录一下", "帮我记住")) {
            int idx = raw.indexOf(trigger);
            if (idx < 0) {
                continue;
            }
            String suffix = raw.substring(idx + trigger.length())
                    .replaceFirst("^[：:，,。\\s]+", "")
                    .trim();
            String scope = resolveLearningScope(suffix, defaultScope);
            String learningContent = selectLearningContent(raw, suffix);
            if (!learningContent.isBlank()) {
                return Optional.of(new LearningCommand(trigger, learningContent, scope));
            }
        }
        return Optional.empty();
    }

    private String resolveLearningScope(String text, String defaultScope) {
        String raw = text == null ? "" : text.toLowerCase();
        if (raw.contains("个人") || raw.contains("我的") || raw.contains("我自己")) {
            return QaScopes.PERSONAL;
        }
        if (raw.contains("企业") || raw.contains("公司")) {
            return QaScopes.ENTERPRISE;
        }
        return QaScopes.normalize(defaultScope);
    }

    private String selectLearningContent(String raw, String suffix) {
        String primary = suffix == null ? "" : suffix.trim();
        String fallback = raw == null ? "" : raw.trim();
        if (primary.isBlank()) {
            primary = fallback;
        }
        if (isFactStyleLearning(primary) && countCjkChars(primary) >= 4) {
            return primary;
        }
        if (isFactStyleLearning(fallback) && countCjkChars(fallback) >= 4) {
            return fallback;
        }
        if (primary.length() >= 8) {
            return primary;
        }
        if (fallback.length() >= 12) {
            return fallback;
        }
        if (countCjkChars(primary) >= 6) {
            return primary;
        }
        return "";
    }

    private boolean isFactStyleLearning(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("是")
                || text.contains("叫")
                || text.contains("就是")
                || text.contains("别名")
                || text.contains("简称")
                || text.contains("对应");
    }

    private int countCjkChars(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (char ch : text.toCharArray()) {
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                count++;
            }
        }
        return count;
    }
}
