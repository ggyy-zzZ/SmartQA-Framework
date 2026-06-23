package com.qa.demo.qa.docvec.service;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.docvec.answer.DocVecAnswerGenerator;
import com.qa.demo.qa.docvec.config.DocVecProperties;
import com.qa.demo.qa.docvec.retrieval.DocVecQdrantRetriever;
import com.qa.demo.qa.docvec.retrieval.DocVecRerankService;
import com.qa.demo.qa.docvec.retrieval.DocVecViewSqlRetriever;
import com.qa.demo.qa.docvec.routing.DocVecQueryRouter;
import com.qa.demo.qa.docvec.routing.DocVecQueryType;
import com.qa.demo.qa.docvec.routing.DocVecRetrievalMode;
import com.qa.demo.qa.docvec.routing.DocVecRouteDecision;
import com.qa.demo.qa.docvec.session.DocVecConversationService;
import com.qa.demo.qa.docvec.session.DocVecConversationTurn;
import com.qa.demo.qa.docvec.session.DocVecSessionSnapshot;
import com.qa.demo.qa.docvec.web.DocVecAskRequest;
import com.qa.demo.qa.docvec.web.DocVecAskResponse;
import com.qa.demo.qa.docvec.web.DocVecEvidenceItem;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * DocVec 问答编排：LLM 路由 → 视图 SQL 或 Doc-RAG → LLM 生成；支持多轮会话。
 */
@Service
public class DocVecAskService {

    private final DocVecProperties properties;
    private final DocVecQueryRouter queryRouter;
    private final DocVecViewSqlRetriever sqlRetriever;
    private final DocVecQdrantRetriever ragRetriever;
    private final DocVecRerankService rerankService;
    private final DocVecAnswerGenerator answerGenerator;
    private final DocVecConversationService conversationService;

    public DocVecAskService(
            DocVecProperties properties,
            DocVecQueryRouter queryRouter,
            DocVecViewSqlRetriever sqlRetriever,
            DocVecQdrantRetriever ragRetriever,
            DocVecRerankService rerankService,
            DocVecAnswerGenerator answerGenerator,
            DocVecConversationService conversationService
    ) {
        this.properties = properties;
        this.queryRouter = queryRouter;
        this.sqlRetriever = sqlRetriever;
        this.ragRetriever = ragRetriever;
        this.rerankService = rerankService;
        this.answerGenerator = answerGenerator;
        this.conversationService = conversationService;
    }

