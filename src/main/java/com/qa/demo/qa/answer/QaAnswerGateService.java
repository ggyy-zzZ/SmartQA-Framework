package com.qa.demo.qa.answer;

import com.qa.demo.knowledge.EvidenceSchemaRegistry;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.intent.QueryTypeRoutingPolicy;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final QueryTypeRoutingPolicy routingPolicy;
    private final RetrievalCatalogRegistry catalogRegistry;
    private final MiniMaxClient miniMaxClient;
    private final ObjectMapper objectMapper;

    public QaAnswerGateService(
            QaAssistantProperties properties,
            EvidenceSchemaRegistry evidenceSchemas,
            QueryTypeRoutingPolicy routingPolicy,
            RetrievalCatalogRegistry catalogRegistry,
            MiniMaxClient miniMaxClient,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.routingPolicy = routingPolicy;
        this.catalogRegistry = catalogRegistry;
        this.miniMaxClient = miniMaxClient;
        this.objectMapper = objectMapper;
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
        if (!properties.isAnswerGateEnabled()) {
            boolean hasEvidence = evidence != null && !evidence.isEmpty();
            return hasEvidence ? GateDecision.allow() : new GateDecision(false, false, "insufficient_evidence");
        }
        if (evidence == null || evidence.isEmpty()) {
            return new GateDecision(false, false, "insufficient_evidence");
        }
        if (properties.isAnswerGateBlockOnUnknownIntent()
                && intent != null
                && "unknown".equalsIgnoreCase(intent.intent())) {
            return new GateDecision(false, false, "unknown_intent");
        }
        boolean catalogGateSatisfied = false;
        if (need != null) {
            if (!catalogRegistry.satisfiesGate(need, evidence)) {
                boolean certificateNeedFallback = need.hasFacet()
                        && "certificate".equalsIgnoreCase(need.facet())
                        && hasFlexibleCertificateEvidence(intent == null ? "" : intent.queryType(), evidence);
                if (!certificateNeedFallback
                        && !allowByLlmAssist(question, intent, need, evidence, "need_evidence_mismatch")) {
                    return new GateDecision(false, false, "need_evidence_mismatch");
                }
            }
            catalogGateSatisfied = catalogRegistry.hasGateRule(need);
        }
        if (!catalogGateSatisfied && intent != null) {
            var requiredSchemas = routingPolicy.requiredEvidenceSchemaIds(intent.queryType());
            if (!requiredSchemas.isEmpty() && !hasEvidenceForSchemas(evidence, requiredSchemas)) {
                if (!hasFlexibleCertificateEvidence(intent.queryType(), evidence)
                        && !allowByLlmAssist(question, intent, need, evidence, "query_type_evidence_mismatch")) {
                    return new GateDecision(false, false, "query_type_evidence_mismatch");
                }
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

    private boolean hasFlexibleCertificateEvidence(String queryType, List<ContextChunk> evidence) {
        if (queryType == null || evidence == null || evidence.isEmpty()) {
            return false;
        }
        String qt = queryType.trim().toLowerCase(Locale.ROOT);
        if (!"company_certificate".equals(qt) && !"person_certificate_list".equals(qt)) {
            return false;
        }
        for (ContextChunk chunk : evidence) {
            if (chunk == null || chunk.snippet() == null || chunk.snippet().isBlank()) {
                continue;
            }
            if ("person_certificate_v1".equalsIgnoreCase(chunk.evidenceSchema())) {
                return true;
            }
            String source = chunk.source() == null ? "" : chunk.source().toLowerCase(Locale.ROOT);
            boolean sourceHint = source.contains("document-chunk-db")
                    || source.contains("enterprise_mysql_compiled")
                    || source.startsWith("mysql-structured-")
                    || source.contains("neo4j-certificate-instance");
            if (sourceHint && chunk.snippet() != null && !chunk.snippet().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean allowByLlmAssist(
            String question,
            IntentDecision intent,
            InformationNeed need,
            List<ContextChunk> evidence,
            String reason
    ) {
        if (question == null || question.isBlank() || !hasApiKey() || !properties.isIntentLlmEnabled()) {
            return false;
        }
        try {
            String systemPrompt = """
                    你是证据闸门判定器。目标：判断“当前证据是否足够支持回答用户问题”，不能编造。
                    输出必须是单行 JSON：
                    {"allow":true|false,"confidence":0.0-1.0,"reason":"<=40字"}
                    规则：
                    1) 若证据已包含问题要求的关键字段（例如证照类型/状态/有效期），可 allow=true。
                    2) 若证据只有主体名单但无问题要求字段，allow=false。
                    3) 宁可保守，不可放过明显证据不足。
                    """;
            String userPrompt = buildGateJudgePrompt(question, intent, need, reason, evidence);
            String raw = miniMaxClient.completeChat(systemPrompt, userPrompt);
            JsonNode node = objectMapper.readTree(extractJson(raw));
            boolean allow = node.path("allow").asBoolean(false);
            double confidence = node.path("confidence").asDouble(0.0);
            return allow && confidence >= 0.7;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String buildGateJudgePrompt(
            String question,
            IntentDecision intent,
            InformationNeed need,
            String reason,
            List<ContextChunk> evidence
    ) {
        String queryType = intent == null ? "" : safe(intent.queryType());
        String facet = need == null ? "" : safe(need.facet());
        StringBuilder sb = new StringBuilder();
        sb.append("question=").append(question).append('\n');
        sb.append("queryType=").append(queryType).append('\n');
        sb.append("facet=").append(facet).append('\n');
        sb.append("gateRejectReason=").append(reason).append('\n');
        sb.append("evidence=\n");
        List<String> lines = new ArrayList<>();
        int max = Math.min(evidence == null ? 0 : evidence.size(), 8);
        for (int i = 0; i < max; i++) {
            ContextChunk c = evidence.get(i);
            if (c == null) {
                continue;
            }
            String snippet = c.snippet() == null ? "" : c.snippet();
            if (snippet.length() > 180) {
                snippet = snippet.substring(0, 180) + "...";
            }
            lines.add("- source=" + safe(c.source()) + ", schema=" + safe(c.evidenceSchema()) + ", snippet=" + snippet);
        }
        if (lines.isEmpty()) {
            sb.append("- none");
        } else {
            sb.append(String.join("\n", lines));
        }
        return sb.toString();
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        int l = raw.indexOf('{');
        int r = raw.lastIndexOf('}');
        if (l >= 0 && r > l) {
            return raw.substring(l, r + 1);
        }
        return "{}";
    }

    private boolean hasApiKey() {
        String key = properties.getApiKey();
        return key != null && !key.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasEvidenceForSchemas(List<ContextChunk> evidence, java.util.Set<String> schemaIds) {
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

    private boolean hasEvidenceForSchema(List<ContextChunk> evidence, String schemaId) {
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
