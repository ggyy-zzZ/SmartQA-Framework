package com.qa.demo.qa.orchestration;

import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.answer.QaAnswerFallbackService;
import com.qa.demo.qa.answer.QaAnswerGateService;
import com.qa.demo.qa.alignment.EvidenceAlignmentService;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.QaScopes;
import com.qa.demo.qa.embedding.TextEmbeddingService;
import com.qa.demo.qa.intent.CompanyClarificationAdvisor;
import com.qa.demo.qa.intent.IntentRouterService;
import com.qa.demo.qa.intent.IntentRoutingOutcome;
import com.qa.demo.qa.intent.PersonClarificationAdvisor;
import com.qa.demo.qa.intent.PersonNameResolution;
import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.learning.ChatLearningCommandParser;
import com.qa.demo.qa.learning.LearningResponseBuilder;
import com.qa.demo.qa.response.QaConversationService;
import com.qa.demo.qa.response.QaLogService;
import com.qa.demo.qa.retrieval.GraphContextService;
import com.qa.demo.qa.retrieval.QaRetrievalOrchestrator;
import com.qa.demo.qa.retrieval.QaRetrievalPipeline;
import com.qa.demo.qa.sedimentation.SedimentationQueueService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 同步/流式共用的问答主流程，避免 {@link QaAskOrchestrator} 双份维护。
 */
@Service
public class QaAskFlowService {

    private static final Pattern DIGIT_ONLY = Pattern.compile("^\\d{1,2}$");

    private final IntentRouterService intentRouterService;
    private final ActiveLearningService activeLearningService;
    private final GraphContextService graphContextService;
    private final MiniMaxClient miniMaxClient;
    private final QaAssistantProperties properties;
    private final QaLogService qaLogService;
    private final QaRetrievalOrchestrator retrievalOrchestrator;
    private final QaConversationService conversationService;
    private final QaRetrievalPipeline retrievalPipeline;
    private final ChatLearningCommandParser learningCommandParser;
    private final CompanyClarificationAdvisor companyClarificationAdvisor;
    private final PersonClarificationAdvisor personClarificationAdvisor;
    private final QaAnswerFallbackService answerFallbackService;
    private final LearningResponseBuilder learningResponseBuilder;
    private final EvidenceAlignmentService evidenceAlignmentService;
    private final SedimentationQueueService sedimentationQueueService;
    private final QaAnswerGateService answerGateService;
    private final TextEmbeddingService textEmbeddingService;
    private final EnterpriseCanonicalFactsRegistry canonicalFactsRegistry;

    public QaAskFlowService(
            IntentRouterService intentRouterService,
            ActiveLearningService activeLearningService,
            GraphContextService graphContextService,
            MiniMaxClient miniMaxClient,
            QaAssistantProperties properties,
            QaLogService qaLogService,
            QaRetrievalOrchestrator retrievalOrchestrator,
            QaConversationService conversationService,
            QaRetrievalPipeline retrievalPipeline,
            ChatLearningCommandParser learningCommandParser,
            CompanyClarificationAdvisor companyClarificationAdvisor,
            PersonClarificationAdvisor personClarificationAdvisor,
            QaAnswerFallbackService answerFallbackService,
            LearningResponseBuilder learningResponseBuilder,
            EvidenceAlignmentService evidenceAlignmentService,
            SedimentationQueueService sedimentationQueueService,
            QaAnswerGateService answerGateService,
            TextEmbeddingService textEmbeddingService,
            EnterpriseCanonicalFactsRegistry canonicalFactsRegistry
    ) {
        this.intentRouterService = intentRouterService;
        this.activeLearningService = activeLearningService;
        this.graphContextService = graphContextService;
        this.miniMaxClient = miniMaxClient;
        this.properties = properties;
        this.qaLogService = qaLogService;
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.conversationService = conversationService;
        this.retrievalPipeline = retrievalPipeline;
        this.learningCommandParser = learningCommandParser;
        this.companyClarificationAdvisor = companyClarificationAdvisor;
        this.personClarificationAdvisor = personClarificationAdvisor;
        this.answerFallbackService = answerFallbackService;
        this.learningResponseBuilder = learningResponseBuilder;
        this.evidenceAlignmentService = evidenceAlignmentService;
        this.sedimentationQueueService = sedimentationQueueService;
        this.answerGateService = answerGateService;
        this.textEmbeddingService = textEmbeddingService;
        this.canonicalFactsRegistry = canonicalFactsRegistry;
    }

