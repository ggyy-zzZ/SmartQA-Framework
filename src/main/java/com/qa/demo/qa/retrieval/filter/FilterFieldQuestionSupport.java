package com.qa.demo.qa.retrieval.filter;

import com.qa.demo.qa.core.InformationNeed;

/**
 * 阈值/筛选问句识别（配置驱动，供 P3b 专用检索与 P4 字段覆盖检测共用）。
 */
public final class FilterFieldQuestionSupport {

    private FilterFieldQuestionSupport() {
    }

    public static boolean isFilterThresholdNeed(InformationNeed need) {
        return need != null
                && need.isAggregate()
                && need.reason() != null
                && need.reason().startsWith("inference:filter_threshold:");
    }

    public static java.util.Optional<com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule> matchRule(
            String question,
            java.util.List<com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule> rules
    ) {
        return matchRule(question, rules, false);
    }

    /** 阈值专用检索：「设立日期」等别名只走字段澄清，不触发 P3b 早退。 */
    public static java.util.Optional<com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule> matchThresholdRule(
            String question,
            java.util.List<com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule> rules
    ) {
        return matchRule(question, rules, true);
    }

    private static java.util.Optional<com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule> matchRule(
            String question,
            java.util.List<com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule> rules,
            boolean thresholdOnly
    ) {
        if (question == null || question.isBlank() || rules == null || rules.isEmpty()) {
            return java.util.Optional.empty();
        }
        String q = question.strip();
        if (!looksLikeFilterListQuestion(q)) {
            return java.util.Optional.empty();
        }
        for (com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule rule : rules) {
            if (rule != null && matchesQuestion(q, rule)) {
                if (thresholdOnly && shouldSkipThresholdForEstablishmentAlias(q, rule)) {
                    continue;
                }
                return java.util.Optional.of(rule);
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean shouldSkipThresholdForEstablishmentAlias(
            String question,
            com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule rule
    ) {
        if (rule == null || rule.getId() == null || !"establishment_date".equals(rule.getId())) {
            return false;
        }
        return question.contains("设立日期")
                && !question.contains("成立时间")
                && !question.contains("成立日期");
    }

    public static java.util.List<com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule> defaultRules() {
        com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule capital =
                new com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule();
        capital.setId("registered_capital");
        capital.setDisplayLabel("注册资本");
        capital.setQuestionAnyKeywords(java.util.List.of("注册资本", "注册资金"));
        capital.setFilterIntentKeywords(java.util.List.of("有哪些", "超过", "以上", "以下", "大于", "小于", "多少"));
        capital.setSourceColumns(java.util.List.of("reg_capital", "registered_capital"));

        com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule establishment =
                new com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule();
        establishment.setId("establishment_date");
        establishment.setDisplayLabel("成立日期");
        establishment.setQuestionAnyKeywords(java.util.List.of("成立时间", "成立日期", "设立日期"));
        establishment.setFilterIntentKeywords(java.util.List.of("有哪些", "超过", "以上", "以下", "之前", "之后", "年"));
        establishment.setSourceColumns(java.util.List.of("establishment_date", "established_date", "setup_date"));

        return java.util.List.of(capital, establishment);
    }

    public static InformationNeed aggregateNeedForRule(
            com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule rule
    ) {
        String facet = rule != null && rule.getId() != null && !rule.getId().isBlank()
                ? rule.getId()
                : "aggregate";
        return new InformationNeed(
                facet,
                InformationNeed.GRANULARITY_AGGREGATE,
                true,
                0.86,
                "inference:filter_threshold:" + facet
        );
    }

    public static boolean looksLikeFilterListQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("有哪些")
                || lower.contains("列出")
                || lower.contains("超过")
                || lower.contains("以上")
                || lower.contains("以下")
                || lower.contains("大于")
                || lower.contains("小于")
                || lower.contains("之前")
                || lower.contains("之后")
                || lower.contains("多少家")
                || lower.contains("几家");
    }

    public static boolean matchesQuestion(
            String question,
            com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule rule
    ) {
        boolean keywordHit = containsAny(question, rule.getQuestionAnyKeywords());
        if (!keywordHit) {
            return false;
        }
        java.util.List<String> filterKeywords = rule.getFilterIntentKeywords();
        if (filterKeywords == null || filterKeywords.isEmpty()) {
            return true;
        }
        return containsAny(question, filterKeywords);
    }

    public static java.util.List<String> resolveSourceColumns(
            com.qa.demo.qa.config.BusinessRulesConfig.FilterFieldCoverageRule rule
    ) {
        if (rule == null) {
            return java.util.List.of();
        }
        if (rule.getSourceColumns() != null && !rule.getSourceColumns().isEmpty()) {
            return java.util.List.copyOf(rule.getSourceColumns());
        }
        if (rule.getId() == null) {
            return java.util.List.of();
        }
        return switch (rule.getId()) {
            case "registered_capital" -> java.util.List.of("reg_capital", "registered_capital");
            case "establishment_date" -> java.util.List.of("establishment_date", "established_date", "setup_date");
            default -> java.util.List.of();
        };
    }

    private static boolean containsAny(String text, java.util.List<String> keywords) {
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
}
