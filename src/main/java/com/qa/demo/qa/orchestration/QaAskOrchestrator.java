package com.qa.demo.qa.orchestration;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.answer.QaAnswerFallbackService;
import com.qa.demo.qa.answer.QaAnswerGateService;
import com.qa.demo.qa.embedding.TextEmbeddingService;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.QaScopes;
import com.qa.demo.qa.intent.CompanyClarificationAdvisor;
import com.qa.demo.qa.intent.IntentRouterService;
import com.qa.demo.qa.alignment.EvidenceAlignmentService;
import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.learning.ChatLearningCommandParser;
import com.qa.demo.qa.learning.LearningResponseBuilder;
import com.qa.demo.qa.retrieval.GraphContextService;
import com.qa.demo.qa.retrieval.QaRetrievalOrchestrator;
import com.qa.demo.qa.retrieval.QaRetrievalPipeline;
import com.qa.demo.qa.response.QaConversationService;
import com.qa.demo.qa.response.QaLogService;
import com.qa.demo.qa.sedimentation.SedimentationQueueService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 问答主流程编排：同步回答、SSE 流式、学习意图与检索-生成-落库闭环。
 */
@Service
public class QaAskOrchestrator {

    private final IntentRouterService intentRouterService;
    private final GraphContextService graphContextService;
    private final MiniMaxClient miniMaxClient;
    private final QaAssistantProperties properties;
    private final QaLogService qaLogService;
    private final ActiveLearningService activeLearningService;
    private final QaRetrievalOrchestrator retrievalOrchestrator;
    private final QaConversationService conversationService;
    private final QaRetrievalPipeline retrievalPipeline;
    private final ChatLearningCommandParser learningCommandParser;
    private final CompanyClarificationAdvisor clarificationAdvisor;
    private final QaAnswerFallbackService answerFallbackService;
    private final LearningResponseBuilder learningResponseBuilder;
    private final EvidenceAlignmentService evidenceAlignmentService;
    private final SedimentationQueueService sedimentationQueueService;
    private final QaSseStreamSupport sseStreamSupport;
    private final QaAnswerGateService answerGateService;
    private final TextEmbeddingService textEmbeddingService;

    public QaAskOrchestrator(
            IntentRouterService intentRouterService,
            GraphContextService graphContextService,
            MiniMaxClient miniMaxClient,
            QaAssistantProperties properties,
            QaLogService qaLogService,
            ActiveLearningService activeLearningService,
            QaRetrievalOrchestrator retrievalOrchestrator,
            QaConversationService conversationService,
            QaRetrievalPipeline retrievalPipeline,
            ChatLearningCommandParser learningCommandParser,
            CompanyClarificationAdvisor clarificationAdvisor,
            QaAnswerFallbackService answerFallbackService,
            LearningResponseBuilder learningResponseBuilder,
            EvidenceAlignmentService evidenceAlignmentService,
            SedimentationQueueService sedimentationQueueService,
            QaSseStreamSupport sseStreamSupport,
            QaAnswerGateService answerGateService,
            TextEmbeddingService textEmbeddingService
    ) {
        this.intentRouterService = intentRouterService;
        this.graphContextService = graphContextService;
        this.miniMaxClient = miniMaxClient;
        this.properties = properties;
        this.qaLogService = qaLogService;
        this.activeLearningService = activeLearningService;
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.conversationService = conversationService;
        this.retrievalPipeline = retrievalPipeline;
        this.learningCommandParser = learningCommandParser;
        this.clarificationAdvisor = clarificationAdvisor;
        this.answerFallbackService = answerFallbackService;
        this.learningResponseBuilder = learningResponseBuilder;
        this.evidenceAlignmentService = evidenceAlignmentService;
        this.sedimentationQueueService = sedimentationQueueService;
        this.sseStreamSupport = sseStreamSupport;
        this.answerGateService = answerGateService;
        this.textEmbeddingService = textEmbeddingService;
    }