    public Map<String, Object> run(
            String question,
            String scope,
            String conversationId,
            Boolean followUpFlag,
            QaAskProgress progress
    ) throws IOException {
        String turnId = qaLogService.nextTurnId();
        String convId = conversationService.resolveConversationId(conversationId);
        List<QaConversationService.ConversationTurn> prior = conversationService.recentTurns(convId, 4);
        boolean followUpApplied = conversationService.resolveFollowUp(followUpFlag, question, prior);

        question = applyPersonFollowUpQuestion(question, prior);

        String sessionRetrievalSeed = conversationService.buildRetrievalQuestion(question, prior, followUpApplied);
        boolean explicitCompanyHint = graphContextService.hasExplicitCompanyHint(question)
                || graphContextService.hasExplicitCompanyHint(sessionRetrievalSeed);
        boolean skipCompanyClarify = followUpApplied && conversationService.priorHasCompanyFocus(convId);
        String modelContextBlock = conversationService.buildModelContextBlock(prior, followUpApplied, question);

        var learningOpt = learningCommandParser.parse(question, scope);
        if (learningOpt.isPresent()) {
            var learningCommand = learningOpt.get();
            progress.onThinking("learning", "识别到主动学习意图，正在写入知识库。", null);
            ActiveLearningService.LearningResult learningResult = activeLearningService.learn(
                    learningCommand.content(),
                    "chat_intent",
                    "chat_message",
                    learningCommand.triggerWord(),
                    learningCommand.scope()
            );
            Map<String, Object> learningResponse = learningResponseBuilder.buildLearningResponse(
                    turnId, question, learningResult, learningCommand.scope());
            learningResponse.put("conversationId", convId);
            learningResponse.put("followUpApplied", false);
            qaLogService.appendAskEvent(learningResponse);
            return learningResponse;
        }

        List<ContextChunk> canonicalHits = mergeBootstrapKnowledge(
                canonicalFactsRegistry.retrieve(sessionRetrievalSeed, scope),
                canonicalFactsRegistry.retrieve(question, scope)
        );
        List<ContextChunk> activeLearningHits =
                retrievalPipeline.safeActiveLearningRetrieve(sessionRetrievalSeed, scope);
        List<ContextChunk> learnedFirst = mergeBootstrapKnowledge(canonicalHits, activeLearningHits);
        QaRetrievalOrchestrator.RetrievalPlan retrievalPlan =
                retrievalOrchestrator.prepareRetrievalQuestion(sessionRetrievalSeed, learnedFirst);
        String retrievalQuestion = retrievalPlan.retrievalQuestion();

        progress.onThinking(
                "decompose",
                "问题理解",
                QaThinkingDigest.decompose(
                        question, retrievalQuestion, followUpApplied, modelContextBlock, retrievalPlan
                )
        );
        progress.onThinking(
                "recall",
                "常识与记忆",
                QaThinkingDigest.bootstrapRecall(canonicalHits, activeLearningHits)
        );

        if (!explicitCompanyHint
                && !skipCompanyClarify
                && companyClarificationAdvisor.needsClarification(question, scope)
                && !retrievalPipeline.preferActiveLearning(question, explicitCompanyHint, learnedFirst)) {
            progress.onThinking("clarify", "公司指代不够具体，需要先确认对象。", null);
            return buildCompanyClarificationResponse(
                    turnId, question, scope, convId, followUpApplied, graphContextService.suggestCompanyCandidates(question, 5));
        }

        IntentRoutingOutcome routing = intentRouterService.decide(retrievalQuestion, explicitCompanyHint, learnedFirst);
        IntentDecision intentDecision = conversationService.enrichIntentForFollowUp(
                routing.decision(), prior, followUpApplied, question);
        progress.onThinking(
                "route",
                "意图与实体",
                QaThinkingDigest.route(intentDecision, intentDecision.reason())
        );

        if (routing.needsPersonClarification()) {
            progress.onThinking("clarify", "人物指称存在歧义，需要补充全名。", null);
            return buildPersonClarificationResponse(
                    turnId, question, scope, convId, followUpApplied, intentDecision, routing.personResolution());
        }

        QaRetrievalPipeline.RetrievalResult retrievalResult = retrieve(scope, question, retrievalQuestion, learnedFirst, intentDecision, explicitCompanyHint);
        String retrievalSource = retrievalResult.retrievalSource();
        List<ContextChunk> evidence = new ArrayList<>(retrievalResult.evidence());
        if (QaScopes.ENTERPRISE.equals(scope) && retrievalPlan.appliedLearningRewrite()) {
            evidence.removeIf(c ->
                    "mysql-employee-precheck".equals(c.source())
                            && "employee_not_found".equals(c.anchorId())
            );
        }

        QaAnswerGateService.GateDecision gate = answerGateService.evaluate(intentDecision, evidence);
        boolean canAnswer = gate.canAnswer();
        boolean allowGenerate = gate.allowGenerate();
        boolean unknownIntent = "unknown".equalsIgnoreCase(intentDecision.intent());
        String route = resolveInitialRoute(unknownIntent, allowGenerate, gate.rejectReason(), retrievalSource);
        double confidence = allowGenerate ? answerFallbackService.calcConfidence(evidence) : 0.20;
        String answer;
        boolean degraded = false;
        String fallbackReason = "";

        progress.onThinking(
                "retrieve",
                "知识检索",
                QaThinkingDigest.retrieval(retrievalSource, evidence)
        );
        progress.onThinking(
                "gate",
                "证据质检",
                QaThinkingDigest.gate(evidence.size(), allowGenerate, canAnswer, gate.rejectReason())
        );

        if (unknownIntent && evidence.isEmpty()) {
            progress.onThinking("decision", "超出知识库覆盖范围。", null);
            answer = KnowledgeAssistantPrompts.unknownCoverageUserMessage();
            route = "reject_unknown_intent";
        } else if (!allowGenerate) {
            if (personClarificationAdvisor.needsClarification(intentDecision, evidence, question)) {
                progress.onThinking("clarify", "未能锁定具体人员，引导补充全名。", null);
                return buildPersonClarificationResponse(
                        turnId, question, scope, convId, followUpApplied, intentDecision, routing.personResolution());
            }
            progress.onThinking("decision", "证据不足，返回补充建议。", null);
            answer = KnowledgeAssistantPrompts.insufficientEvidenceGeneralHint();
            route = "reject_gate_" + nullToEmpty(gate.rejectReason());
        } else {
            progress.onThinking("generate", "正在依据证据组织回答…", null);
            try {
                answer = miniMaxClient.askWithEvidence(modelContextBlock, question, evidence, intentDecision);
                route = retrievalSource + "_generate_llm";
            } catch (Exception ex) {
                degraded = true;
                fallbackReason = answerFallbackService.sanitizeError(ex.getMessage());
                progress.onThinking("degrade", "模型生成失败，已切换保底回答。", null);
                answer = answerFallbackService.buildFallbackAnswer(question, evidence);
                route = retrievalSource + "_fallback_template";
            }
        }

        Map<String, Object> response = assembleResponse(
                turnId, question, scope, convId, followUpApplied, intentDecision, evidence,
                answer, canAnswer, confidence, route, retrievalSource, retrievalResult, gate, degraded, fallbackReason, unknownIntent
        );
        qaLogService.appendAskEvent(response);
        if (Boolean.TRUE.equals(response.get("knowledgeDepositTriggered"))) {
            enqueueDeposit(turnId, question, intentDecision.intent(), retrievalSource,
                    unknownIntent ? "unknown_intent" : nullToEmpty(gate.rejectReason(), "insufficient_evidence"), evidence);
        }
        String focusPerson = intentDecision.hasPersonFocus() ? intentDecision.personName().trim() : "";
        conversationService.appendTurn(
                convId, scope, turnId, question, answer, evidence, List.of(), focusPerson,
                intentDecision.intent(), intentDecision.queryType());
        return response;
    }

