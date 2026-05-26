package com.qa.demo.qa.answer;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
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

    public QaAnswerGateService(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public GateDecision evaluate(IntentDecision intent, List<ContextChunk> evidence) {
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
        if (intent != null && intent.isPersonCertificateListQuery()) {
            boolean hasPersonCert = evidence.stream().anyMatch(c ->
                    c != null
                            && "mysql-person-certificate".equals(c.source())
                            && c.snippet() != null
                            && c.snippet().contains("证照类型="));
            if (!hasPersonCert) {
                return new GateDecision(false, false, "person_certificate_no_evidence");
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
}
