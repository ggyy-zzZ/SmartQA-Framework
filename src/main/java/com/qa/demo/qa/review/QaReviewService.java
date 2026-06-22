package com.qa.demo.qa.review;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.answer.EvidenceFieldCoverageAdvisor;
import com.qa.demo.qa.answer.QaAnswerGateService;
import com.qa.demo.qa.alignment.EvidenceAlignmentService;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.intent.PersonClarificationAdvisor;
import com.qa.demo.qa.intent.PersonNameResolution;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reviewer 层：证据闸门、字段覆盖、澄清与生成后对齐检查。
 */
@Service
public class QaReviewService {

    private final QaAnswerGateService answerGateService;
    private final EvidenceFieldCoverageAdvisor fieldCoverageAdvisor;
    private final PersonClarificationAdvisor personClarificationAdvisor;
    private final EvidenceAlignmentService evidenceAlignmentService;

    public QaReviewService(
            QaAnswerGateService answerGateService,
            EvidenceFieldCoverageAdvisor fieldCoverageAdvisor,
            PersonClarificationAdvisor personClarificationAdvisor,
            EvidenceAlignmentService evidenceAlignmentService
    ) {
        this.answerGateService = answerGateService;
        this.fieldCoverageAdvisor = fieldCoverageAdvisor;
        this.personClarificationAdvisor = personClarificationAdvisor;
        this.evidenceAlignmentService = evidenceAlignmentService;
    }

    public enum DecisionKind {
        GENERATE,
        REJECT_UNKNOWN,
        REJECT_GATE,
        CLARIFY_FIELD,
        CLARIFY_PERSON
    }

    public record ReviewDecision(
            DecisionKind kind,
            QaAnswerGateService.GateDecision gate,
            boolean canAnswer,
            boolean allowGenerate,
            String route,
            String answer,
            Optional<EvidenceFieldCoverageAdvisor.FieldCoverageGap> fieldGap
    ) {
    }

    public QaAnswerGateService.GateDecision evaluateGate(
            String question,
            IntentDecision intentDecision,
            InformationNeed informationNeed,
            List<ContextChunk> evidence,
            String retrievalSource
    ) {
        return answerGateService.evaluate(question, intentDecision, informationNeed, evidence, retrievalSource);
    }

    public ReviewDecision decideBeforeGeneration(
            String question,
            IntentDecision intentDecision,
            InformationNeed informationNeed,
            List<ContextChunk> evidence,
            QaAnswerGateService.GateDecision gate,
            boolean unknownIntent,
            String retrievalSource,
            PersonNameResolution personResolution
    ) {
        boolean allowGenerate = gate.allowGenerate();
        boolean canAnswer = gate.canAnswer();

        Optional<EvidenceFieldCoverageAdvisor.FieldCoverageGap> fieldGap =
                fieldCoverageAdvisor.detectFilterFieldGap(question, evidence, informationNeed);
        if (unknownIntent && evidence.isEmpty()) {
            return new ReviewDecision(
                    DecisionKind.REJECT_UNKNOWN,
                    gate,
                    false,
                    false,
                    "reject_unknown_intent",
                    KnowledgeAssistantPrompts.unknownCoverageUserMessage(),
                    fieldGap
            );
        }
        if (fieldGap.isPresent()) {
            return new ReviewDecision(
                    DecisionKind.CLARIFY_FIELD,
                    gate,
                    false,
                    false,
                    "clarify_field_gap_" + fieldGap.get().ruleId(),
                    fieldCoverageAdvisor.buildClarification(fieldGap.get()),
                    fieldGap
            );
        }
        if (!allowGenerate) {
            if (personClarificationAdvisor.needsClarification(intentDecision, informationNeed, evidence, question)) {
                return new ReviewDecision(
                        DecisionKind.CLARIFY_PERSON,
                        gate,
                        canAnswer,
                        false,
                        "ask_person_clarification",
                        "",
                        fieldGap
                );
            }
            return new ReviewDecision(
                    DecisionKind.REJECT_GATE,
                    gate,
                    canAnswer,
                    false,
                    "reject_gate_" + nullToEmpty(gate.rejectReason()),
                    KnowledgeAssistantPrompts.insufficientEvidenceGeneralHint(),
                    fieldGap
            );
        }
        return new ReviewDecision(
                DecisionKind.GENERATE,
                gate,
                canAnswer,
                true,
                retrievalSource,
                "",
                fieldGap
        );
    }

    public void attachEvidenceAlignment(
            Map<String, Object> response,
            String question,
            String answer,
            List<ContextChunk> evidence,
            boolean canAnswer
    ) {
        EvidenceAlignmentService.AlignmentInsight insight =
                evidenceAlignmentService.analyze(question, answer, evidence, canAnswer);
        response.put("evidenceAlignment", Map.of(
                "keywordOverlap", insight.keywordOverlap(),
                "lowOverlap", insight.lowOverlap(),
                "warnings", insight.warnings()
        ));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
