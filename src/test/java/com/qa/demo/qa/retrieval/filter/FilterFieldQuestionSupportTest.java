package com.qa.demo.qa.retrieval.filter;

import com.qa.demo.qa.config.BusinessRulesConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterFieldQuestionSupportTest {

    @Test
    void matchesEstablishmentThresholdQuestion() {
        BusinessRulesConfig.FilterFieldCoverageRule rule = new BusinessRulesConfig.FilterFieldCoverageRule();
        rule.setId("establishment_date");
        rule.setDisplayLabel("成立日期");
        rule.setQuestionAnyKeywords(List.of("成立时间", "成立日期", "设立日期"));
        rule.setFilterIntentKeywords(List.of("有哪些", "超过", "以上", "以下", "之前", "之后", "年"));

        assertTrue(FilterFieldQuestionSupport.matchRule(
                "成立时间超过 10 年的公司有哪些",
                List.of(rule)
        ).isPresent());
    }
}
