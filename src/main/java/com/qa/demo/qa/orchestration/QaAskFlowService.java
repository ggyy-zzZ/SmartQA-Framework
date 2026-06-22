package com.qa.demo.qa.orchestration;

import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.answer.EvidenceTruncationAdvisor;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.answer.QaAnswerFallbackService;
import com.qa.demo.qa.answer.QaAnswerGateService;
import com.qa.demo.qa.alignment.EvidenceAlignmentService;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.constraint.ConstraintResolver;
import com.qa.demo.qa.constraint.ConstraintSet;
import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.RetrievalStrategy;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.QaScopes;
import com.qa.demo.qa.domain.EntityRef;
import com.qa.demo.qa.domain.EvidenceToEntityExtractor;
import com.qa.demo.qa.embedding.TextEmbeddingService;
import com.qa.demo.qa.intent.CompanyClarificationAdvisor;
import com.qa.demo.qa.intent.FollowUpIntentContext;
import com.qa.demo.qa.intent.IntentSlots;
import com.qa.demo.qa.intent.IntentRouterService;
import com.qa.demo.qa.intent.IntentScopeNormalizer;
import com.qa.demo.qa.intent.IntentRoutingOutcome;
import com.qa.demo.qa.intent.PersonClarificationAdvisor;
import com.qa.demo.qa.intent.PersonNameResolution;
import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.learning.ChatLearningCommandParser;
import com.qa.demo.qa.learning.LearningResponseBuilder;
import com.qa.demo.qa.response.QaConversationService;
import com.qa.demo.qa.response.QaLogService;
import com.qa.demo.qa.debug.GateMetricsWriter;
import com.qa.demo.qa.execution.AgentStepResult;
import com.qa.demo.qa.execution.QaExecutionService;
import com.qa.demo.qa.execution.QaMultiStepExecutor;
import com.qa.demo.qa.planning.AgentTaskPlan;
import com.qa.demo.qa.planning.CompanyHintService;
import com.qa.demo.qa.planning.QaTaskPlannerService;
import com.qa.demo.qa.review.PostGenerationReviewService;
import com.qa.demo.qa.review.QaReviewService;
import com.qa.demo.qa.answer.EvidenceFieldCoverageAdvisor;
import com.qa.demo.qa.retrieval.EvidencePresentationContext;
import com.qa.demo.qa.retrieval.EvidencePresentationPolicy;
import com.qa.demo.qa.retrieval.EvidenceTruncationMeta;
import com.qa.demo.qa.retrieval.QaRetrievalOrchestrator;
import com.qa.demo.qa.retrieval.QaRetrievalPipeline;
import com.qa.demo.qa.retrieval.catalog.InformationNeedMerger;
import com.qa.demo.qa.retrieval.catalog.NeedInferenceService;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogRegistry;
import com.qa.demo.qa.retrieval.certificate.CertificateListQuestionSupport;
import com.qa.demo.qa.sedimentation.SedimentationQueueService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 同步/流式共用的问答主流程，避免 {@link QaAskOrchestrator} 双份维护。
 */
@Service
public class QaAskFlowService {

