package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import org.springframework.stereotype.Component;

@Component
public class RetrievalPlanFactory {

    private final QaAssistantProperties properties;

    public RetrievalPlanFactory(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public RetrievalPlan from(IntentDecision intent) {
        boolean personRoleList = intent != null && intent.isPersonRoleListQuery();
        int graphTopK = personRoleList
                ? Math.max(properties.getRecallGraphTopK(), properties.getRecallGraphPersonRoleTopK())
                : properties.getRecallGraphTopK();
        int evidenceTopK = personRoleList
                ? Math.max(properties.getRetrievalTopK(), properties.getRecallGraphPersonRoleTopK())
                : properties.getRetrievalTopK();
        return RetrievalPlan.of(intent, graphTopK, evidenceTopK);
    }
}