    private QaRetrievalPipeline.RetrievalResult retrieve(
            String scope,
            String question,
            String retrievalQuestion,
            List<ContextChunk> learnedFirst,
            IntentDecision intentDecision,
            boolean explicitCompanyHint
    ) throws IOException {
        if (QaScopes.PERSONAL.equals(scope)) {
            return learnedFirst.isEmpty()
                    ? new QaRetrievalPipeline.RetrievalResult("personal_scope_no_memory", List.of())
                    : new QaRetrievalPipeline.RetrievalResult("active_learning_personal", learnedFirst);
        }
        if (QaScopes.ENTERPRISE.equals(scope) && properties.isUnifiedRetrievalEnabled()) {
            return retrievalPipeline.retrieveUnifiedEnterprise(retrievalQuestion, learnedFirst, intentDecision);
        }
        if (retrievalPipeline.preferActiveLearning(question, explicitCompanyHint, learnedFirst)) {
            return new QaRetrievalPipeline.RetrievalResult("active_learning_priority", learnedFirst);
        }
        QaRetrievalPipeline.RetrievalResult result = retrievalPipeline.retrieveByIntent(intentDecision, retrievalQuestion);
        if (QaScopes.ENTERPRISE.equals(scope)) {
            result = retrievalPipeline.mergeEnterpriseActiveLearning(result, learnedFirst, explicitCompanyHint);
        }
        return result;
    }