    public DocVecAskResponse ask(DocVecAskRequest request) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isBlank()) {
            return emptyResponse(question, "问题不能为空", "");
        }
        if (!properties.isEnabled()) {
            return emptyResponse(question, "Doc-RAG 实验路径已禁用（qa.docvec.enabled=false）", "");
        }

        String conversationId = conversationService.resolveConversationId(request.conversationId());
        List<DocVecConversationTurn> prior = conversationService.recentTurns(conversationId, 3);
        boolean followUp = conversationService.resolveFollowUp(request.followUp(), question, prior);
        DocVecSessionSnapshot session = conversationService.buildSnapshot(prior, followUp);

        DocVecRouteDecision route = queryRouter.route(question, session);

        DocVecAskResponse response;
        List<ContextChunk> evidenceForSession;
        if (route.mode() == DocVecRetrievalMode.SQL) {
            var result = askViaSql(question, route, session, conversationId, followUp);
            response = result.response();
            evidenceForSession = result.evidence();
        } else {
            var result = askViaRag(question, request, route, conversationId, followUp);
            response = result.response();
            evidenceForSession = result.evidence();
        }

        conversationService.appendTurn(conversationId, question, response.answer(), route, evidenceForSession);
        return response;
    }

    private record AskResult(DocVecAskResponse response, List<ContextChunk> evidence) {
    }

    private AskResult askViaSql(
            String question,
            DocVecRouteDecision route,
            DocVecSessionSnapshot session,
            String conversationId,
            boolean followUp
    ) {
        List<ContextChunk> evidence = sqlRetriever.retrieve(route, session);
        String answer = answerGenerator.generate(question, evidence, true);
        double confidence = evidence.isEmpty() ? 0.0 : 0.9;
        Map<String, Object> debug = buildRouteDebug(route, followUp);
        debug.put("retrievalMode", "sql");
        debug.put("sqlMaxRows", properties.getSqlMaxRows());

        return new AskResult(buildResponse(question, answer, confidence, !evidence.isEmpty(),
                "docvec_sql", "tdcomp_views", evidence.size(), evidence.size(),
                toEvidenceItems(evidence), debug, conversationId), evidence);
    }

    private AskResult askViaRag(
            String question,
            DocVecAskRequest request,
            DocVecRouteDecision route,
            String conversationId,
            boolean followUp
    ) {
        int vectorTopK = request.topK() != null && request.topK() > 0
                ? request.topK()
                : properties.getVectorTopK();
        int rerankTopK = request.rerankTopK() != null && request.rerankTopK() > 0
                ? request.rerankTopK()
                : properties.getRerankTopK();

        String retrievalQuestion = buildRagQuestion(question, route);
        List<ContextChunk> recalled = ragRetriever.retrieve(retrievalQuestion, vectorTopK);
        List<ContextChunk> evidence = rerankService.rerank(retrievalQuestion, recalled, rerankTopK);
        String answer = answerGenerator.generate(question, evidence, false);

        Map<String, Object> debug = buildRouteDebug(route, followUp);
        debug.put("retrievalMode", "rag");
        debug.put("retrievalQuestion", retrievalQuestion);
        debug.put("vectorTopK", vectorTopK);
        debug.put("rerankTopK", rerankTopK);
        debug.put("rerankEnabled", properties.isRerankEnabled());

        return new AskResult(buildResponse(question, answer, estimateConfidence(evidence), !evidence.isEmpty(),
                "docvec_rag", properties.getCollection(), recalled.size(), evidence.size(),
                toEvidenceItems(evidence), debug, conversationId), evidence);
    }

    private static String buildRagQuestion(String question, DocVecRouteDecision route) {
        if (route.queryType() == DocVecQueryType.COMPANY_DETAIL
                && route.companyNameHint() != null
                && !route.companyNameHint().isBlank()) {
            return route.companyNameHint() + " " + question;
        }
        return question;
    }

    private Map<String, Object> buildRouteDebug(DocVecRouteDecision route, boolean followUp) {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("routeReason", route.reason());
        debug.put("queryType", route.queryType().name());
        debug.put("routeConfidence", route.routeConfidence());
        debug.put("followUpApplied", followUp || route.followUpApplied());
        debug.put("personName", route.personName());
        debug.put("roleLabel", route.roleLabel());
        debug.put("regionKeyword", route.regionKeyword());
        debug.put("certificateTypeName", route.certificateTypeName());
        debug.put("certificateTypeId", route.certificateTypeId());
        debug.put("companyNameHint", route.companyNameHint());
        debug.put("countQuery", route.countQuery());
        return debug;
    }

    private static DocVecAskResponse emptyResponse(String question, String answer, String conversationId) {
        return buildResponse(question, answer, 0.0, false, "docvec_none", "",
                0, 0, List.of(), Map.of(), conversationId);
    }

    private static DocVecAskResponse buildResponse(
            String question,
            String answer,
            double confidence,
            boolean canAnswer,
            String route,
            String collection,
            int recalledCount,
            int evidenceCount,
            List<DocVecEvidenceItem> evidence,
            Map<String, Object> debug,
            String conversationId
    ) {
        return new DocVecAskResponse(
                question,
                answer,
                confidence,
                canAnswer,
                route,
                collection,
                recalledCount,
                evidenceCount,
                evidence,
                debug,
                conversationId,
                OffsetDateTime.now().toString()
        );
    }

    private static double estimateConfidence(List<ContextChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0;
        }
        double top = evidence.get(0).score();
        if (top >= 15.0) {
            return 0.85;
        }
        if (top >= 10.0) {
            return 0.7;
        }
        if (top >= 5.0) {
            return 0.5;
        }
        return 0.3;
    }

    private static List<DocVecEvidenceItem> toEvidenceItems(List<ContextChunk> evidence) {
        List<DocVecEvidenceItem> items = new ArrayList<>();
        for (ContextChunk chunk : evidence) {
            String preview = chunk.snippet() == null ? "" : chunk.snippet();
            if (preview.length() > 480) {
                preview = preview.substring(0, 480) + "...";
            }
            items.add(new DocVecEvidenceItem(
                    chunk.anchorId(),
                    chunk.displayLabel(),
                    chunk.score(),
                    preview,
                    chunk.source()
            ));
        }
        return items;
    }
}