    private static final Pattern DIGIT_ONLY = Pattern.compile("^\\d{1,2}$");
    private static final Pattern ALIAS_IDENTITY = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,6})\\s*(?:是|就是|即|叫|叫做)\\s*([\\u4e00-\\u9fa5]{2,6})"
    );

    private final IntentRouterService intentRouterService;
    private final ActiveLearningService activeLearningService;
    private final QaTaskPlannerService taskPlannerService;
    private final QaMultiStepExecutor multiStepExecutor;
    private final CompanyHintService companyHintService;
    private final QaExecutionService executionService;
    private final QaReviewService reviewService;
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
    private final NeedInferenceService needInferenceService;
    private final InformationNeedMerger informationNeedMerger;
    private final RetrievalCatalogRegistry retrievalCatalogRegistry;
    private final IntentScopeNormalizer intentScopeNormalizer;
    private final ConstraintResolver constraintResolver;
    private final GateMetricsWriter gateMetricsWriter;
    private final EvidenceFieldCoverageAdvisor fieldCoverageAdvisor;
    private final EvidencePresentationPolicy evidencePresentationPolicy;
    private final EvidenceTruncationAdvisor truncationAdvisor;
    private final PostGenerationReviewService postGenerationReviewService;

    public QaAskFlowService(
            IntentRouterService intentRouterService,
            ActiveLearningService activeLearningService,
            QaTaskPlannerService taskPlannerService,
            QaMultiStepExecutor multiStepExecutor,
            CompanyHintService companyHintService,
            QaExecutionService executionService,
            QaReviewService reviewService,
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
            EnterpriseCanonicalFactsRegistry canonicalFactsRegistry,
            NeedInferenceService needInferenceService,
            InformationNeedMerger informationNeedMerger,
            RetrievalCatalogRegistry retrievalCatalogRegistry,
            IntentScopeNormalizer intentScopeNormalizer,
            ConstraintResolver constraintResolver,
            GateMetricsWriter gateMetricsWriter,
            EvidenceFieldCoverageAdvisor fieldCoverageAdvisor,
            EvidencePresentationPolicy evidencePresentationPolicy,
            EvidenceTruncationAdvisor truncationAdvisor,
            PostGenerationReviewService postGenerationReviewService
    ) {
        this.intentRouterService = intentRouterService;
        this.activeLearningService = activeLearningService;
        this.taskPlannerService = taskPlannerService;
        this.multiStepExecutor = multiStepExecutor;
        this.companyHintService = companyHintService;
        this.executionService = executionService;
        this.reviewService = reviewService;
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
        this.needInferenceService = needInferenceService;
        this.informationNeedMerger = informationNeedMerger;
        this.retrievalCatalogRegistry = retrievalCatalogRegistry;
        this.intentScopeNormalizer = intentScopeNormalizer;
        this.constraintResolver = constraintResolver;
        this.gateMetricsWriter = gateMetricsWriter;
        this.fieldCoverageAdvisor = fieldCoverageAdvisor;
        this.evidencePresentationPolicy = evidencePresentationPolicy;
        this.truncationAdvisor = truncationAdvisor;
        this.postGenerationReviewService = postGenerationReviewService;
    }

    /**
     * 单轮问答主流程，同步 {@link QaAskOrchestrator#buildAskResponse} 与流式 SSE 共用。
     * <p>
     * 阶段顺序：会话准备 →（可选）主动学习写入 → Bootstrap 召回 → 澄清分支 → 意图/Need →
     * 多路检索 → 证据闸门 → LLM 生成或拒答 → 日志与会话落盘。
     * 澄清类分支（公司/人物）在中间提前返回，不走检索与生成。
     *
     * @param question       用户原问
     * @param scope          知识域（如 {@link QaScopes#ENTERPRISE}）
     * @param conversationId 会话 ID；空则新建
     * @param followUpFlag   是否接续上一轮；{@code null} 时由会话服务推断
     * @param progress       思考阶段回调；同步入口传 {@link QaAskProgress#NOOP}
     * @return 结构化响应（answer、evidence、route、conversationId 等）
     */
    public Map<String, Object> run(
            String question,
            String scope,
            String conversationId,
            Boolean followUpFlag,
            QaAskProgress progress
    ) throws IOException {
        return run(question, scope, conversationId, followUpFlag, null, progress);
    }

    public Map<String, Object> run(
            String question,
            String scope,
            String conversationId,
            Boolean followUpFlag,
            String evidencePresentationMode,
            QaAskProgress progress
    ) throws IOException {
        // P0-S6：闸门指标埋点计时起点
        long startMs = System.currentTimeMillis();
        // --- 1. 会话与多轮上下文 ---
        progress.onThinking("prep", "正在初始化会话…", null);
        String turnId = qaLogService.nextTurnId();
        String convId = conversationService.resolveConversationId(conversationId);
        List<QaConversationService.ConversationTurn> prior = conversationService.recentTurns(convId, 4);
        boolean followUpApplied = conversationService.resolveFollowUp(followUpFlag, question, prior);

        EvidencePresentationContext presentation = evidencePresentationPolicy.resolve(
                question, evidencePresentationMode, prior);

        // 人物澄清后用户选序号/短名时，拼回上轮原问供检索与意图使用
        question = applyPersonFollowUpQuestion(question, prior);

        String sessionRetrievalSeed = conversationService.buildRetrievalQuestion(question, prior, followUpApplied);
        boolean explicitCompanyHint = companyHintService.hasExplicitCompanyHint(question)
                || companyHintService.hasExplicitCompanyHint(sessionRetrievalSeed);
        boolean skipCompanyClarify = followUpApplied && conversationService.priorHasCompanyFocus(convId);
        String modelContextBlock = conversationService.buildModelContextBlock(prior, followUpApplied, question);

        // --- 2. 主动学习指令（非问答，直接落库后返回）---
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
            String learnedPerson = inferLearningFocusPerson(learningCommand.content());
            conversationService.appendTurn(
                    convId,
                    learningCommand.scope(),
                    turnId,
                    question,
                    String.valueOf(learningResponse.get("answer")),
                    List.of(),
                    List.of(),
                    learnedPerson,
                    "learning",
                    ""
            );
            return learningResponse;
        }

        // --- 3. Bootstrap 召回（在意图路由之前，供别名改写与 preferActiveLearning 判断）---
        List<ContextChunk> canonicalHits = mergeBootstrapKnowledge(
                canonicalFactsRegistry.retrieve(sessionRetrievalSeed, scope),
                canonicalFactsRegistry.retrieve(question, scope)
        );
        List<ContextChunk> activeLearningHits = shouldSkipActiveLearningBootstrap(question, sessionRetrievalSeed)
                ? List.of()
                : retrievalPipeline.safeActiveLearningRetrieve(question, scope);
        List<ContextChunk> learnedFirst = mergeBootstrapKnowledge(canonicalHits, activeLearningHits);
        QaRetrievalOrchestrator.RetrievalPlan retrievalPlan =
                retrievalOrchestrator.prepareRetrievalQuestion(sessionRetrievalSeed, learnedFirst);
        String retrievalQuestion = retrievalPlan.retrievalQuestion();
        String intentQuestion = intentInputForLlm(question, retrievalQuestion);

        progress.onThinking(
                "recall",
                "常识与记忆",
                QaThinkingDigest.bootstrapRecall(canonicalHits, activeLearningHits)
        );
        progress.onThinking(
                "decompose",
                "问题理解",
                QaThinkingDigest.decompose(
                        question, retrievalQuestion, followUpApplied, modelContextBlock, retrievalPlan
                )
        );

        // --- 4. 公司指代澄清（模糊指代且主动学习未强命中时提前返回）---
        if (!explicitCompanyHint
                && !skipCompanyClarify
                && companyClarificationAdvisor.needsClarification(question, scope)
                && !retrievalPipeline.preferActiveLearning(question, explicitCompanyHint, learnedFirst)) {
            progress.onThinking("clarify", "公司指代不够具体，需要先确认对象。", null);
            return buildCompanyClarificationResponse(
                    turnId, question, scope, convId, followUpApplied, companyHintService.suggestCompanyCandidates(question, 5));
        }

        // --- 5. 意图路由 + 信息需求（Need）推断 ---
        progress.onThinking("intent_wait", "正在理解问题意图…", null);
        FollowUpIntentContext followUpContext = followUpApplied
                ? conversationService.buildFollowUpContext(prior, null)
                : FollowUpIntentContext.inactive();
        IntentRoutingOutcome routing = intentRouterService.decide(
                intentQuestion, explicitCompanyHint, learnedFirst, followUpContext);
        IntentDecision intentDecision = intentScopeNormalizer.normalize(routing.decision(), question);
        InformationNeed informationNeed = resolveInformationNeed(question, intentDecision);
        if (CertificateListQuestionSupport.isGlobalCertificateListNeed(informationNeed)) {
            intentDecision = new IntentDecision(
                    intentDecision.intent(),
                    intentDecision.confidence(),
                    intentDecision.reason() + "; need_override:global_certificate_list",
                    intentDecision.personName(),
                    intentDecision.companyHints(),
                    intentDecision.roleFocus(),
                    intentDecision.personEmployeeId(),
                    com.qa.demo.qa.core.RetrievalStrategy.STRUCTURED_LIST.token()
            );
        }
        progress.onThinking("intent_done", "意图识别完成。", null);
        progress.onThinking(
                "route",
                "意图与实体",
                QaThinkingDigest.route(intentDecision, intentDecision.reason())
        );

        // --- 6. 人物歧义澄清（图谱多候选，意图阶段提前返回）---
        if (routing.needsPersonClarification()) {
            progress.onThinking("clarify", "人物指称存在歧义，需要补充全名。", null);
            return buildPersonClarificationResponse(
                    turnId, question, scope, convId, followUpApplied, intentDecision, routing.personResolution());
        }

        // --- 6b. Planner：多步任务拆解（对比/跨源计算）---
        AgentTaskPlan taskPlan = taskPlannerService.plan(question, intentDecision, informationNeed);
        if (taskPlan.requiresMultiStepExecution()) {
            progress.onThinking(
                    "plan",
                    "多步任务规划",
                    Map.of("lines", taskPlannerService.planDigestLines(taskPlan))
            );
        }

        // --- 7. Execution：单轮或多步检索 ---
        boolean multiStepPath = taskPlan.requiresMultiStepExecution();
        progress.onThinking(
                "retrieve",
                multiStepPath
                        ? "正在按子任务多步检索…"
                        : nullToEmpty(
                        retrievalCatalogRegistry.thinkingMessageFor(informationNeed, intentDecision),
                        "正在多路召回并重排证据…"),
                null
        );
        ConstraintSet constraint = QaScopes.ENTERPRISE.equals(scope)
                ? constraintResolver.resolve(question, intentDecision)
                : ConstraintSet.empty();
        String retrievalSource;
        List<ContextChunk> evidence;
        QaRetrievalPipeline.RetrievalResult retrievalRaw;
        List<AgentStepResult> agentStepResults = List.of();
        if (multiStepPath) {
            QaMultiStepExecutor.MultiStepOutcome multi = multiStepExecutor.execute(
                    taskPlan,
                    scope,
                    question,
                    learnedFirst,
                    intentDecision,
                    explicitCompanyHint,
                    constraint,
                    retrievalPlan.appliedLearningRewrite()
            );
            retrievalSource = multi.retrievalSource();
            evidence = multi.evidence();
            agentStepResults = multi.stepResults();
            retrievalRaw = new QaRetrievalPipeline.RetrievalResult(retrievalSource, evidence);
        } else {
            QaExecutionService.RetrievalOutcome retrievalOutcome = executionService.retrieve(
                    scope,
                    question,
                    retrievalQuestion,
                    learnedFirst,
                    intentDecision,
                    informationNeed,
                    constraint,
                    explicitCompanyHint,
                    retrievalPlan.appliedLearningRewrite(),
                    presentation
            );
            retrievalSource = retrievalOutcome.retrievalSource();
            evidence = retrievalOutcome.evidence();
            retrievalRaw = retrievalOutcome.raw();
        }

        // --- 8. Review：证据闸门 → 生成 / 拒答 / 澄清 ---
        QaAnswerGateService.GateDecision gate = reviewService.evaluateGate(
                question, intentDecision, informationNeed, evidence, retrievalSource);
        boolean unknownIntent = "unknown".equalsIgnoreCase(intentDecision.intent());
        QaReviewService.ReviewDecision review = reviewService.decideBeforeGeneration(
                question,
                intentDecision,
                informationNeed,
                evidence,
                gate,
                unknownIntent,
                retrievalSource,
                routing.personResolution()
        );
        boolean canAnswer = review.canAnswer();
        boolean allowGenerate = review.allowGenerate();
        String route = review.route();
        double confidence = allowGenerate ? answerFallbackService.calcConfidence(evidence) : 0.20;
        String answer = review.answer();
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

        if (review.kind() == QaReviewService.DecisionKind.CLARIFY_PERSON) {
            progress.onThinking("clarify", "未能锁定具体人员，引导补充全名。", null);
            return buildPersonClarificationResponse(
                    turnId, question, scope, convId, followUpApplied, intentDecision, routing.personResolution());
        }
        if (review.kind() == QaReviewService.DecisionKind.GENERATE) {
            progress.onThinking("generate", "正在依据证据组织回答…", null);
            QaExecutionService.GenerationOutcome generated;
            if (multiStepPath) {
                String stepBlock = multiStepExecutor.buildStepContextBlock(agentStepResults);
                generated = executionService.generateMultiStep(
                        modelContextBlock, stepBlock, question, evidence, intentDecision, retrievalSource);
            } else {
                generated = executionService.generate(
                        modelContextBlock, question, evidence, intentDecision, retrievalSource, true);
            }
            answer = generated.answer();
            route = generated.route();
            confidence = generated.confidence();
            degraded = generated.degraded();
            fallbackReason = generated.fallbackReason();
            if (generated.degraded()) {
                progress.onThinking("degrade", "模型生成失败，已切换保底回答。", null);
            }
            PostGenerationReviewService.Adjustment postGen = postGenerationReviewService.adjust(
                    question, answer, evidence, canAnswer, confidence, degraded);
            canAnswer = postGen.canAnswer();
            confidence = postGen.confidence();
        } else if (review.kind() == QaReviewService.DecisionKind.REJECT_UNKNOWN) {
            progress.onThinking("decision", "超出知识库覆盖范围。", null);
        } else if (review.kind() == QaReviewService.DecisionKind.CLARIFY_FIELD) {
            progress.onThinking("clarify", "筛选字段未在证据中出现，引导补充问法。", null);
        } else {
            progress.onThinking("decision", "证据不足，返回补充建议。", null);
        }

        EvidenceTruncationMeta truncation = retrievalRaw != null ? retrievalRaw.truncation() : null;
        if (review.kind() == QaReviewService.DecisionKind.GENERATE) {
            String truncationNotice = truncationAdvisor.buildNotice(truncation);
            if (!truncationNotice.isBlank()) {
                answer = answer + truncationNotice;
            }
        }

        // --- 9. 响应组装、事件日志、知识沉淀队列与会话落盘 ---
        Map<String, Object> response = assembleResponse(
                turnId, question, scope, convId, followUpApplied, intentDecision, informationNeed, evidence,
                answer, canAnswer, confidence, route, retrievalSource, retrievalRaw, gate, degraded, fallbackReason,
                unknownIntent, taskPlan, agentStepResults, presentation, truncation
        );
        qaLogService.appendAskEvent(response);
        if (Boolean.TRUE.equals(response.get("knowledgeDepositTriggered"))) {
            enqueueDeposit(turnId, question, intentDecision.intent(), retrievalSource,
                    unknownIntent ? "unknown_intent" : nullToEmpty(gate.rejectReason(), "insufficient_evidence"), evidence);
        }
        String focusPerson = intentDecision.hasPersonFocus() ? intentDecision.personName().trim() : "";
        // 从证据中提取结构化实体，供后续轮次使用
        Map<String, List<EntityRef>> retrievedEntities = EvidenceToEntityExtractor.extractForNeed(
                evidence, informationNeed);
        conversationService.appendTurn(
                convId, scope, turnId, question, answer, evidence, List.of(), focusPerson,
                intentDecision.intent(), intentDecision.retrievalStrategy(), retrievedEntities);
        // P0-S6：闸门指标埋点（主出口；type(record 完整版) 与 stage-4/8 的 recordRaw 互补）
        gateMetricsWriter.record(
                turnId,
                intentDecision,
                evidence,
                gate,
                route,
                confidence,
                canAnswer,
                System.currentTimeMillis() - startMs
        );
        return response;
    }

    /**
     * 任职/法人/证照等结构化问句走业务库与图谱，跳过主动学习 MySQL 扫描以免阻塞首包。
     */
    private static boolean shouldSkipActiveLearningBootstrap(String question, String retrievalSeed) {
        String q = question == null ? "" : question.strip();
        if (q.isBlank()) {
            return false;
        }
        if (retrievalSeed != null && retrievalSeed.length() > 600) {
            return looksLikeStructuredEnterpriseQuery(q);
        }
        return looksLikeStructuredEnterpriseQuery(q);
    }

    private static boolean looksLikeStructuredEnterpriseQuery(String q) {
        return q.contains("法人") || q.contains("董事") || q.contains("监事")
                || q.contains("证照") || q.contains("任职") || q.contains("法定代表人");
    }

    /** 多轮检索句过长时，意图 LLM 仅用用户原问，避免超长 prompt 卡死。 */
    private static String intentInputForLlm(String userQuestion, String retrievalQuestion) {
        if (retrievalQuestion == null || retrievalQuestion.length() <= 600) {
            return retrievalQuestion == null || retrievalQuestion.isBlank() ? userQuestion : retrievalQuestion;
        }
        return userQuestion == null ? "" : userQuestion.strip();
    }

    private InformationNeed resolveInformationNeed(String question, IntentDecision intentDecision) {
        InformationNeed inferred = needInferenceService.infer(question, intentDecision);
        RetrievalStrategy strategy = intentDecision != null
                ? intentDecision.resolvedRetrievalStrategy()
                : RetrievalStrategy.UNKNOWN;
        double confidence = intentDecision != null ? intentDecision.confidence() : 0.0;
        return informationNeedMerger.merge(strategy, confidence, inferred, intentDecision);
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
        reviewService.attachEvidenceAlignment(response, question, clarifyAnswer, List.of(), false);
        qaLogService.appendAskEvent(response);
        enqueueDeposit(turnId, question, "clarification", "none", "needs_company_clarification", List.of());
        conversationService.appendTurn(convId, scope, turnId, question, clarifyAnswer, List.of(), List.of(), "", "", "", Map.of());
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
        reviewService.attachEvidenceAlignment(response, question, clarifyAnswer, List.of(), false);
        qaLogService.appendAskEvent(response);
        enqueueDeposit(turnId, question, intentDecision.intent(), "none", "needs_person_clarification", List.of());
        conversationService.appendTurn(convId, scope, turnId, question, clarifyAnswer, List.of(), personCandidates, "", "", "", Map.of());
        return response;
    }

    private Map<String, Object> assembleResponse(
            String turnId,
            String question,
            String scope,
            String convId,
            boolean followUpApplied,
            IntentDecision intentDecision,
            InformationNeed informationNeed,
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
            boolean unknownIntent,
            AgentTaskPlan taskPlan,
            List<AgentStepResult> agentStepResults,
            EvidencePresentationContext presentation,
            EvidenceTruncationMeta truncation
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
        putRoutingTrace(response, intentDecision, informationNeed, followUpApplied, evidence, taskPlan, agentStepResults);
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
        putEvidencePresentation(response, presentation, truncation);
        reviewService.attachEvidenceAlignment(response, question, answer, evidence, canAnswer);
        return response;
    }

    private void putEvidencePresentation(
            Map<String, Object> response,
            EvidencePresentationContext presentation,
            EvidenceTruncationMeta truncation
    ) {
        if (presentation != null) {
            response.put("evidencePresentationMode", presentation.mode().name().toLowerCase(Locale.ROOT));
            response.put("userEmphasizedComplete", presentation.userEmphasizedComplete());
            response.put("evidenceTopKApplied", presentation.evidenceTopK());
        }
        if (truncation != null) {
            Map<String, Object> truncationMap = new HashMap<>();
            truncationMap.put("truncated", truncation.truncated());
            truncationMap.put("candidatesConsidered", truncation.candidatesConsidered());
            truncationMap.put("presentedToModel", truncation.presentedToModel());
            truncationMap.put("configuredCap", truncation.configuredCap());
            truncationMap.put("omittedCount", truncation.omittedCount());
            truncationMap.put("offerFullDetail", truncation.truncated());
            truncationMap.put("suggestedFollowUp", "展示完整数据");
            response.put("evidenceTruncation", truncationMap);
        }
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

    private static String nullToEmpty(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String nullToEmpty(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private void putRoutingTrace(
            Map<String, Object> response,
            IntentDecision intentDecision,
            InformationNeed informationNeed,
            boolean followUpApplied,
            List<ContextChunk> evidence,
            AgentTaskPlan taskPlan,
            List<AgentStepResult> agentStepResults
    ) {
        Map<String, Object> routing = new HashMap<>();
        routing.put("retrievalStrategy", nullToEmpty(intentDecision.retrievalStrategy(), ""));
        if (informationNeed != null) {
            routing.put("needFacet", nullToEmpty(informationNeed.facet(), ""));
            routing.put("needGranularity", nullToEmpty(informationNeed.granularity(), ""));
            routing.put("inferenceReason", nullToEmpty(informationNeed.reason(), ""));
        }
        routing.put("routeSource", resolveRouteSource(intentDecision.reason()));
        routing.put("followUpApplied", followUpApplied);
        routing.put("intentConfidence", intentDecision.confidence());
        routing.put("evidenceCount", evidence == null ? 0 : evidence.size());
        if (intentDecision.hasPersonFocus()) {
            routing.put("personName", intentDecision.personName());
        }
        if (intentDecision.hasCompanyHints()) {
            routing.put("companyHintCount", intentDecision.companyHints().size());
        }
        if (taskPlan != null && taskPlan.requiresMultiStepExecution()) {
            routing.put("agentMultiStep", true);
            routing.put("agentPlannerSource", taskPlan.plannerSource());
            routing.put("agentStepCount", taskPlan.steps().size());
            if (agentStepResults != null && !agentStepResults.isEmpty()) {
                List<Map<String, Object>> stepSummaries = new ArrayList<>();
                for (AgentStepResult step : agentStepResults) {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("id", step.stepId());
                    summary.put("tool", step.tool().wireName());
                    summary.put("question", step.question());
                    summary.put("retrievalSource", step.retrievalSource());
                    summary.put("evidenceCount", step.evidenceCount());
                    stepSummaries.add(summary);
                }
                routing.put("agentSteps", stepSummaries);
            }
        }
        response.put("routing", routing);
    }

    private static String resolveRouteSource(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("followup_llm")) {
            return "followup_llm";
        }
        if (r.contains("session_inherit") || r.contains("session_entity_merge")) {
            return "session_inherit";
        }
        if (r.contains("llm_timeout")) {
            return "llm_timeout";
        }
        if (r.contains("llm")) {
            return "llm";
        }
        if (r.contains("rule")) {
            return "rule";
        }
        return "other";
    }

    private void putIntentMetadata(Map<String, Object> response, IntentDecision intentDecision) {
        response.put("intent", intentDecision.intent());
        response.put("intentConfidence", intentDecision.confidence());
        response.put("routeReason", intentDecision.reason());
        if (intentDecision.hasRetrievalStrategy()) {
            response.put("retrievalStrategy", intentDecision.retrievalStrategy());
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

    /**
     * 识别学习语句中的实名（如「老布是李晓峰」），写入会话锚点方便下一轮“重新回答”承接。
     */
    private static String inferLearningFocusPerson(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher matcher = ALIAS_IDENTITY.matcher(content.trim());
        if (!matcher.find()) {
            return "";
        }
        String right = IntentSlots.sanitizePersonName(matcher.group(2));
        if (!right.isBlank()) {
            return right;
        }
        return IntentSlots.sanitizePersonName(matcher.group(1));
    }
}
