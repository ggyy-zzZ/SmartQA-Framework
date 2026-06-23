package com.qa.demo.qa.answer;

import com.qa.demo.knowledge.EvidenceSchemaRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.intent.IntentRoutingPolicy;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogRegistry;
import com.qa.demo.qa.retrieval.parent.ParentScopedCompanyListSupport;
import com.qa.demo.qa.retrieval.region.RegionListQuestionSupport;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 是否允许基于检索证据调用 LLM 生成；证据不足时拒答。
 */
@Service
public class QaAnswerGateService {

    public record GateDecision(
            boolean allowGenerate,
            boolean canAnswer,
            String rejectReason
    ) {
        public static GateDecision allow() {
            return new GateDecision(true, true, null);
        }
    }

    private final QaAssistantProperties properties;
    private final IntentRoutingPolicy routingPolicy;
    private final RetrievalCatalogRegistry catalogRegistry;

    public QaAnswerGateService(
            QaAssistantProperties properties,
            EvidenceSchemaRegistry evidenceSchemas,
            IntentRoutingPolicy routingPolicy,
            RetrievalCatalogRegistry catalogRegistry
    ) {
        this.properties = properties;
        this.routingPolicy = routingPolicy;
        this.catalogRegistry = catalogRegistry;
    }

    public GateDecision evaluate(IntentDecision intent, List<ContextChunk> evidence) {
        return evaluate("", intent, null, evidence);
    }

    public GateDecision evaluate(IntentDecision intent, InformationNeed need, List<ContextChunk> evidence) {
        return evaluate("", intent, need, evidence);
    }

    public GateDecision evaluate(
            String question,
            IntentDecision intent,
            InformationNeed need,
            List<ContextChunk> evidence
    ) {
        return evaluate(question, intent, need, evidence, null);
    }

    public GateDecision evaluate(
            String question,
            IntentDecision intent,
            InformationNeed need,
            List<ContextChunk> evidence,
            String retrievalSource
    ) {
        if (retrievalSource != null && retrievalSource.startsWith("dedicated_miss:")) {
            return new GateDecision(false, false, "dedicated_path_miss");
        }
        if (!properties.isAnswerGateEnabled()) {
            boolean hasEvidence = evidence != null && !evidence.isEmpty();
            return hasEvidence ? GateDecision.allow() : new GateDecision(false, false, "insufficient_evidence");
        }
        if (evidence == null || evidence.isEmpty()) {
            return new GateDecision(false, false, "insufficient_evidence");
        }
        if (properties.isAnswerGateBlockOnUnknownIntent()
                && intent != null
                && "unknown".equalsIgnoreCase(intent.intent())
                && !allowsUnknownIntent(retrievalSource, need)) {
            return new GateDecision(false, false, "unknown_intent");
        }
        boolean catalogGateSatisfied = false;
        if (need != null) {
            if (!catalogRegistry.satisfiesGate(need, evidence)) {
                return new GateDecision(false, false, "need_evidence_mismatch");
            }
            catalogGateSatisfied = catalogRegistry.hasGateRule(need);
        }
        if (!catalogGateSatisfied && need != null) {
            var requiredSchemas = routingPolicy.requiredEvidenceSchemaIds(need);
            if (!requiredSchemas.isEmpty() && !hasEvidenceForSchemas(evidence, requiredSchemas)) {
                return new GateDecision(false, false, "need_evidence_mismatch");
            }
        }
        if (evidence.size() < properties.getAnswerGateMinEvidenceCount()) {
            return new GateDecision(false, false, "evidence_count_below_min");
        }
        double maxScore = evidence.stream().mapToDouble(ContextChunk::score).max().orElse(0.0);
        if (maxScore < properties.getAnswerGateMinTopScore()) {
            return new GateDecision(false, false, "top_score_below_min");
        }
        return GateDecision.allow();
    }

    /** 专用结构化检索已成功时，不因 intent=unknown 拒答。 */
    private static boolean allowsUnknownIntent(String retrievalSource, InformationNeed need) {
        if (retrievalSource != null && !retrievalSource.isBlank()) {
            if (retrievalSource.startsWith("dedicated_miss:")
                    || retrievalSource.startsWith("unified_constrained_rerank")
                    || retrievalSource.startsWith("unified_hybrid")) {
                return false;
            }
            if ("child_company_list".equals(retrievalSource)
                    || "filter_threshold".equals(retrievalSource)
                    || "unified_type_catalog".equals(retrievalSource)
                    || retrievalSource.contains("dedicated_list")
                    || retrievalSource.contains("dedicated_certificate")
                    || retrievalSource.contains("aggregate_count")) {
                return true;
            }
        }
        if (need != null && need.reason() != null) {
            String reason = need.reason();
            if (ParentScopedCompanyListSupport.isParentScopedCompanyListNeed(need)
                    || RegionListQuestionSupport.isRegionCompanyListNeed(need)) {
                return true;
            }
            if (reason.startsWith("inference:parent_company_list")
                    || reason.startsWith("inference:region_company_list")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEvidenceForSchemas(java.util.List<ContextChunk> evidence, java.util.Set<String> schemaIds) {
        if (evidence == null || evidence.isEmpty() || schemaIds == null || schemaIds.isEmpty()) {
            return false;
        }
        for (String schemaId : schemaIds) {
            if (hasEvidenceForSchema(evidence, schemaId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEvidenceForSchema(java.util.List<ContextChunk> evidence, String schemaId) {
        if (schemaId == null || schemaId.isBlank()) {
            return false;
        }
        return evidence.stream().anyMatch(c ->
                c != null
                        && schemaId.equals(c.evidenceSchema())
                        && c.snippet() != null
                        && !c.snippet().isBlank()
        );
    }
}
