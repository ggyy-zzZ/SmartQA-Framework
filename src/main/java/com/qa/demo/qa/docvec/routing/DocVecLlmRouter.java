package com.qa.demo.qa.docvec.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.docvec.session.DocVecSessionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 路由：在视图 SQL 与 Doc-RAG 之间选择，并抽取执行槽位（含多轮上下文）。
 */
@Component
public class DocVecLlmRouter {

    private static final Logger log = LoggerFactory.getLogger(DocVecLlmRouter.class);
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

    private final MiniMaxClient miniMaxClient;
    private final ObjectMapper objectMapper;
    private final DocVecSlotResolver slotResolver;
    private final String systemPrompt;

    public DocVecLlmRouter(
            MiniMaxClient miniMaxClient,
            ObjectMapper objectMapper,
            DocVecSlotResolver slotResolver
    ) {
        this.miniMaxClient = miniMaxClient;
        this.objectMapper = objectMapper;
        this.slotResolver = slotResolver;
        this.systemPrompt = loadSystemPrompt();
    }

    public DocVecRouteDecision route(String question, DocVecSessionSnapshot session) {
        String q = question == null ? "" : question.trim();
        if (q.isBlank()) {
            return DocVecRouteDecision.rag("empty_question");
        }
        try {
            String userPrompt = buildUserPrompt(q, session);
            String content = miniMaxClient.completeChat(systemPrompt, userPrompt);
            return parseAndNormalize(q, session, content);
        } catch (Exception e) {
            log.warn("docvec llm route failed, fallback semantic rag: {}", e.getMessage());
            return DocVecRouteDecision.rag("llm_route_fallback:" + e.getMessage());
        }
    }

    DocVecRouteDecision parseAndNormalize(String question, DocVecSessionSnapshot session, String content)
            throws Exception {
        JsonNode node = parseJsonNode(content);
        String mode = node.path("mode").asText("rag").trim().toLowerCase();
        DocVecQueryType queryType = DocVecQueryType.fromToken(node.path("queryType").asText(""));
        double confidence = clamp(node.path("confidence").asDouble(0.75));
        String reason = node.path("reason").asText("llm_route");
        boolean followUp = session != null && session.followUp();

        String personName = slotResolver.mergePersonFromSession(
                node.path("personName").asText(""),
                session == null ? "" : session.priorPersonName()
        );
        String roleLabel = slotResolver.mergeRoleFromSession(
                node.path("roleLabel").asText(""),
                session == null ? "" : session.priorRoleLabel()
        );
        String regionKeyword = slotResolver.normalizeRegion(node.path("regionKeyword").asText(""));
        String operatingStatus = node.path("operatingStatusFilter").asText("").trim();
        String certificateTypeName = node.path("certificateTypeName").asText("").trim();
        if (certificateTypeName.isBlank() && session != null && followUp) {
            certificateTypeName = session.priorCertificateTypeName() == null
                    ? "" : session.priorCertificateTypeName().trim();
        }
        String companyNameHint = node.path("companyNameHint").asText("").trim();
        int certificateTypeId = slotResolver.resolveCertificateTypeId(certificateTypeName, question);

        queryType = normalizeQueryType(queryType, mode, personName, roleLabel, regionKeyword,
                operatingStatus, certificateTypeName, certificateTypeId, companyNameHint, session, followUp);

        if (queryType == DocVecQueryType.COMPANY_DETAIL || queryType == DocVecQueryType.SEMANTIC) {
            return DocVecRouteDecision.rag(queryType, reason);
        }

        boolean countQuery = queryType == DocVecQueryType.COMPANY_COUNT;
        return DocVecRouteDecision.sql(
                queryType,
                reason,
                personName,
                roleLabel,
                regionKeyword,
                countQuery,
                operatingStatus,
                certificateTypeName,
                certificateTypeId,
                companyNameHint,
                followUp,
                confidence
        );
    }