    private String applyPersonFollowUpQuestion(String question, List<QaConversationService.ConversationTurn> prior) {
        Optional<String> pick = conversationService.resolvePersonFollowUpSelection(question, prior);
        if (pick.isEmpty() || prior.isEmpty()) {
            return question;
        }
        if (!DIGIT_ONLY.matcher(question.trim()).matches() && question.trim().length() > 8) {
            return question;
        }
        QaConversationService.ConversationTurn last = prior.get(prior.size() - 1);
        String person = pick.get();
        String priorQ = last.question() == null ? "" : last.question().strip();
        if (priorQ.isBlank()) {
            return person;
        }
        return person + " " + priorQ;
    }

    private Map<String, Object> buildCompanyClarificationResponse(
            String turnId,
            String question,
            String scope,
            String convId,
            boolean followUpApplied,
            List<CompanyCandidate> candidates
    ) {
        String clarifyAnswer = companyClarificationAdvisor.buildClarificationAnswer(candidates);
        Map<String, Object> response = new HashMap<>();
        response.put("turnId", turnId);
        response.put("question", question);
        response.put("answer", clarifyAnswer);
        response.put("canAnswer", false);
        response.put("confidence", 0.10);
        response.put("route", "ask_company_clarification");
        response.put("retrievalSource", "none");
        response.put("intent", "clarification");
        response.put("intentConfidence", 1.0);
        response.put("routeReason", "missing_company_subject");
        response.put("evidence", List.of());
        response.put("degraded", false);
        response.put("clarificationRequired", true);
        response.put("companyCandidates", candidates);
        response.put("docsDir", properties.getDocsDir());
        response.put("model", properties.getModel());
        response.put("timestamp", OffsetDateTime.now().toString());
        response.put("scope", scope);
        response.put("conversationId", convId);
        response.put("followUpApplied", followUpApplied);
        response.put("knowledgeDepositTriggered", true);
        putEvidenceAlignment(response, question, clarifyAnswer, List.of(), false);
        qaLogService.appendAskEvent(response);
        enqueueDeposit(turnId, question, "clarification", "none", "needs_company_clarification", List.of());
        conversationService.appendTurn(convId, scope, turnId, question, clarifyAnswer, List.of());
        return response;
    }

    public Map<String, Object> buildPersonClarificationResponse(
            String turnId,
            String question,
            String scope,
            String convId,
            boolean followUpApplied,
            IntentDecision intentDecision,
            PersonNameResolution resolution
    ) {
        String clarifyAnswer = personClarificationAdvisor.buildClarificationAnswer(intentDecision, question, resolution);
        List<String> personCandidates = resolution != null && resolution.needsClarification()
                ? resolution.candidates()
                : List.of();
        Map<String, Object> response = new HashMap<>();
        response.put("turnId", turnId);
        response.put("question", question);
        response.put("answer", clarifyAnswer);
        response.put("canAnswer", false);
        response.put("confidence", 0.12);
        response.put("route", "ask_person_clarification");
        response.put("retrievalSource", "none");
        response.put("intent", "clarification");
        response.put("intentConfidence", intentDecision.confidence());
        response.put("routeReason", "needs_person_clarification");
        response.put("evidence", List.of());
        response.put("degraded", false);
        response.put("clarificationRequired", true);
        response.put("personClarificationRequired", true);
        if (!personCandidates.isEmpty()) {
            response.put("personCandidates", personCandidates);
        }
        putIntentMetadata(response, intentDecision);
        response.put("docsDir", properties.getDocsDir());
        response.put("model", properties.getModel());
        response.put("timestamp", OffsetDateTime.now().toString());
        response.put("scope", scope);
        response.put("conversationId", convId);
        response.put("followUpApplied", followUpApplied);
        response.put("knowledgeDepositTriggered", true);
        putEvidenceAlignment(response, question, clarifyAnswer, List.of(), false);
        qaLogService.appendAskEvent(response);
        enqueueDeposit(turnId, question, intentDecision.intent(), "none", "needs_person_clarification", List.of());
        conversationService.appendTurn(convId, scope, turnId, question, clarifyAnswer, List.of(), personCandidates);
        return response;
    }

