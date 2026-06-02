package com.qa.demo.qa.learning;

import com.qa.demo.qa.core.QaScopes;
import com.qa.demo.qa.domain.EnterpriseLexicon;
import com.qa.demo.qa.retrieval.EmployeeBaseKnowledgeService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从聊天问句中识别「记住/学习」类指令并抽取待写入内容。
 */
@Component
public class ChatLearningCommandParser {
    private static final Pattern IMPLICIT_ALIAS_STATEMENT = Pattern.compile(
            "^([\\u4e00-\\u9fa5]{2,6})\\s*(?:是|就是|即|叫|叫做)\\s*([\\u4e00-\\u9fa5]{2,6})$"
    );
    private static final String IMPLICIT_ALIAS_TRIGGER = "implicit_alias";
    private static final Set<String> FALLBACK_ALIAS_STOPWORDS = Set.of(
            "哪些", "哪个", "什么", "谁", "多少", "怎么", "为何", "为什么", "如何", "是否", "有哪"
    );

    private final EmployeeBaseKnowledgeService employeeBaseKnowledgeService;
    private final Set<String> aliasStopwords;

    public ChatLearningCommandParser(
            EmployeeBaseKnowledgeService employeeBaseKnowledgeService,
            EnterpriseLexicon enterpriseLexicon
    ) {
        this.employeeBaseKnowledgeService = employeeBaseKnowledgeService;
        Set<String> configured = new LinkedHashSet<>();
        if (enterpriseLexicon != null && enterpriseLexicon.aliasStopwords() != null) {
            for (String token : enterpriseLexicon.aliasStopwords()) {
                if (token != null && !token.isBlank()) {
                    configured.add(token.trim());
                }
            }
        }
        if (configured.isEmpty()) {
            configured.addAll(FALLBACK_ALIAS_STOPWORDS);
        }
        this.aliasStopwords = Set.copyOf(configured);
    }

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
        return parseImplicitAliasLearning(raw, defaultScope);
    }

    private String resolveLearningScope(String text, String defaultScope) {
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

    private Optional<LearningCommand> parseImplicitAliasLearning(String raw, String defaultScope) {
        if (raw.contains("?") || raw.contains("？")) {
            return Optional.empty();
        }
        String normalized = stripTrailingPunctuation(raw);
        Matcher matcher = IMPLICIT_ALIAS_STATEMENT.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String left = matcher.group(1);
        String right = matcher.group(2);
        if (!isValidAliasToken(left) || !isValidAliasToken(right) || left.equals(right)) {
            return Optional.empty();
        }
        if (!looksLikePersonAlias(left, right)) {
            return Optional.empty();
        }
        String content = left + "是" + right;
        String scope = resolveLearningScope(content, defaultScope);
        return Optional.of(new LearningCommand(IMPLICIT_ALIAS_TRIGGER, content, scope));
    }

    private String stripTrailingPunctuation(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceFirst("[，,。；;：:\\s]+$", "").trim();
    }

    private boolean isValidAliasToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = token.trim();
        if (t.length() < 2 || t.length() > 6) {
            return false;
        }
        return !aliasStopwords.contains(t);
    }

    private boolean looksLikePersonAlias(String left, String right) {
        if (startsWithInterrogative(left) || startsWithInterrogative(right)) {
            return false;
        }
        Integer leftId = employeeBaseKnowledgeService.resolveToEmployeeId(left);
        Integer rightId = employeeBaseKnowledgeService.resolveToEmployeeId(right);
        // 至少一侧能命中员工索引，才把「A是B」视为人物别名学习，避免把普通问句误判为学习指令。
        return leftId != null || rightId != null;
    }

    private boolean startsWithInterrogative(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = token.trim();
        for (String prefix : aliasStopwords) {
            if (t.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
