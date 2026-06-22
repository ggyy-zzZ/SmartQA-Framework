package com.qa.demo.qa.domain;

import com.qa.demo.qa.config.BusinessRulesConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;

/**
 * 通用的业务场景规则引擎。
 * <p>
 * 所有特定业务的硬编码规则（话术模式、实体类型、查询类型判断）
 * 都应通过 {@link BusinessRulesConfig} 配置，并由此引擎执行匹配。
 * <p>
 * 原则：
 * <ul>
 *   <li>代码只处理"通用模式匹配"，不直接耦合业务语义</li>
 *   <li>业务规则（关键词、查询类型、数据源）全部外置到 business-rules.json</li>
 *   <li>新增业务场景只需修改配置，无需修改代码</li>
 * </ul>
 */
@Component
public class ScenarioRuleEngine {
    private static final List<String> DEFAULT_FILTER_RULE_PREFIXES = List.of(
            "只要", "凡是", "如果", "对于", "关于", "按照", "按", "不是存续", "不是在业"
    );

    private final BusinessRulesConfig config;

    public ScenarioRuleEngine(BusinessRulesConfig config) {
        this.config = config;
    }

    /**
     * 从问句中提取人名。
     * 模式从 business-rules.json 的 intentRules.personNamePatterns 读取。
     */
    public String extractPersonName(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String t = question.strip();
        BusinessRulesConfig.PersonNamePatterns patterns = config.getIntentRules().getPersonNamePatterns();

        // 优先: 人名+动作词模式
        for (java.util.regex.Pattern p : patterns.getBeforeActionPatterns()) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                String name = m.group(1);
                if (!isBlocklisted(name, patterns)) {
                    return name;
                }
            }
        }

        // 其次: 人名+离职模式
        for (java.util.regex.Pattern p : patterns.getBeforeResignPatterns()) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                String name = m.group(1);
                if (!isBlocklisted(name, patterns)) {
                    return name;
                }
            }
        }

        // 再次: 问句开头人名模式
        for (java.util.regex.Pattern p : patterns.getLeadingNamePatterns()) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                String name = m.group(1);
                if (!isBlocklisted(name, patterns)) {
                    return name;
                }
            }
        }

        return "";
    }

    /**
     * 根据数据源和检索策略获取截断阈值。
     */
    public int getTruncationThreshold(String source, String retrievalStrategy) {
        if (source == null || retrievalStrategy == null) {
            return Integer.MAX_VALUE;
        }
        for (BusinessRulesConfig.SourceThreshold threshold : config.getRetrievalThresholds().getSourceThresholds()) {
            if (source.equals(threshold.getSource())
                    && retrievalStrategy.equalsIgnoreCase(threshold.getRetrievalStrategy())) {
                return threshold.getMinCount();
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 检查是否需要触发截断。
     */
    public boolean shouldTruncate(String source, String retrievalStrategy, long currentCount) {
        int threshold = getTruncationThreshold(source, retrievalStrategy);
        return currentCount >= threshold;
    }

    public boolean questionSuggestsCompiledDocs(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        for (String keyword : config.getIntentRouting().getCompiledDocumentKeywords()) {
            if (keyword != null && !keyword.isBlank() && question.contains(keyword.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断问句是否包含纠偏前缀。
     */
    public boolean isCorrectionQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String t = question.trim();
        if (looksLikeFilterRuleStatement(t)) {
            return false;
        }
        for (BusinessRulesConfig.CorrectionRule rule : config.getCorrectionRules()) {
            for (java.util.regex.Pattern p : rule.getCompiledPrefixPatterns()) {
                if (p.matcher(t).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 过滤「只要不是存续/在业都算失效」这类规则补充语句，避免被误判为纠偏。
     */
    private boolean looksLikeFilterRuleStatement(String question) {
        String text = question == null ? "" : question.strip();
        if (text.isBlank()) {
            return false;
        }
        for (String prefix : filterRulePrefixes()) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return (text.contains("算作") || text.contains("都算") || text.contains("均算"))
                && (text.contains("失效") || text.contains("无效"))
                && (text.contains("存续") || text.contains("在业"));
    }

    private List<String> filterRulePrefixes() {
        List<String> configured = config.getIntentRouting().getFilterRulePrefixes();
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_FILTER_RULE_PREFIXES;
        }
        return configured;
    }

    /**
     * 从问句中提取纠偏后的实体名称。
     *
     * @param question 原始问句
     * @param entityType 实体类型 (e.g., "company")
     * @return 提取到的实体名称
     */
    public String extractCorrectedEntityName(String question, String entityType) {
        if (question == null || question.isBlank() || entityType == null || entityType.isBlank()) {
            return "";
        }
        String t = question.trim();

        for (BusinessRulesConfig.CorrectionRule rule : config.getCorrectionRules()) {
            if (!entityType.equals(rule.getEntityType())) {
                continue;
            }
            java.util.regex.Pattern pattern = rule.getCompiledEntityPattern();
            if (pattern == null) {
                continue;
            }
            Matcher m = pattern.matcher(t);
            if (m.find()) {
                return normalizeEntityName(m.group(1), rule.getSuffixes());
            }
        }
        return "";
    }

    /**
     * 检查问句是否询问关联主体（有这些词时不触发纠偏收窄）。
     */
    public boolean asksRelatedEntities(String question, String entityType) {
        if (question == null) {
            return false;
        }
        for (BusinessRulesConfig.CorrectionRule rule : config.getCorrectionRules()) {
            if (!entityType.equals(rule.getEntityType())) {
                continue;
            }
            String q = question;
            for (String keyword : rule.getRelatedKeywords()) {
                if (q.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 获取某查询类型的输出契约。
     */
    public String getOutputContract(String contractKey) {
        if (contractKey == null || contractKey.isBlank()) {
            return config.getOutputContracts().getOrDefault("default", "");
        }
        return config.getOutputContracts().getOrDefault(contractKey, "");
    }

    private boolean isBlocklisted(String name, BusinessRulesConfig.PersonNamePatterns patterns) {
        return patterns.getPronounBlocklist().contains(name);
    }

    private static boolean containsAny(String text, List<String> needles) {
        if (text == null || needles == null || needles.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && lower.contains(needle.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeEntityName(String raw, List<String> suffixes) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        // 移除常见的纠偏前缀词
        s = s.replaceAll("^[不对不是错了错啦说错了你搞错了搞错了纠正一下更正应该是其实是正确是应当是，,\\s]+", "");
        return s.trim();
    }
}