    private Map<String, Object> assembleResponse(
            String turnId,
            String question,
            String scope,
            String convId,
            boolean followUpApplied,
            IntentDecision intentDecision,
            List<ContextChunk> evidence,
            String answer,
            boolean canAnswer,
            double confidence,
            String route,
            String retrievalSource,
            QaRetrievalPipeline.RetrievalResult retrievalResult,
            QaAnswerGateService.GateDecision gate,
            boolean degraded,
            String fallbackReason,
            boolean unknownIntent
    ) {
        Map<String, Object> response = new HashMap<>();
        response.put("turnId", turnId);
        response.put("question", question);
        response.put("answer", answer);
        response.put("canAnswer", canAnswer);
        response.put("confidence", confidence);
        response.put("route", route);
        response.put("retrievalSource", retrievalSource);
        putIntentMetadata(response, intentDecision);
        response.put("evidence", evidence);
        response.put("degraded", degraded);
        response.put("docsDir", properties.getDocsDir());
        response.put("model", properties.getModel());
        response.put("timestamp", OffsetDateTime.now().toString());
        response.put("scope", scope);
        response.put("conversationId", convId);
        response.put("followUpApplied", followUpApplied);
        response.put("embeddingProvider", textEmbeddingService.activeProvider());
        response.put("unifiedRetrieval", properties.isUnifiedRetrievalEnabled());
        response.put("rerankProvider", retrievalSource.contains("rerank")
                ? retrievalSource.replace("unified_rerank_", "")
                : "unified_graph_person_role".equals(retrievalSource) ? "graph_person_role_skip" : "none");
        if (gate.rejectReason() != null) {
            response.put("answerGateRejectReason", gate.rejectReason());
        }
        boolean shouldDeposit = unknownIntent || !canAnswer;
        response.put("knowledgeDepositTriggered", shouldDeposit);
        if (degraded) {
            response.put("fallbackReason", fallbackReason);
        }
        putEvidenceAlignment(response, question, answer, evidence, canAnswer);
        return response;
    }

    private void enqueueDeposit(
            String turnId,
            String question,
            String intent,
            String retrievalSource,
            String depositReason,
            List<ContextChunk> evidence
    ) {
        Map<String, Object> candidate = qaLogService.buildKnowledgeCandidateEvent(
                turnId, question, intent, retrievalSource, depositReason, evidence);
        qaLogService.appendKnowledgeCandidate(candidate);
        sedimentationQueueService.enqueuePending(
                turnId, question, intent, retrievalSource, depositReason, evidence);
    }

    private static String resolveInitialRoute(
            boolean unknownIntent,
            boolean allowGenerate,
            String gateRejectReason,
            String retrievalSource
    ) {
        if (unknownIntent) {
            return "reject_unknown_intent";
        }
        if (!allowGenerate) {
            return "reject_gate_" + nullToEmpty(gateRejectReason);
        }
        return retrievalSource + "_retrieval_generate";
    }

    private static String nullToEmpty(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String nullToEmpty(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private void putIntentMetadata(Map<String, Object> response, IntentDecision intentDecision) {
        response.put("intent", intentDecision.intent());
        response.put("intentConfidence", intentDecision.confidence());
        response.put("routeReason", intentDecision.reason());
        if (intentDecision.queryType() != null && !intentDecision.queryType().isBlank()) {
            response.put("queryType", intentDecision.queryType());
        }
        if (intentDecision.hasPersonFocus()) {
            response.put("personName", intentDecision.personName());
        }
        if (intentDecision.roleFocus() != null && !intentDecision.roleFocus().isBlank()
                && !"any".equalsIgnoreCase(intentDecision.roleFocus())) {
            response.put("roleFocus", intentDecision.roleFocus());
        }
        if (intentDecision.hasCompanyHints()) {
            response.put("companyHints", intentDecision.companyHints());
        }
    }

    private void putEvidenceAlignment(
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

    private static List<ContextChunk> mergeBootstrapKnowledge(
            List<ContextChunk> canonical,
            List<ContextChunk> activeLearning
    ) {
        List<ContextChunk> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        appendUniqueChunks(merged, seen, canonical);
        appendUniqueChunks(merged, seen, activeLearning);
        return merged;
    }

    private static void appendUniqueChunks(
            List<ContextChunk> target,
            java.util.Set<String> seen,
            List<ContextChunk> chunks
    ) {
        if (chunks == null) {
            return;
        }
        for (ContextChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String key = chunk.source() + "|" + chunk.anchorId() + "|" + chunk.snippet();
            if (seen.add(key)) {
                target.add(chunk);
            }
        }
    }
}
