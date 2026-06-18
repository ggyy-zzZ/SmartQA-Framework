package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalExecutionProfile;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogRegistry;
import org.springframework.stereotype.Component;

@Component
public class RetrievalPlanFactory {

    private final QaAssistantProperties properties;
    private final RetrievalCatalogRegistry catalogRegistry;

    public RetrievalPlanFactory(QaAssistantProperties properties, RetrievalCatalogRegistry catalogRegistry) {
        this.properties = properties;
        this.catalogRegistry = catalogRegistry;
    }

    public RetrievalPlan from(IntentDecision intent) {
        String queryType = intent == null ? "" : intent.queryType();
        RetrievalExecutionProfile execution = catalogRegistry.executionFor(queryType);
        int graphTopK = execution.expandRecallTopK()
                ? Math.max(properties.getRecallGraphTopK(), properties.getRecallGraphPersonRoleTopK())
                : properties.getRecallGraphTopK();
        int evidenceTopK = execution.expandRecallTopK()
                ? Math.max(properties.getRetrievalTopK(), properties.getRecallGraphPersonRoleTopK())
                : properties.getRetrievalTopK();
        return RetrievalPlan.of(intent, graphTopK, evidenceTopK, execution);
    }
}
