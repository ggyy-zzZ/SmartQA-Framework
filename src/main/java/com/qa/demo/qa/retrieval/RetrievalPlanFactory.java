package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.InformationNeed;
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

    public RetrievalPlan from(IntentDecision intent, InformationNeed need) {
        return from(intent, need, null);
    }

    public RetrievalPlan from(IntentDecision intent, InformationNeed need, EvidencePresentationContext presentation) {
        InformationNeed effectiveNeed = need != null ? need : InformationNeed.defaultSemantic();
        RetrievalExecutionProfile execution = catalogRegistry.executionFor(effectiveNeed, intent);
        int graphTopK = resolveGraphTopK(execution, presentation);
        int evidenceTopK = resolveEvidenceTopK(execution, presentation);
        return RetrievalPlan.of(intent, effectiveNeed, graphTopK, evidenceTopK, execution);
    }

    private int resolveGraphTopK(RetrievalExecutionProfile execution, EvidencePresentationContext presentation) {
        if (execution.expandRecallTopK()) {
            return Math.max(properties.getRecallGraphTopK(), properties.getRecallGraphPersonRoleTopK());
        }
        if (presentation != null && presentation.isFullPresentation()) {
            return Math.max(properties.getRecallGraphTopK(), properties.getRecallGraphPersonRoleTopK());
        }
        return properties.getRecallGraphTopK();
    }

    private int resolveEvidenceTopK(RetrievalExecutionProfile execution, EvidencePresentationContext presentation) {
        if (presentation != null) {
            int fromPresentation = Math.max(1, presentation.evidenceTopK());
            if (execution.expandRecallTopK()) {
                return Math.max(fromPresentation, properties.getRecallGraphPersonRoleTopK());
            }
            return fromPresentation;
        }
        int evidenceTopK = execution.expandRecallTopK()
                ? Math.max(properties.getRetrievalTopK(), properties.getRecallGraphPersonRoleTopK())
                : properties.getRetrievalTopK();
        return evidenceTopK;
    }

    /** @deprecated 使用 {@link #from(IntentDecision, InformationNeed)} */
    @Deprecated
    public RetrievalPlan from(IntentDecision intent) {
        return from(intent, null);
    }
}
