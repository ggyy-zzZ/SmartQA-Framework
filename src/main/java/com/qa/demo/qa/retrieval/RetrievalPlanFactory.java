package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.intent.QueryTypeRoutingPolicy;
import org.springframework.stereotype.Component;

@Component
public class RetrievalPlanFactory {

    private final QaAssistantProperties properties;
    private final QueryTypeRoutingPolicy routingPolicy;

    public RetrievalPlanFactory(QaAssistantProperties properties, QueryTypeRoutingPolicy routingPolicy) {
        this.properties = properties;
        this.routingPolicy = routingPolicy;
    }

    public RetrievalPlan from(IntentDecision intent) {
        boolean personRoleList = intent != null && intent.isPersonRoleListQuery();
        String queryType = intent == null ? "" : intent.queryType();
        boolean certificateQuery = routingPolicy.isCertificateQueryType(queryType);
        int graphTopK = personRoleList
                ? Math.max(properties.getRecallGraphTopK(), properties.getRecallGraphPersonRoleTopK())
                : properties.getRecallGraphTopK();
        int evidenceTopK = personRoleList || certificateQuery
                ? Math.max(properties.getRetrievalTopK(), properties.getRecallGraphPersonRoleTopK())
                : properties.getRetrievalTopK();
        return RetrievalPlan.of(intent, graphTopK, evidenceTopK, personRoleList, certificateQuery);
    }
}
