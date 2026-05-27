package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.domain.EnterpriseLexicon;
import org.springframework.stereotype.Component;

@Component
public class SqlTopKResolver {

    private final QaAssistantProperties properties;
    private final EnterpriseLexicon lexicon;

    public SqlTopKResolver(QaAssistantProperties properties, EnterpriseLexicon lexicon) {
        this.properties = properties;
        this.lexicon = lexicon;
    }

    public int resolve(String question, RetrievalPlan plan) {
        int base = Math.max(1, properties.getMysqlTopK());
        if (plan != null && plan.personRoleList()) {
            return Math.max(base, plan.finalEvidenceTopK());
        }
        if (question == null || question.isBlank()) {
            return base;
        }
        if (lexicon.containsAny(question, lexicon.sqlListStyleKeywords())) {
            return Math.max(base, 20);
        }
        if (lexicon.containsAny(question, lexicon.sqlCountStyleKeywords())) {
            return Math.max(base, 10);
        }
        return base;
    }
}