    public Map<String, Object> buildAskResponse(String question, String scope, String conversationId, Boolean followUpFlag)
            throws IOException {
        String turnId = qaLogService.nextTurnId();
        String convId = conversationService.resolveConversationId(conversationId);
        List<QaConversationService.ConversationTurn> prior = conversationService.recentTurns(convId, 4);
        boolean followUpApplied = conversationService.resolveFollowUp(followUpFlag, question, prior);
        String sessionRetrievalSeed = conversationService.buildRetrievalQuestion(question, prior, followUpApplied);
        boolean explicitCompanyHint = graphContextService.hasExplicitCompanyHint(question)
                || graphContextService.hasExplicitCompanyHint(sessionRetrievalSeed);
        boolean skipCompanyClarify = followUpApplied && conversationService.priorHasCompanyFocus(convId);
        String modelContextBlock = conversationService.buildModelContextBlock(prior, followUpApplied);

        var learningOpt = learningCommandParser.parse(question, scope);
        if (learningOpt.isPresent()) {
            var learningCommand = learningOpt.get();
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

        List<ContextChunk> learnedFirst = retrievalPipeline.safeActiveLearningRetrieve(question, scope);
        QaRetrievalOrchestrator.RetrievalPlan retrievalPlan =
                retrievalOrchestrator.prepareRetrievalQuestion(sessionRetrievalSeed, learnedFirst);
        String retrievalQuestion = retrievalPlan.retrievalQuestion();

        if (!explicitCompanyHint
                && !skipCompanyClarify
                && clarificationAdvisor.needsClarification(question, scope)
                && !retrievalPipeline.preferActiveLearning(question, explicitCompanyHint, learnedFirst)) {
            List<CompanyCandidate> candidates = graphContextService.suggestCompanyCandidates(question, 5);
            String clarifyAnswer = clarificationAdvisor.buildClarificationAnswer(candidates);
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
            putEvidenceAlignment(response, question, clarifyAnswer, List.of(), false);
            qaLogService.appendAskEvent(response);
            return response;
        }

        IntentDecision intentDecision = intentRouterService.decide(retrievalQuestion, explicitCompanyHint);
        QaRetrievalPipeline.RetrievalResult retrievalResult;
        if (QaScopes.PERSONAL.equals(scope)) {
            retrievalResult = learnedFirst.isEmpty()
                    ? new QaRetrievalPipeline.RetrievalResult("personal_scope_no_memory", List.of())
                    : new QaRetrievalPipeline.RetrievalResult("active_learning_personal", learnedFirst);
        } else if (QaScopes.ENTERPRISE.equals(scope) && properties.isUnifiedRetrievalEnabled()) {
            retrievalResult = retrievalPipeline.retrieveUnifiedEnterprise(
                    retrievalQuestion,
                    learnedFirst,
                    intentDecision
            );
        } else if (retrievalPipeline.preferActiveLearning(question, explicitCompanyHint, learnedFirst)) {
            retrievalResult = new QaRetrievalPipeline.RetrievalResult("active_learning_priority", learnedFirst);
        } else {
            retrievalResult = retrievalPipeline.retrieveByIntent(intentDecision.intent(), retrievalQuestion);
            if (QaScopes.ENTERPRISE.equals(scope)) {
                retrievalResult = retrievalPipeline.mergeEnterpriseActiveLearning(retrievalResult, learnedFirst, explicitCompanyHint);
            }
        }
        String retrievalSource = retrievalResult.retrievalSource();
        List<ContextChunk> evidence = new ArrayList<>(retrievalResult.evidence());
        if (QaScopes.ENTERPRISE.equals(scope) && retrievalPlan.appliedLearningRewrite()) {
            evidence.removeIf(c ->
                    "mysql-employee-precheck".equals(c.source())
                            && "employee_not_found".equals(c.companyId())
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

        if (unknownIntent && evidence.isEmpty()) {
            answer = KnowledgeAssistantPrompts.unknownCoverageUserMessage();
            route = "reject_unknown_intent";
        } else if (!allowGenerate) {
            answer = KnowledgeAssistantPrompts.insufficientEvidenceGeneralHint();
            route = "reject_gate_" + nullToEmpty(gate.rejectReason());
        } else {
            try {
                answer = miniMaxClient.askWithEvidence(modelContextBlock, question, evidence);
                route = retrievalSource + "_generate_llm";
            } catch (Exception ex) {
                degraded = true;
                fallbackReason = answerFallbackService.sanitizeError(ex.getMessage());
                answer = answerFallbackService.buildFallbackAnswer(question, evidence);
                route = retrievalSource + "_fallback_template";
            }
        }

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
        response.put("rerankProvider", retrievalResult.retrievalSource().contains("rerank")
                ? retrievalResult.retrievalSource().replace("unified_rerank_", "")
                : "none");
        if (gate.rejectReason() != null) {
            response.put("answerGateRejectReason", gate.rejectReason());
        }
        boolean shouldDeposit = unknownIntent || !canAnswer;
        response.put("knowledgeDepositTriggered", shouldDeposit);
        if (degraded) {
            response.put("fallbackReason", fallbackReason);
        }
        putEvidenceAlignment(response, question, answer, evidence, canAnswer);
        qaLogService.appendAskEvent(response);
        if (shouldDeposit) {
            String depositReason = unknownIntent ? "unknown_intent" : nullToEmpty(gate.rejectReason(), "insufficient_evidence");
            Map<String, Object> candidate = qaLogService.buildKnowledgeCandidateEvent(
                    turnId,
                    question,
                    intentDecision.intent(),
                    retrievalSource,
                    depositReason,
                    evidence
            );
            qaLogService.appendKnowledgeCandidate(candidate);
            sedimentationQueueService.enqueuePending(
                    turnId,
                    question,
                    intentDecision.intent(),
                    retrievalSource,
                    depositReason,
                    evidence
            );
        }
        conversationService.appendTurn(convId, scope, turnId, question, answer, evidence);
        return response;
    }

    public SseEmitter startAskStream(String question, String scope, String conversationId, Boolean followUpFlag) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                sseStreamSupport.emitThinking(emitter, "start", "请求已接收，开始处理问题。");
                String turnId = qaLogService.nextTurnId();
                String convId = conversationService.resolveConversationId(conversationId);
                List<QaConversationService.ConversationTurn> prior = conversationService.recentTurns(convId, 4);
                boolean followUpApplied = conversationService.resolveFollowUp(followUpFlag, question, prior);
                String sessionRetrievalSeed = conversationService.buildRetrievalQuestion(question, prior, followUpApplied);
                boolean explicitCompanyHint = graphContextService.hasExplicitCompanyHint(question)
                        || graphContextService.hasExplicitCompanyHint(sessionRetrievalSeed);
                boolean skipCompanyClarify = followUpApplied && conversationService.priorHasCompanyFocus(convId);
                String modelContextBlock = conversationService.buildModelContextBlock(prior, followUpApplied);
                sseStreamSupport.emitThinking(emitter, "question", "已解析问题：" + question);
                if (followUpApplied) {
                    sseStreamSupport.emitThinking(emitter, "context", "已启用多轮上下文，本轮将结合上一轮问答理解追问。");
                }

                var learningOpt = learningCommandParser.parse(question, scope);
                if (learningOpt.isPresent()) {
                    var learningCommand = learningOpt.get();
                    sseStreamSupport.emitThinking(emitter, "learning", "识别到主动学习意图，正在写入知识库。");
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
                    sseStreamSupport.sendStreamResponse(emitter, learningResponse, true);
                    return;
                }

                if (explicitCompanyHint) {
                    sseStreamSupport.emitThinking(emitter, "hint", "已从问题中识别到具体对象名称或编号，将优先围绕该对象检索。");
                }
                List<ContextChunk> learnedFirst = retrievalPipeline.safeActiveLearningRetrieve(question, scope);
                QaRetrievalOrchestrator.RetrievalPlan retrievalPlan =
                        retrievalOrchestrator.prepareRetrievalQuestion(sessionRetrievalSeed, learnedFirst);
                String retrievalQuestion = retrievalPlan.retrievalQuestion();
                if (retrievalPlan.appliedLearningRewrite()) {
                    sseStreamSupport.emitThinking(
                            emitter,
                            "orchestrate",
                            "调度中心：根据已学别名关系，将检索问句调整为「" + retrievalQuestion + "」，便于匹配结构化人员与任职信息。"
                    );
                }

                if (!explicitCompanyHint
                        && !skipCompanyClarify
                        && clarificationAdvisor.needsClarification(question, scope)
                        && !retrievalPipeline.preferActiveLearning(question, explicitCompanyHint, learnedFirst)) {
                    List<CompanyCandidate> candidates = graphContextService.suggestCompanyCandidates(question, 5);
                    sseStreamSupport.emitThinking(emitter, "clarify", "当前指代不够具体，需要先确认你要查询的是哪一个对象。");
                    String clarifyAnswer = clarificationAdvisor.buildClarificationAnswer(candidates);
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
                    putEvidenceAlignment(response, question, clarifyAnswer, List.of(), false);
                    qaLogService.appendAskEvent(response);
                    sseStreamSupport.sendStreamResponse(emitter, response, true);
                    return;
                }

                IntentDecision intentDecision = intentRouterService.decide(retrievalQuestion, explicitCompanyHint);
                String intentDetail = intentDecision.queryType() != null && !intentDecision.queryType().isBlank()
                        ? "，形态=" + intentDecision.queryType()
                        : "";
                if (intentDecision.hasPersonFocus()) {
                    intentDetail += "，人物=" + intentDecision.personName();
                }
                sseStreamSupport.emitThinking(
                        emitter,
                        "intent",
                        String.format(
                                "路由判定：%s（置信度 %.2f%s）。原因：%s",
                                intentDecision.intent(),
                                intentDecision.confidence(),
                                intentDetail,
                                intentDecision.reason()
                        )
                );

                QaRetrievalPipeline.RetrievalResult retrievalResult;
                if (QaScopes.PERSONAL.equals(scope)) {
                    if (!learnedFirst.isEmpty()) {
                        sseStreamSupport.emitThinking(emitter, "learning_recall", "当前为个人知识库模式，已命中个人记忆。");
                        retrievalResult = new QaRetrievalPipeline.RetrievalResult("active_learning_personal", learnedFirst);
                    } else {
                        sseStreamSupport.emitThinking(emitter, "learning_recall", "当前为个人知识库模式，但尚未命中个人知识。");
                        retrievalResult = new QaRetrievalPipeline.RetrievalResult("personal_scope_no_memory", List.of());
                    }
                } else if (QaScopes.ENTERPRISE.equals(scope) && properties.isUnifiedRetrievalEnabled()) {
                    sseStreamSupport.emitThinking(emitter, "retrieval", "企业统一召回：图+向量+结构化+主动学习，并重排证据。");
                    retrievalResult = retrievalPipeline.retrieveUnifiedEnterprise(
                            retrievalQuestion,
                            learnedFirst,
                            intentDecision
                    );
                } else if (retrievalPipeline.preferActiveLearning(question, explicitCompanyHint, learnedFirst)) {
                    sseStreamSupport.emitThinking(emitter, "learning_recall", "命中主动学习知识，优先基于新记忆回答。");
                    retrievalResult = new QaRetrievalPipeline.RetrievalResult("active_learning_priority", learnedFirst);
                } else {
                    retrievalResult = retrievalPipeline.retrieveByIntent(intentDecision.intent(), retrievalQuestion);
                    if (QaScopes.ENTERPRISE.equals(scope)) {
                        retrievalResult = retrievalPipeline.mergeEnterpriseActiveLearning(retrievalResult, learnedFirst, explicitCompanyHint);
                    }
                }
                String retrievalSource = retrievalResult.retrievalSource();
                List<ContextChunk> evidence = new ArrayList<>(retrievalResult.evidence());
                if (QaScopes.ENTERPRISE.equals(scope) && retrievalPlan.appliedLearningRewrite()) {
                    evidence.removeIf(c ->
                            "mysql-employee-precheck".equals(c.source())
                                    && "employee_not_found".equals(c.companyId())
                    );
                }
                QaAnswerGateService.GateDecision gate = answerGateService.evaluate(intentDecision, evidence);
                boolean canAnswer = gate.canAnswer();
                boolean allowGenerate = gate.allowGenerate();
                boolean unknownIntent = "unknown".equalsIgnoreCase(intentDecision.intent());
                sseStreamSupport.emitThinking(
                        emitter,
                        "retrieval",
                        String.format("检索完成：来源=%s，命中证据=%d 条。", retrievalSource, evidence.size())
                );
                if (!evidence.isEmpty()) {
                    String companies = evidence.stream()
                            .map(ContextChunk::companyName)
                            .filter(name -> name != null && !name.isBlank())
                            .distinct()
                            .limit(5)
                            .reduce((a, b) -> a + "、" + b)
                            .orElse("已命中相关企业信息");
                    sseStreamSupport.emitThinking(emitter, "evidence", "检索到的相关对象：" + companies);
                }

                String route = resolveInitialRoute(unknownIntent, allowGenerate, gate.rejectReason(), retrievalSource);
                double confidence = allowGenerate ? answerFallbackService.calcConfidence(evidence) : 0.20;
                String answer;
                boolean degraded = false;
                String fallbackReason = "";
                boolean answerAlreadyStreamed = false;

                if (unknownIntent && evidence.isEmpty()) {
                    sseStreamSupport.emitThinking(emitter, "decision", "当前问题超出知识库覆盖范围，先返回引导性说明。");
                    answer = KnowledgeAssistantPrompts.unknownCoverageUserMessage();
                    route = "reject_unknown_intent";
                } else if (!allowGenerate) {
                    sseStreamSupport.emitThinking(emitter, "decision", "证据不足或未过回答闸门，返回补充建议。");
                    answer = KnowledgeAssistantPrompts.insufficientEvidenceStreamingHint();
                    route = "reject_gate_" + nullToEmpty(gate.rejectReason());
                } else {
                    sseStreamSupport.emitThinking(emitter, "generation", "开始调用模型进行流式生成。");
                    try {
                        final boolean[] streamed = {false};
                        answer = miniMaxClient.askWithEvidenceStream(modelContextBlock, question, evidence, (type, content) -> {
                            if (content == null || content.isBlank()) {
                                return;
                            }
                            if ("thinking".equals(type)) {
                                sseStreamSupport.emitThinking(emitter, "model", content);
                                return;
                            }
                            if ("delta".equals(type)) {
                                try {
                                    streamed[0] = true;
                                    emitter.send(SseEmitter.event().name("delta").data(content));
                                } catch (IOException ioException) {
                                    throw new RuntimeException(ioException);
                                }
                            }
                        });
                        answerAlreadyStreamed = streamed[0];
                        route = retrievalSource + "_generate_llm";
                    } catch (Exception ex) {
                        degraded = true;
                        fallbackReason = answerFallbackService.sanitizeError(ex.getMessage());
                        sseStreamSupport.emitThinking(emitter, "degrade", "模型流式生成失败，已切换为保底回答。");
                        answer = answerFallbackService.buildFallbackAnswer(question, evidence);
                        route = retrievalSource + "_fallback_template";
                    }
                }

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
                        : "none");
                if (gate.rejectReason() != null) {
                    response.put("answerGateRejectReason", gate.rejectReason());
                }
                boolean shouldDeposit = unknownIntent || !canAnswer;
                response.put("knowledgeDepositTriggered", shouldDeposit);
                if (degraded) {
                    response.put("fallbackReason", fallbackReason);
                }
                putEvidenceAlignment(response, question, answer, evidence, canAnswer);
                qaLogService.appendAskEvent(response);
                if (shouldDeposit) {
                    String depositReason = unknownIntent ? "unknown_intent" : nullToEmpty(gate.rejectReason(), "insufficient_evidence");
                    Map<String, Object> candidate = qaLogService.buildKnowledgeCandidateEvent(
                            turnId,
                            question,
                            intentDecision.intent(),
                            retrievalSource,
                            depositReason,
                            evidence
                    );
                    qaLogService.appendKnowledgeCandidate(candidate);
                    sedimentationQueueService.enqueuePending(
                            turnId,
                            question,
                            intentDecision.intent(),
                            retrievalSource,
                            depositReason,
                            evidence
                    );
                }
                conversationService.appendTurn(convId, scope, turnId, question, answer, evidence);
                sseStreamSupport.sendStreamResponse(emitter, response, !answerAlreadyStreamed);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("qa_error").data(Map.of(
                            "message", answerFallbackService.sanitizeError(e.getMessage()),
                            "timestamp", OffsetDateTime.now().toString()
                    )));
                } catch (Exception ignored) {
                    // Ignore secondary failures while reporting stream error.
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
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

    private static void putIntentMetadata(Map<String, Object> response, IntentDecision intentDecision) {
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
}
