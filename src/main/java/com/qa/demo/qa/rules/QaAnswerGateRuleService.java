package com.qa.demo.qa.rules;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.intent.QueryTypeRoutingPolicy;
import com.qa.demo.qa.rules.fact.EvidenceFact;
import com.qa.demo.qa.rules.fact.GateFact;
import com.qa.demo.qa.rules.fact.QaContextFact;
import org.kie.api.runtime.StatelessKieSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 答案闸门规则服务（P0-S3）。
 * <p>
 * 把 {@code QaAnswerGateService.hasFlexibleCertificateEvidence} 与
 * {@code hasEvidenceForSchemas} 的判定逻辑移到 DRL 规则。
 * <p>
 * 灰度开关 {@code qa.rule.answer-gate.enabled}=true 时本服务生效；默认 OFF，
 * 旧 Java 逻辑（{@link com.qa.demo.qa.answer.QaAnswerGateService}）继续工作。
 */
@Service
@ConditionalOnProperty(name = "qa.rule.answer-gate.enabled", havingValue = "true")
public class QaAnswerGateRuleService {

    private final StatelessKieSession session;
    private final QueryTypeRoutingPolicy routingPolicy;
    private final boolean fallbackClassic;

    public QaAnswerGateRuleService(
            @Qualifier("qaAnswerGateStateless") StatelessKieSession session,
            QueryTypeRoutingPolicy routingPolicy,
            @Value("${qa.rule.fallback.classic:false}") boolean fallbackClassic
    ) {
        this.session = session;
        this.routingPolicy = routingPolicy;
        this.fallbackClassic = fallbackClassic;
    }

    /**
     * DRL 替代 {@code QaAnswerGateService.hasFlexibleCertificateEvidence}。
     * <p>
     * 返回 true 表示存在柔性的证照证据，可放宽闸门通过。
     */
    public boolean hasFlexibleCertificateEvidence(IntentDecision intent, java.util.List<ContextChunk> evidence) {
        if (fallbackClassic || intent == null || evidence == null || evidence.isEmpty()) {
            return false;
        }
        QaContextFact ctx = new QaContextFact();
        GateFact gate = new GateFact();
        gate.setQueryType(intent.queryType());
        gate.setQueryTypeEnum(QaQueryType.from(intent.queryType()));
        for (ContextChunk c : evidence) {
            if (c == null) continue;
            if (c.evidenceSchema() != null && !c.evidenceSchema().isBlank()) {
                gate.getSchemaIds().add(c.evidenceSchema());
            }
            if (c.source() != null) {
                gate.getSourcePrefixes().add(c.source().toLowerCase(Locale.ROOT));
            }
        }
        for (ContextChunk c : evidence) {
            session.execute(new Object[]{ctx, gate, EvidenceFact.fromChunk(c, gate.getQueryTypeEnum())});
        }
        return gate.isAllowGenerate() || gate.hasFlexibleCertSource();
    }

    /**
     * DRL 替代 {@code QaAnswerGateService.hasEvidenceForSchemas}。
     * <p>
     * 返回 true 表示 required schemaIds 至少有一个被命中。
     */
    public boolean hasEvidenceForSchemas(IntentDecision intent, java.util.List<ContextChunk> evidence) {
        if (fallbackClassic || intent == null) {
            return false;
        }
        Set<String> required = routingPolicy.requiredEvidenceSchemaIds(intent.queryType());
        if (required == null || required.isEmpty()) {
            return true;
        }
        QaContextFact ctx = new QaContextFact();
        GateFact gate = new GateFact();
        gate.setQueryType(intent.queryType());
        gate.setQueryTypeEnum(QaQueryType.from(intent.queryType()));
        for (ContextChunk c : evidence) {
            if (c != null && c.evidenceSchema() != null) {
                gate.getSchemaIds().add(c.evidenceSchema());
            }
        }
        for (ContextChunk c : evidence) {
            session.execute(new Object[]{ctx, gate, EvidenceFact.fromChunk(c, gate.getQueryTypeEnum())});
        }
        for (String schemaId : required) {
            if (gate.getSchemaIds().contains(schemaId)) {
                return true;
            }
        }
        return false;
    }

    public boolean needsLlmAssist(IntentDecision intent, InformationNeed need,
                                  java.util.List<ContextChunk> evidence, String reason) {
        if (fallbackClassic || intent == null) {
            return false;
        }
        QaContextFact ctx = new QaContextFact();
        GateFact gate = new GateFact();
        gate.setQueryType(intent.queryType());
        gate.setQueryTypeEnum(QaQueryType.from(intent.queryType()));
        gate.setFacet(need == null ? "" : need.facet());
        gate.setEvidenceCount(evidence == null ? 0 : evidence.size());
        for (ContextChunk c : evidence) {
            if (c != null) {
                gate.getSchemaIds().add(c.evidenceSchema() == null ? "" : c.evidenceSchema());
                if (c.source() != null) gate.getSourcePrefixes().add(c.source().toLowerCase(Locale.ROOT));
                gate.setMaxScore(Math.max(gate.getMaxScore(), c.score()));
            }
        }
        if (reason != null) {
            gate.addRejectionReason(reason);
        }
        session.execute(new Object[]{ctx, gate});
        return gate.isAllowByLlmAssist();
    }
}
