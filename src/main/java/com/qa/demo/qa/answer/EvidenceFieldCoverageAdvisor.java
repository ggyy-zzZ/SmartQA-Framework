package com.qa.demo.qa.answer;

import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.retrieval.filter.FilterFieldQuestionSupport;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 检测阈值/筛选问句所需字段是否在 evidence 中出现；缺失时生成追问式澄清（P4）。
 */
@Component
public class EvidenceFieldCoverageAdvisor {

    private static final Pattern CAPITAL_VALUE_PATTERN = Pattern.compile(
            "(注册资本|注册资金|registeredCapital|reg_capital)\\s*[=：:]\\s*([^;\\n\\r]{1,64})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern ESTABLISHMENT_VALUE_PATTERN = Pattern.compile(
            "(成立日期|成立时间|establishmentDate|found_date|establishment_date)\\s*[=：:]\\s*(\\d{4}[-/年]?\\d{1,2}[-/日]?\\d{0,2}|\\d{8})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    public record FieldCoverageGap(
            String ruleId,
            String fieldLabel,
            String userHint
    ) {
    }

    private final List<BusinessRulesConfig.FilterFieldCoverageRule> rules;

    public EvidenceFieldCoverageAdvisor(BusinessRulesConfig businessRulesConfig) {
        List<BusinessRulesConfig.FilterFieldCoverageRule> loaded =
                businessRulesConfig.getFilterFieldCoverageRules() == null
                        ? List.of()
                        : businessRulesConfig.getFilterFieldCoverageRules();
        this.rules = loaded.isEmpty() ? FilterFieldQuestionSupport.defaultRules() : List.copyOf(loaded);
    }

    public Optional<FieldCoverageGap> detectFilterFieldGap(
            String question,
            List<ContextChunk> evidence,
            InformationNeed need
    ) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        if (need != null && need.isTypeCatalog()) {
            return Optional.empty();
        }
        Optional<BusinessRulesConfig.FilterFieldCoverageRule> matched =
                FilterFieldQuestionSupport.matchRule(question, rules);
        if (matched.isEmpty()) {
            return Optional.empty();
        }
        BusinessRulesConfig.FilterFieldCoverageRule rule = matched.get();
        String corpus = evidence == null || evidence.isEmpty()
                ? ""
                : buildEvidenceCorpus(evidence);
        if (hasSufficientFilterFieldCoverage(evidence, corpus, rule)) {
            return Optional.empty();
        }
        String label = rule.getDisplayLabel() == null || rule.getDisplayLabel().isBlank()
                ? rule.getId()
                : rule.getDisplayLabel();
        return Optional.of(new FieldCoverageGap(
                rule.getId(),
                label,
                buildHint(label)
        ));
    }

    public String buildClarification(FieldCoverageGap gap) {
        if (gap == null) {
            return "当前证据未包含筛选所需字段，请补充问法或指定具体对象。";
        }
        return gap.userHint();
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw.strip())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSufficientFilterFieldCoverage(
            List<ContextChunk> evidence,
            String corpus,
            BusinessRulesConfig.FilterFieldCoverageRule rule
    ) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        int usableChunks = 0;
        for (ContextChunk chunk : evidence) {
            if (chunk == null) {
                continue;
            }
            String snippet = buildChunkText(chunk);
            if (snippet.isBlank()) {
                continue;
            }
            if (chunkHasUsableFilterValue(snippet, rule)) {
                usableChunks++;
            }
        }
        if (usableChunks == 0) {
            return false;
        }
        if (evidence.stream().allMatch(c -> c != null && "mysql-filter-threshold".equals(c.source()))) {
            return true;
        }
        int minRequired = evidence.size() >= 10 ? 3 : (evidence.size() >= 3 ? 2 : 1);
        double ratio = (double) usableChunks / evidence.size();
        double minRatio = evidence.size() >= 10 ? 0.75 : 1.0;
        return usableChunks >= minRequired && ratio >= minRatio;
    }

    private static boolean chunkHasUsableFilterValue(String snippet, BusinessRulesConfig.FilterFieldCoverageRule rule) {
        if (snippet == null || snippet.isBlank() || rule == null || rule.getId() == null) {
            return false;
        }
        return switch (rule.getId()) {
            case "registered_capital" -> hasCapitalValue(snippet);
            case "establishment_date" -> hasEstablishmentValue(snippet);
            default -> hasMarkerInEvidence(snippet, rule.getSnippetMarkers());
        };
    }

    private static String buildChunkText(ContextChunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (chunk.snippet() != null) {
            sb.append(chunk.snippet());
        }
        if (chunk.displayLabel() != null) {
            sb.append('\n').append(chunk.displayLabel());
        }
        if (chunk.field() != null) {
            sb.append('\n').append(chunk.field());
        }
        return sb.toString();
    }

    private static boolean hasUsableFilterValue(String corpus, BusinessRulesConfig.FilterFieldCoverageRule rule) {
        if (corpus == null || corpus.isBlank() || rule == null || rule.getId() == null) {
            return false;
        }
        return switch (rule.getId()) {
            case "registered_capital" -> hasCapitalValue(corpus);
            case "establishment_date" -> hasEstablishmentValue(corpus);
            default -> hasMarkerInEvidence(corpus, rule.getSnippetMarkers());
        };
    }

    private static boolean hasCapitalValue(String corpus) {
        var matcher = CAPITAL_VALUE_PATTERN.matcher(corpus);
        while (matcher.find()) {
            if (isMeaningfulValue(matcher.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEstablishmentValue(String corpus) {
        var matcher = ESTABLISHMENT_VALUE_PATTERN.matcher(corpus);
        while (matcher.find()) {
            if (isMeaningfulValue(matcher.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMeaningfulValue(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return !lower.equals("-")
                && !lower.equals("—")
                && !lower.equals("null")
                && !lower.equals("none")
                && !lower.contains("未维护")
                && !lower.contains("暂无")
                && !lower.contains("未知");
    }

    private static boolean hasMarkerInEvidence(String corpus, List<String> markers) {
        if (corpus == null || corpus.isBlank() || markers == null || markers.isEmpty()) {
            return false;
        }
        String lower = corpus.toLowerCase(Locale.ROOT);
        for (String marker : markers) {
            if (marker == null || marker.isBlank()) {
                continue;
            }
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String buildEvidenceCorpus(List<ContextChunk> evidence) {
        StringBuilder sb = new StringBuilder();
        for (ContextChunk chunk : evidence) {
            if (chunk == null) {
                continue;
            }
            if (chunk.snippet() != null) {
                sb.append(chunk.snippet()).append('\n');
            }
            if (chunk.displayLabel() != null) {
                sb.append(chunk.displayLabel()).append('\n');
            }
            if (chunk.field() != null) {
                sb.append(chunk.field()).append('\n');
            }
            if (chunk.source() != null) {
                sb.append(chunk.source()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String buildHint(String fieldLabel) {
        return "您的问题涉及「" + fieldLabel + "」筛选，但当前检索到的证据里还没有这一字段的取值。"
                + "该字段需在企业源业务系统中维护，本系统不会自动补数。"
                + "您可以：\n"
                + "1）换用证据里已有的条件（如公司名称、经营状态、地区）重新提问；\n"
                + "2）指定具体公司查询其他已维护字段；\n"
                + "3）待业务方补全源数据后再问筛选类问题。";
    }
}