    private DocVecQueryType normalizeQueryType(
            DocVecQueryType fromLlm,
            String mode,
            String personName,
            String roleLabel,
            String regionKeyword,
            String operatingStatus,
            String certificateTypeName,
            int certificateTypeId,
            String companyNameHint,
            DocVecSessionSnapshot session,
            boolean followUp
    ) {
        if ("rag".equals(mode)) {
            if (fromLlm == DocVecQueryType.COMPANY_DETAIL || !companyNameHint.isBlank()) {
                return DocVecQueryType.COMPANY_DETAIL;
            }
            return DocVecQueryType.SEMANTIC;
        }

        if (fromLlm == DocVecQueryType.SEMANTIC && certificateTypeId > 0) {
            return DocVecQueryType.CERTIFICATE_HOLDER_LIST;
        }
        if (fromLlm == DocVecQueryType.SEMANTIC && !companyNameHint.isBlank()) {
            return DocVecQueryType.COMPANY_DETAIL;
        }
        if (fromLlm == DocVecQueryType.PERSON_ROLE_REGION_FILTER
                || (followUp && !regionKeyword.isBlank() && !personName.isBlank())) {
            return DocVecQueryType.PERSON_ROLE_REGION_FILTER;
        }
        if (fromLlm == DocVecQueryType.PRIOR_LIST_REGION_FILTER
                || (followUp && !regionKeyword.isBlank() && session != null
                && !session.priorCompanyIds().isEmpty() && personName.isBlank())) {
            return DocVecQueryType.PRIOR_LIST_REGION_FILTER;
        }
        if (fromLlm == DocVecQueryType.CERTIFICATE_HOLDER_LIST || certificateTypeId > 0) {
            return DocVecQueryType.CERTIFICATE_HOLDER_LIST;
        }
        if (fromLlm == DocVecQueryType.COMPANY_COUNT
                || (!operatingStatus.isBlank() && questionLooksLikeCount(operatingStatus))) {
            return DocVecQueryType.COMPANY_COUNT;
        }
        if (fromLlm == DocVecQueryType.PERSON_ROLE_LIST
                || (!personName.isBlank() && !roleLabel.isBlank())) {
            return DocVecQueryType.PERSON_ROLE_LIST;
        }
        if (fromLlm == DocVecQueryType.REGION_COMPANY_LIST || !regionKeyword.isBlank()) {
            return DocVecQueryType.REGION_COMPANY_LIST;
        }
        return fromLlm;
    }

    private static boolean questionLooksLikeCount(String operatingStatus) {
        return operatingStatus != null && !operatingStatus.isBlank();
    }

    private String buildUserPrompt(String question, DocVecSessionSnapshot session) {
        StringBuilder sb = new StringBuilder();
        if (session != null && session.followUp()) {
            sb.append("【多轮上下文】\n");
            sb.append("上一轮用户问：").append(nullToEmpty(session.priorQuestion())).append('\n');
            sb.append("上一轮助手答摘要：").append(nullToEmpty(session.priorAnswerSummary())).append('\n');
            sb.append("上一轮查询类型：").append(session.priorQueryType()).append('\n');
            if (!nullToEmpty(session.priorPersonName()).isBlank()) {
                sb.append("上一轮人物：").append(session.priorPersonName()).append('\n');
            }
            if (!nullToEmpty(session.priorRoleLabel()).isBlank()) {
                sb.append("上一轮角色：").append(session.priorRoleLabel()).append('\n');
            }
            if (!nullToEmpty(session.priorCertificateTypeName()).isBlank()) {
                sb.append("上一轮证照类型：").append(session.priorCertificateTypeName()).append('\n');
            }
            if (session.priorCompanyNames() != null && !session.priorCompanyNames().isEmpty()) {
                int n = Math.min(session.priorCompanyNames().size(), 30);
                sb.append("上一轮结果主体（前").append(n).append("家）：")
                        .append(String.join("、", session.priorCompanyNames().subList(0, n)));
                if (session.priorCompanyNames().size() > n) {
                    sb.append("…等共").append(session.priorCompanyNames().size()).append("家");
                }
                sb.append('\n');
            }
            sb.append("说明：当前为追问，请解析「其中/这些/上面」等指代，结合上文选择 queryType 与槽位。\n\n");
        }
        sb.append("【当前问题】\n").append(question);
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private JsonNode parseJsonNode(String content) throws Exception {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("{")) {
            return objectMapper.readTree(trimmed);
        }
        Matcher matcher = JSON_OBJECT.matcher(trimmed);
        if (matcher.find()) {
            return objectMapper.readTree(matcher.group());
        }
        throw new IllegalStateException("No JSON object in docvec route content");
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String loadSystemPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("qa/docvec/route-system-prompt.txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return DEFAULT_SYSTEM_PROMPT;
        }
    }

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 DocVec 实验问答的路由器。只能在两种检索模式中选择，并输出 JSON（不要 markdown）。
            模式：
            - sql：全量列表、统计、按证照类型枚举主体、人员任职列举、地区过滤（含多轮在上文结果上筛选）
            - rag：单一或少数公司档案细节（证照明细、法人、股东、印章等语义问答）
            queryType 取值：
            person_role_list, person_role_region_filter, region_company_list, company_count,
            certificate_holder_list, prior_list_region_filter, company_detail, semantic
            输出字段：mode, queryType, personName, roleLabel, regionKeyword, operatingStatusFilter,
            certificateTypeName, companyNameHint, reason, confidence
            """;
}
