package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.domain.EntityRef;
import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.qa.domain.ScenarioRuleEngine;
import com.qa.demo.qa.domain.PersonAliasIdentityParser;
import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.retrieval.catalog.CatalogEvidenceRetriever;
import com.qa.demo.qa.retrieval.catalog.NeedInferenceService;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogConfig;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogRegistry;
import com.qa.demo.qa.retrieval.personcert.PersonCertificateQueryService;
import com.qa.demo.qa.retrieval.RetrievalGapLlmAdvisor.GapDecision;
import com.qa.demo.qa.retrieval.sql.SqlCompanyFacetEnricher;
import com.qa.demo.qa.retrieval.sql.SqlPersonRoleDetailEnricher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按意图执行多路检索、融合与主动学习片段合并。
 * <p>
 * 企业 scope 且开启统一召回时走 {@link #retrieveUnifiedEnterprise}（并行图/向量/MySQL/SQL + 重排）；
 * 否则按 {@link IntentDecision#intent()} 选择单路或混合召回。
 */
@Service
public class QaRetrievalPipeline {

    private final GraphContextService graphContextService;
    private final VectorContextService vectorContextService;
    private final MysqlContextService mysqlContextService;
    private final SqlQueryService sqlQueryService;
    private final DocumentContextService documentContextService;
    private final ActiveLearningService activeLearningService;
    private final QaAssistantProperties properties;
    private final EntityTableMapper entityTableMapper;
    private final EmployeeBaseKnowledgeService employeeBaseKnowledge;
    private final EvidenceRerankService evidenceRerankService;
    private final RetrievalPlanFactory retrievalPlanFactory;
    private final SqlTopKResolver sqlTopKResolver;
    private final ScenarioRuleEngine ruleEngine;
    private final PersonCertificateQueryService personCertificateQueryService;
    private final NeedInferenceService needInferenceService;
    private final RetrievalCatalogRegistry catalogRegistry;
    private final CatalogEvidenceRetriever catalogEvidenceRetriever;
    private final SqlPersonRoleDetailEnricher personRoleDetailEnricher;
    private final SqlCompanyFacetEnricher companyFacetEnricher;
    private final RetrievalGapLlmAdvisor gapLlmAdvisor;

    public QaRetrievalPipeline(
            GraphContextService graphContextService,
            VectorContextService vectorContextService,
            MysqlContextService mysqlContextService,
            SqlQueryService sqlQueryService,
            DocumentContextService documentContextService,
            ActiveLearningService activeLearningService,
            QaAssistantProperties properties,
            EntityTableMapper entityTableMapper,
            EmployeeBaseKnowledgeService employeeBaseKnowledge,
            EvidenceRerankService evidenceRerankService,
            RetrievalPlanFactory retrievalPlanFactory,
            SqlTopKResolver sqlTopKResolver,
            ScenarioRuleEngine ruleEngine,
            PersonCertificateQueryService personCertificateQueryService,
            NeedInferenceService needInferenceService,
            RetrievalCatalogRegistry catalogRegistry,
            CatalogEvidenceRetriever catalogEvidenceRetriever,
            SqlPersonRoleDetailEnricher personRoleDetailEnricher,
            SqlCompanyFacetEnricher companyFacetEnricher,
            RetrievalGapLlmAdvisor gapLlmAdvisor
    ) {
        this.graphContextService = graphContextService;
        this.vectorContextService = vectorContextService;
        this.mysqlContextService = mysqlContextService;
        this.sqlQueryService = sqlQueryService;
        this.documentContextService = documentContextService;
        this.activeLearningService = activeLearningService;
        this.properties = properties;
        this.entityTableMapper = entityTableMapper;
        this.employeeBaseKnowledge = employeeBaseKnowledge;
        this.evidenceRerankService = evidenceRerankService;
        this.retrievalPlanFactory = retrievalPlanFactory;
        this.sqlTopKResolver = sqlTopKResolver;
        this.ruleEngine = ruleEngine;
        this.personCertificateQueryService = personCertificateQueryService;
        this.needInferenceService = needInferenceService;
        this.catalogRegistry = catalogRegistry;
        this.catalogEvidenceRetriever = catalogEvidenceRetriever;
        this.personRoleDetailEnricher = personRoleDetailEnricher;
        this.companyFacetEnricher = companyFacetEnricher;
        this.gapLlmAdvisor = gapLlmAdvisor;
    }

    public record RetrievalResult(String retrievalSource, List<ContextChunk> evidence) {
    }

    /**
     * P0：企业问答统一召回（图 + 向量 + MySQL + SQL + 主动学习）后重排截断。
     */
    public RetrievalResult retrieveUnifiedEnterprise(
            String question,
            List<ContextChunk> learned,
            IntentDecision intent
    ) throws IOException {
        InformationNeed need = needInferenceService.infer(question, intent);
        return retrieveUnifiedEnterprise(question, learned, intent, need);
    }

    public RetrievalResult retrieveUnifiedEnterprise(
            String question,
            List<ContextChunk> learned,
            IntentDecision intent,
            InformationNeed need
    ) throws IOException {
        RetrievalPlan plan = retrievalPlanFactory.from(intent);
        List<ContextChunk> merged = collectHybridCandidatesExpanded(question, plan, need);
        merged = mergeLearnedUnconditionally(learned, merged);
        RetrievalResult base = new RetrievalResult("unified_hybrid", merged);
        base = appendSupplementalTables(base, question, plan);
        base = appendEmployeeBaseInfo(base, question, plan);
        base = appendPersonIdentityEvidence(base, question, intent);
        if (need == null || !need.isTypeCatalog()) {
            base = appendPersonCertificateIfNeeded(base, plan, question);
        }
        base = appendCatalogEvidence(base, need);

        String queryType = intent != null ? intent.queryType() : "";
        base = applyConfigDrivenTruncation(base, queryType);

        if (need != null && need.isTypeCatalog() && hasEvidenceSchema(base.evidence(), "catalog_v1")) {
            return trimTypeCatalogEvidence(base.evidence(), plan.finalEvidenceTopK());
        }
        if (plan.personCertificateList() && (need == null || !need.isTypeCatalog())) {
            return new RetrievalResult("unified_person_certificate", base.evidence());
        }
        if (plan.personRoleList()) {
            return trimEvidence(base.evidence(), plan.finalEvidenceTopK(), "unified_person_role");
        }

        List<ContextChunk> forRerank = applyCorrectionNarrow(question, base.evidence(), "company");
        List<ContextChunk> reranked = evidenceRerankService.rerank(
                question, forRerank, plan.finalEvidenceTopK());
        String source = "unified_rerank_" + evidenceRerankService.activeProvider();
        return new RetrievalResult(source, reranked);
    }

    private RetrievalResult appendCatalogEvidence(RetrievalResult base, InformationNeed need) {
        if (base == null || need == null) {
            return base;
        }
        List<RetrievalCatalogConfig.DimensionDef> dimensions = catalogRegistry.matchDimensions(need);
        if (dimensions.isEmpty()) {
            return base;
        }
        List<ContextChunk> catalog = catalogEvidenceRetriever.retrieve(dimensions);
        if (catalog.isEmpty()) {
            return base;
        }
        List<ContextChunk> merged = new ArrayList<>(base.evidence());
        appendUnique(merged, catalog);
        String source = base.retrievalSource() == null ? "catalog" : base.retrievalSource() + "+catalog";
        return new RetrievalResult(source, merged);
    }

    private static boolean hasEvidenceSchema(List<ContextChunk> evidence, String schemaId) {
        if (evidence == null || schemaId == null || schemaId.isBlank()) {
            return false;
        }
        return evidence.stream().anyMatch(c -> c != null && schemaId.equals(c.evidenceSchema()));
    }

    private RetrievalResult applyConfigDrivenTruncation(RetrievalResult base, String queryType) {
        if ("person_role_list".equalsIgnoreCase(queryType)
                || "person_certificate_list".equalsIgnoreCase(queryType)) {
            return base;
        }
        List<ContextChunk> evidence = base.evidence();
        int topK = 20; // default

        // 按数据源检查是否需要截断
        java.util.Map<String, Long> sourceCounts = new java.util.HashMap<>();
        for (ContextChunk c : evidence) {
            if (c != null && c.source() != null) {
                sourceCounts.merge(c.source(), 1L, Long::sum);
            }
        }

        for (java.util.Map.Entry<String, Long> entry : sourceCounts.entrySet()) {
            String source = entry.getKey();
            long count = entry.getValue();
            if (ruleEngine.shouldTruncate(source, queryType, count)) {
                int threshold = ruleEngine.getTruncationThreshold(source, queryType);
                if (evidence.size() > threshold) {
                    evidence = new ArrayList<>(evidence.subList(0, (int) threshold));
                    return new RetrievalResult("truncated_" + base.retrievalSource(), evidence);
                }
            }
        }
        return base;
    }

    public RetrievalResult retrieveByIntent(String intent, String question) throws IOException {
        IntentDecision decision = new IntentDecision(intent, 0.5, "legacy_string_intent_only");
        return retrieveByIntent(decision, question);
    }

    public RetrievalResult retrieveByIntent(IntentDecision intent, String question) throws IOException {
        RetrievalPlan plan = retrievalPlanFactory.from(intent);
        String normalized = intent.intent() == null ? "" : intent.intent().toLowerCase();
        RetrievalResult base = switch (normalized) {
            case "graph" -> retrieveGraphFirst(question, plan);
            case "vector" -> retrieveVectorFirst(question);
            case "document" -> retrieveDocumentFirst(question);
            case "mysql" -> retrieveMysqlFirst(question);
            case "sql" -> retrieveSqlFirst(question);
            case "hybrid" -> retrieveHybrid(question, plan);
            case "unknown" -> new RetrievalResult("unknown", List.of());
            default -> retrieveHybrid(question, plan);
        };
        RetrievalResult withPersonCert = appendPersonCertificateIfNeeded(base, plan, question);
        RetrievalResult withSupplemental = appendSupplementalTables(withPersonCert, question, plan);
        RetrievalResult withEmployee = appendEmployeeBaseInfo(withSupplemental, question, plan);
        RetrievalResult withIdentity = appendPersonIdentityEvidence(withEmployee, question, intent);
        InformationNeed need = needInferenceService.infer(question, intent);
        RetrievalResult withCatalog = appendCatalogEvidence(withIdentity, need);
        List<ContextChunk> narrowed = applyCorrectionNarrow(question, withCatalog.evidence(), "company");
        if (narrowed == withCatalog.evidence()) {
            if (need.isTypeCatalog() && hasEvidenceSchema(withCatalog.evidence(), "catalog_v1")) {
                return new RetrievalResult("type_catalog_" + withCatalog.retrievalSource(), withCatalog.evidence());
            }
            return withCatalog;
        }
        return new RetrievalResult(withCatalog.retrievalSource() + "+correction_narrow", narrowed);
    }

    /**
     * 配置化的纠偏收窄。
     */
    private List<ContextChunk> applyCorrectionNarrow(String question, List<ContextChunk> evidence, String entityType) {
        if (evidence == null || evidence.isEmpty()) {
            return evidence;
        }
        if (!ruleEngine.isCorrectionQuestion(question)) {
            return evidence;
        }
        if (ruleEngine.asksRelatedEntities(question, entityType)) {
            return evidence;
        }
        String target = ruleEngine.extractCorrectedEntityName(question, entityType);
        if (target == null || target.isBlank()) {
            return evidence;
        }
        if (target.contains("分公司")) {
            return evidence;
        }
        List<ContextChunk> exact = new ArrayList<>();
        for (ContextChunk chunk : evidence) {
            if (chunk == null || !ContextChunk.KIND_COMPANY.equals(chunk.entityKind())) {
                continue;
            }
            String label = canonicalCompanyLabel(chunk.displayLabel());
            if (label.equals(target) || normalizeSpaces(label).equals(normalizeSpaces(target))) {
                exact.add(chunk);
            }
        }
        return exact.isEmpty() ? evidence : exact;
    }

    private static String canonicalCompanyLabel(String displayLabel) {
        if (displayLabel == null || displayLabel.isBlank()) {
            return "";
        }
        String s = displayLabel.trim();
        int idIdx = s.indexOf("（ID ");
        if (idIdx > 0) {
            s = s.substring(0, idIdx).trim();
        }
        int asciiIdIdx = s.indexOf("(ID ");
        if (asciiIdIdx > 0) {
            s = s.substring(0, asciiIdIdx).trim();
        }
        return s;
    }

    private static String normalizeSpaces(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 追加检测到的 supplemental tables 查询结果。
     */
    private RetrievalResult appendSupplementalTables(RetrievalResult base, String question, RetrievalPlan plan) {
        List<String> supplementalTables = entityTableMapper.getSupplementalTablesForQuestion(question);
        if (supplementalTables.isEmpty()) {
            return base;
        }
        List<ContextChunk> merged = new ArrayList<>(base.evidence());
        Set<String> seen = new HashSet<>();
        for (ContextChunk c : base.evidence()) {
            seen.add(c.source() + "|" + c.anchorId());
        }
        int supplementalLimit = Math.max(1, properties.getMysqlTopK());
        for (String table : supplementalTables) {
            List<ContextChunk> supplemental = mysqlContextService.querySupplementalTable(table, question, supplementalLimit);
            for (ContextChunk c : supplemental) {
                String key = c.source() + "|" + c.anchorId();
                if (seen.add(key)) {
                    merged.add(c);
                }
            }
        }
        return trimEvidence(merged, plan.finalEvidenceTopK(), "supplemental_appended_" + base.retrievalSource());
    }

    /**
     * 检测问题中的人名/花名，追加员工基础信息到检索结果。
     */
    private RetrievalResult appendEmployeeBaseInfo(RetrievalResult base, String question, RetrievalPlan plan) {
        if (plan.skipEmployeeBaseAppend()) {
            return base;
        }
        if (employeeBaseKnowledge.size() == 0) {
            return base;
        }
        // 简单提取问题中的中文词（假设人名是2-4个汉字）
        List<String> potentialNames = extractPotentialNames(question);
        if (potentialNames.isEmpty()) {
            return base;
        }
        List<ContextChunk> merged = new ArrayList<>(base.evidence());
        Set<String> seen = new HashSet<>();
        for (ContextChunk c : base.evidence()) {
            seen.add(c.source() + "|" + c.anchorId());
        }
        for (String name : potentialNames) {
            Integer employeeId = employeeBaseKnowledge.resolveToEmployeeId(name);
            if (employeeId == null) {
                continue;
            }
            EmployeeBaseKnowledgeService.EmployeeRecord record = employeeBaseKnowledge.getEmployeeById(employeeId);
            if (record == null) {
                continue;
            }
            String snippet = employeeBaseKnowledge.formatIdentityEvidence(record);
            if (snippet.isBlank()) {
                continue;
            }
            ContextChunk chunk = ContextChunk.ofEmployee(
                    String.valueOf(employeeId),
                    record.name() != null ? record.name() : "",
                    "employee_base",
                    snippet,
                    10.0,
                    "employee_base",
                    "employee_identity_v1"
            );
            String key = chunk.source() + "|" + chunk.anchorId();
            if (seen.add(key)) {
                merged.add(chunk);
            }
        }
        return trimEvidence(merged, plan.finalEvidenceTopK(), "employee_base_appended_" + base.retrievalSource());
    }

    /**
     * 追加员工身份证据（姓名/花名对照），人物任职类检索也会执行，避免 skipEmployeeBaseAppend 时丢失别名依据。
     */
    private RetrievalResult appendPersonIdentityEvidence(
            RetrievalResult base,
            String question,
            IntentDecision intent
    ) {
        if (employeeBaseKnowledge.size() == 0) {
            return base;
        }
        Set<Integer> employeeIds = new LinkedHashSet<>();
        if (intent != null && intent.hasPersonEmployeeId()) {
            employeeIds.add(intent.personEmployeeId());
        }
        if (intent != null && intent.hasPersonFocus()) {
            Integer id = employeeBaseKnowledge.resolveToEmployeeId(intent.personName());
            if (id != null) {
                employeeIds.add(id);
            }
        }
        if (question != null && !question.isBlank()) {
            for (String token : PersonAliasIdentityParser.extractMentionedPersonTokens(question)) {
                Integer id = employeeBaseKnowledge.resolveToEmployeeId(token);
                if (id != null) {
                    employeeIds.add(id);
                }
            }
            for (String name : extractPotentialNames(question)) {
                Integer id = employeeBaseKnowledge.resolveToEmployeeId(name);
                if (id != null) {
                    employeeIds.add(id);
                }
            }
        }
        if (employeeIds.isEmpty()) {
            return base;
        }
        List<ContextChunk> merged = new ArrayList<>(base.evidence());
        Set<String> seen = new HashSet<>();
        for (ContextChunk c : base.evidence()) {
            seen.add(c.source() + "|" + c.anchorId());
        }
        for (Integer employeeId : employeeIds) {
            EmployeeBaseKnowledgeService.EmployeeRecord record = employeeBaseKnowledge.getEmployeeById(employeeId);
            if (record == null) {
                continue;
            }
            String snippet = employeeBaseKnowledge.formatIdentityEvidence(record);
            if (snippet.isBlank()) {
                continue;
            }
            ContextChunk chunk = ContextChunk.ofEmployee(
                    String.valueOf(employeeId),
                    record.name() != null ? record.name() : "",
                    "员工身份",
                    snippet,
                    18.0,
                    "employee_identity",
                    "employee_identity_v1"
            );
            String key = chunk.source() + "|" + chunk.anchorId();
            if (seen.add(key)) {
                merged.add(0, chunk);
            }
        }
        return new RetrievalResult("employee_identity_appended_" + base.retrievalSource(), merged);
    }

    private RetrievalResult trimTypeCatalogEvidence(List<ContextChunk> merged, int cap) {
        List<ContextChunk> catalog = new ArrayList<>();
        List<ContextChunk> other = new ArrayList<>();
        for (ContextChunk chunk : merged) {
            if (chunk != null && "catalog_v1".equals(chunk.evidenceSchema())) {
                catalog.add(chunk);
            } else if (chunk != null) {
                other.add(chunk);
            }
        }
        if (!catalog.isEmpty()) {
            return new RetrievalResult("unified_type_catalog", catalog);
        }
        int limit = Math.max(1, cap);
        if (other.size() > limit) {
            other = new ArrayList<>(other.subList(0, limit));
        }
        return new RetrievalResult("unified_type_catalog", other);
    }

    private RetrievalResult trimEvidence(List<ContextChunk> merged, int cap, String retrievalSource) {
        int limit = Math.max(1, cap);
        if (merged.size() > limit) {
            merged = new ArrayList<>(merged.subList(0, limit));
        }
        return new RetrievalResult(retrievalSource, merged);
    }

    private List<String> extractPotentialNames(String question) {
        List<String> names = new ArrayList<>();
        // 匹配2-4个连续汉字
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\u4e00-\u9fa5]{2,4}");
        java.util.regex.Matcher matcher = pattern.matcher(question);
        while (matcher.find()) {
            String name = matcher.group();
            // 排除常见通用词
            if (!isCommonWord(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private boolean isCommonWord(String word) {
        return Set.of("公司", "员工", "部门", "岗位", "制度", "流程", "规定", "管理", "请问", "我想", "帮我", "查询", "一下").contains(word);
    }

    public List<ContextChunk> safeActiveLearningRetrieve(String question, String scope) {
        try {
            return activeLearningService.retrieveTopChunks(
                    question,
                    Math.max(1, properties.getRetrievalTopK()),
                    scope
            );
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public boolean preferActiveLearning(String question, boolean explicitCompanyHint, List<ContextChunk> learned) {
        if (learned == null || learned.isEmpty()) {
            return false;
        }
        if (explicitCompanyHint) {
            return false;
        }
        if (question == null) {
            return false;
        }
        String q = question.trim();
        if (q.isBlank()) {
            return false;
        }
        String lower = q.toLowerCase();
        boolean shortQuestion = q.length() <= 14;
        boolean memoryLike = lower.contains("总部")
                || lower.contains("总公司")
                || lower.contains("是啥")
                || lower.contains("是什么")
                || lower.contains("还记得")
                || lower.contains("记得");
        double maxLearned = learned.stream().mapToDouble(ContextChunk::score).max().orElse(0);
        boolean strongLearnedHit = maxLearned >= 2.5;
        boolean hasCanonical = learned.stream()
                .anyMatch(c -> c != null && EnterpriseCanonicalFactsRegistry.SOURCE.equals(c.source()));
        return shortQuestion || memoryLike || strongLearnedHit || hasCanonical;
    }

    /**
     * 企业检索仍合并高置信主动学习命中，使别名等参与回答。
     */
    public RetrievalResult mergeEnterpriseActiveLearning(
            RetrievalResult base,
            List<ContextChunk> learned,
            boolean explicitCompanyHint
    ) {
        if (learned == null || learned.isEmpty()) {
            return base;
        }
        double maxLearned = learned.stream().mapToDouble(ContextChunk::score).max().orElse(0);
        double threshold = explicitCompanyHint ? 2.0 : 1.0;
        if (maxLearned < threshold) {
            return base;
        }
        List<ContextChunk> prefix = learned.stream()
                .filter(c -> c.score() >= threshold)
                .limit(4)
                .toList();
        if (prefix.isEmpty()) {
            return base;
        }
        Set<String> seen = new HashSet<>();
        List<ContextChunk> merged = new ArrayList<>();
        for (ContextChunk c : prefix) {
            String key = c.anchorId() + "|" + c.source();
            if (seen.add(key)) {
                merged.add(c);
            }
        }
        for (ContextChunk c : base.evidence()) {
            String key = c.anchorId() + "|" + c.source();
            if (seen.add(key)) {
                merged.add(c);
            }
        }
        int cap = Math.max(properties.getRetrievalTopK(), 8);
        if (merged.size() > cap) {
            merged = new ArrayList<>(merged.subList(0, cap));
        }
        return new RetrievalResult("active_learning_merged_" + base.retrievalSource(), merged);
    }

    public int resolveSqlTopK(String question) {
        return sqlTopKResolver.resolve(question, retrievalPlanFactory.from(null));
    }

    private RetrievalResult retrieveGraphFirst(String question) throws IOException {
        return retrieveGraphFirst(question, retrievalPlanFactory.from(null));
    }

    private RetrievalResult retrieveVectorFirst(String question) throws IOException {
        List<ContextChunk> vector = vectorContextService.retrieveTopChunks(question);
        if (!vector.isEmpty()) {
            return new RetrievalResult("vector", vector);
        }
        List<ContextChunk> graph = safeGraphRetrieve(question);
        if (!graph.isEmpty()) {
            return new RetrievalResult("graph_fallback_after_vector", graph);
        }
        List<ContextChunk> mysql = safeMysqlRetrieve(question);
        if (!mysql.isEmpty()) {
            return new RetrievalResult("mysql_fallback_after_vector", mysql);
        }
        List<ContextChunk> sql = safeSqlRetrieve(question);
        if (!sql.isEmpty()) {
            return new RetrievalResult("sql_fallback_after_vector", sql);
        }
        return new RetrievalResult("document_fallback_after_vector",
                documentContextService.retrieveTopChunks(question, properties.getDocsDir(), properties.getRetrievalTopK()));
    }

    private RetrievalResult retrieveDocumentFirst(String question) throws IOException {
        List<ContextChunk> document = documentContextService.retrieveTopChunks(
                question,
                properties.getDocsDir(),
                properties.getRetrievalTopK()
        );
        if (!document.isEmpty()) {
            return new RetrievalResult("document", document);
        }
        List<ContextChunk> graph = safeGraphRetrieve(question);
        if (!graph.isEmpty()) {
            return new RetrievalResult("graph_fallback_after_document", graph);
        }
        List<ContextChunk> vector = vectorContextService.retrieveTopChunks(question);
        if (!vector.isEmpty()) {
            return new RetrievalResult("vector_fallback_after_document", vector);
        }
        List<ContextChunk> mysql = safeMysqlRetrieve(question);
        if (!mysql.isEmpty()) {
            return new RetrievalResult("mysql_fallback_after_document", mysql);
        }
        return new RetrievalResult("sql_fallback_after_document", safeSqlRetrieve(question));
    }

    private RetrievalResult retrieveMysqlFirst(String question) throws IOException {
        List<ContextChunk> mysql = safeMysqlRetrieve(question);
        if (!mysql.isEmpty()) {
            return new RetrievalResult("mysql", mysql);
        }
        List<ContextChunk> graph = safeGraphRetrieve(question);
        if (!graph.isEmpty()) {
            return new RetrievalResult("graph_fallback_after_mysql", graph);
        }
        List<ContextChunk> vector = vectorContextService.retrieveTopChunks(question);
        if (!vector.isEmpty()) {
            return new RetrievalResult("vector_fallback_after_mysql", vector);
        }
        List<ContextChunk> sql = safeSqlRetrieve(question);
        if (!sql.isEmpty()) {
            return new RetrievalResult("sql_fallback_after_mysql", sql);
        }
        return new RetrievalResult("document_fallback_after_mysql",
                documentContextService.retrieveTopChunks(question, properties.getDocsDir(), properties.getRetrievalTopK()));
    }

    private RetrievalResult retrieveSqlFirst(String question) throws IOException {
        List<ContextChunk> sql = safeSqlRetrieve(question);
        if (!sql.isEmpty()) {
            return new RetrievalResult("sql", sql);
        }
        List<ContextChunk> mysql = safeMysqlRetrieve(question);
        if (!mysql.isEmpty()) {
            return new RetrievalResult("mysql_fallback_after_sql", mysql);
        }
        List<ContextChunk> graph = safeGraphRetrieve(question);
        if (!graph.isEmpty()) {
            return new RetrievalResult("graph_fallback_after_sql", graph);
        }
        List<ContextChunk> vector = vectorContextService.retrieveTopChunks(question);
        if (!vector.isEmpty()) {
            return new RetrievalResult("vector_fallback_after_sql", vector);
        }
        return new RetrievalResult("document_fallback_after_sql",
                documentContextService.retrieveTopChunks(question, properties.getDocsDir(), properties.getRetrievalTopK()));
    }

    private List<ContextChunk> mergeLearnedUnconditionally(List<ContextChunk> learned, List<ContextChunk> candidates) {
        List<ContextChunk> safeLearned = learned == null ? List.of() : learned;
        Set<String> seen = new HashSet<>();
        List<ContextChunk> merged = new ArrayList<>();
        for (ContextChunk c : safeLearned) {
            if (seen.add(dedupeKey(c))) {
                merged.add(c);
            }
        }
        for (ContextChunk c : candidates) {
            if (seen.add(dedupeKey(c))) {
                merged.add(c);
            }
        }
        int cap = Math.max(properties.getRerankCandidateMax(), properties.getRetrievalTopK());
        if (merged.size() > cap) {
            return new ArrayList<>(merged.subList(0, cap));
        }
        return merged;
    }

    private static String dedupeKey(ContextChunk c) {
        return c.source() + "|" + c.anchorId() + "|" + c.displayLabel() + "|" + c.field();
    }

    private List<ContextChunk> collectHybridCandidatesExpanded(
            String question,
            RetrievalPlan plan,
            InformationNeed need
    ) throws IOException {
        List<ContextChunk> merged = new ArrayList<>();
        appendPersonCertificateEvidence(merged, plan, question);
        if (plan.personRoleList()) {
            List<ContextChunk> boundaries = safeGraphRetrieve(question, plan);
            appendUnique(merged, boundaries);
            if (properties.isPersonRoleGraphPrimary()) {
                if (!boundaries.isEmpty()) {
                    appendUnique(merged, personRoleDetailEnricher.enrichFromBoundaries(
                            question, plan, boundaries, plan.finalEvidenceTopK()));
                } else {
                    appendUnique(merged, safeSqlRetrieve(question, plan));
                }
            } else {
                appendUnique(merged, safeSqlRetrieve(question, plan));
            }
            appendCompanyFacetEvidenceIfNeeded(merged, question, need, boundaries, plan.finalEvidenceTopK());
            return merged;
        }
        List<ContextChunk> graph = safeGraphRetrieve(question, plan);
        appendUnique(merged, graph);
        if (plan.preferGraphOnly() && !graph.isEmpty()) {
            return merged;
        }
        appendUnique(merged, vectorContextService.retrieveTopChunks(question, properties.getRecallVectorTopK()));
        appendUnique(merged, safeMysqlRetrieve(question));
        appendUnique(merged, safeSqlRetrieve(question, plan));
        if (shouldIncludeCompiledDocs(plan, question)) {
            appendUnique(merged, safeDocumentRetrieve(question));
        } else if (merged.isEmpty()) {
            return safeDocumentRetrieve(question);
        }
        return merged;
    }

    /**
     * 通用缺口补检索：当任职边界证据缺少用户问题需要的公司维度时，按 companyId 回补业务库字段。
     */
    private void appendCompanyFacetEvidenceIfNeeded(
            List<ContextChunk> merged,
            String question,
            InformationNeed need,
            List<ContextChunk> boundaries,
            int topK
    ) {
        if (!requiresCompanyFacetSupplement(question, need, merged, boundaries)) {
            return;
        }
        appendUnique(merged, companyFacetEnricher.enrichFromBoundaries(question, boundaries, topK));
    }

    private boolean requiresCompanyFacetSupplement(
            String question,
            InformationNeed need,
            List<ContextChunk> evidence,
            List<ContextChunk> boundaries
    ) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean asksStatus = containsAny(q, "状态", "经营状态", "存续", "在业", "注销", "吊销", "撤销", "非存续");
        boolean asksValidity = containsAny(q, "有效期", "有效时间", "到期", "截止", "失效", "起止", "开始", "结束");
        boolean facetNotRole = need != null && need.hasFacet() && !"role".equalsIgnoreCase(need.facet());

        boolean hasStatus = hasSnippetKeyword(evidence, "状态=", "status=");
        boolean hasValidity = hasSnippetKeyword(
                evidence, "有效期", "expiry", "expire", "deadline", "valid_from", "valid_to", "start", "end");

        if (asksStatus && !hasStatus) {
            return true;
        }
        if (asksValidity && !hasValidity) {
            return true;
        }
        if (facetNotRole && !(hasStatus || hasValidity)) {
            return true;
        }
        if (shouldAskLlmGapJudge(evidence, boundaries, hasStatus, hasValidity)) {
            GapDecision llmDecision = gapLlmAdvisor.decideCompanyFacetGap(question, evidence);
            if (llmDecision.confidence() >= 0.6) {
                if (llmDecision.needStatus() && !hasStatus) {
                    return true;
                }
                if (llmDecision.needValidity() && !hasValidity) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasSnippetKeyword(List<ContextChunk> evidence, String... keywords) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        for (ContextChunk chunk : evidence) {
            if (chunk == null || chunk.snippet() == null) {
                continue;
            }
            String snippet = chunk.snippet().toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (snippet.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean shouldAskLlmGapJudge(
            List<ContextChunk> evidence,
            List<ContextChunk> boundaries,
            boolean hasStatus,
            boolean hasValidity
    ) {
        if ((hasStatus && hasValidity) || evidence == null || evidence.isEmpty()) {
            return false;
        }
        int boundaryCount = 0;
        for (ContextChunk chunk : boundaries == null ? List.<ContextChunk>of() : boundaries) {
            if (chunk == null || chunk.source() == null) {
                continue;
            }
            if (chunk.source().toLowerCase(Locale.ROOT).contains("neo4j-boundary")) {
                boundaryCount++;
            }
        }
        int total = boundaries == null ? 0 : boundaries.size();
        return total > 0 && boundaryCount >= Math.max(1, total / 2);
    }

    private static boolean containsAny(String text, String... markers) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private void appendPersonCertificateEvidence(List<ContextChunk> merged, RetrievalPlan plan, String question) {
        IntentDecision intent = plan.intent();
        if (intent == null || !plan.personCertificateList()) {
            return;
        }
        int maxRows = resolvePersonCertificateMaxRows(intent);
        boolean activeCompaniesOnly = question != null && question.contains("存续");
        if (intent.hasCompanyHints()) {
            appendUnique(merged, personCertificateQueryService.retrieveByCompanyNames(
                    intent.companyHints(),
                    activeCompaniesOnly,
                    maxRows
            ));
        }
        if (intent.hasPersonFocus()) {
            appendUnique(merged, personCertificateQueryService.retrieve(
                    intent.personEmployeeId(),
                    intent.personName(),
                    maxRows
            ));
        }
    }

    private int resolvePersonCertificateMaxRows(IntentDecision intent) {
        int base = Math.max(properties.getRecallGraphTopK() * 8, 64);
        if (intent != null && intent.hasCompanyHints()) {
            return Math.min(Math.max(base, intent.companyHints().size() * 12), 256);
        }
        return base;
    }

    private RetrievalResult appendPersonCertificateIfNeeded(
            RetrievalResult base,
            RetrievalPlan plan,
            String question
    ) {
        if (!plan.personCertificateList()) {
            return base;
        }
        List<ContextChunk> merged = new ArrayList<>(base.evidence());
        appendPersonCertificateEvidence(merged, plan, question);
        long personCertRows = merged.stream()
                .filter(c -> c != null && isStructuredCertificateSource(c.source()))
                .count();
        if (personCertRows >= 1) {
            return trimEvidence(merged, plan.finalEvidenceTopK(), "person_certificate_" + base.retrievalSource());
        }
        if (merged.size() == base.evidence().size()) {
            return base;
        }
        return new RetrievalResult("person_certificate_partial_" + base.retrievalSource(), merged);
    }

    private static boolean isStructuredCertificateSource(String source) {
        return "mysql-person-certificate".equals(source) || "mysql-company-certificate".equals(source);
    }

    private static void appendUnique(List<ContextChunk> merged, List<ContextChunk> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ContextChunk c : merged) {
            seen.add(dedupeKey(c));
        }
        for (ContextChunk item : items) {
            if (seen.add(dedupeKey(item))) {
                merged.add(item);
            }
        }
    }

    private RetrievalResult retrieveGraphFirst(String question, RetrievalPlan plan) throws IOException {
        List<ContextChunk> graph = safeGraphRetrieve(question, plan);
        if (!graph.isEmpty()) {
            return new RetrievalResult("graph", graph);
        }
        List<ContextChunk> vector = vectorContextService.retrieveTopChunks(question);
        if (!vector.isEmpty()) {
            return new RetrievalResult("vector_fallback_after_graph", vector);
        }
        List<ContextChunk> mysql = safeMysqlRetrieve(question);
        if (!mysql.isEmpty()) {
            return new RetrievalResult("mysql_fallback_after_graph", mysql);
        }
        List<ContextChunk> sql = safeSqlRetrieve(question);
        if (!sql.isEmpty()) {
            return new RetrievalResult("sql_fallback_after_graph", sql);
        }
        return new RetrievalResult("document_fallback_after_graph",
                documentContextService.retrieveTopChunks(question, properties.getDocsDir(), properties.getRetrievalTopK()));
    }

    private RetrievalResult retrieveHybrid(String question) throws IOException {
        return retrieveHybrid(question, retrievalPlanFactory.from(null));
    }

    private RetrievalResult retrieveHybrid(String question, RetrievalPlan plan) throws IOException {
        List<ContextChunk> merged = new ArrayList<>();
        List<ContextChunk> graph = safeGraphRetrieve(question, plan);
        if (!graph.isEmpty()) {
            merged.addAll(graph);
        }
        List<ContextChunk> vector = vectorContextService.retrieveTopChunks(question);
        if (!vector.isEmpty()) {
            for (ContextChunk item : vector) {
                boolean exists = merged.stream().anyMatch(
                        x -> x.anchorId().equals(item.anchorId()) && x.source().equals(item.source())
                );
                if (!exists) {
                    merged.add(item);
                }
            }
        }
        List<ContextChunk> mysql = safeMysqlRetrieve(question);
        if (!mysql.isEmpty()) {
            for (ContextChunk item : mysql) {
                boolean exists = merged.stream().anyMatch(
                        x -> x.anchorId().equals(item.anchorId()) && x.source().equals(item.source())
                );
                if (!exists) {
                    merged.add(item);
                }
            }
        }
        List<ContextChunk> sql = safeSqlRetrieve(question);
        if (!sql.isEmpty()) {
            for (ContextChunk item : sql) {
                boolean exists = merged.stream().anyMatch(
                        x -> x.anchorId().equals(item.anchorId()) && x.source().equals(item.source())
                );
                if (!exists) {
                    merged.add(item);
                }
            }
        }
        if (merged.isEmpty()) {
            merged = documentContextService.retrieveTopChunks(
                    question,
                    properties.getDocsDir(),
                    properties.getRetrievalTopK()
            );
            return new RetrievalResult("document_fallback_after_hybrid", merged);
        }
        int limited = Math.min(merged.size(), Math.max(1, properties.getRetrievalTopK()));
        return new RetrievalResult("hybrid_graph_vector_mysql_sql", merged.subList(0, limited));
    }

    private List<ContextChunk> safeGraphRetrieve(String question) {
        return safeGraphRetrieve(question, retrievalPlanFactory.from(null));
    }

    private List<ContextChunk> safeGraphRetrieve(String question, RetrievalPlan plan) {
        try {
            return graphContextService.retrieveTopChunks(question, plan);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean shouldIncludeCompiledDocs(RetrievalPlan plan, String question) {
        // 配置化判断：证照/印章相关查询时包含文档
        if (plan.intent() != null && plan.intent().isPersonCertificateListQuery()) {
            return true;
        }
        if (plan.intent() != null && plan.intent().isCompanyComplianceQuery()) {
            return true;
        }
        if (question == null || question.isBlank()) {
            return false;
        }
        // 使用配置化的规则引擎判断
        return ruleEngine.isQueryType(question, null, "person_certificate_list") ||
               ruleEngine.isQueryType(question, null, "company_certificate") ||
               question.contains("证照") || question.contains("许可证") ||
               question.contains("备案") || question.contains("印章") || question.contains("公章");
    }

    private List<ContextChunk> safeDocumentRetrieve(String question) {
        try {
            return documentContextService.retrieveTopChunks(
                    question,
                    properties.getDocsDir(),
                    properties.getRetrievalTopK()
            );
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<ContextChunk> safeMysqlRetrieve(String question) {
        try {
            return mysqlContextService.retrieveTopChunks(question, properties.getMysqlTopK());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<ContextChunk> safeSqlRetrieve(String question) {
        return safeSqlRetrieve(question, retrievalPlanFactory.from(null));
    }

    private List<ContextChunk> safeSqlRetrieve(String question, RetrievalPlan plan) {
        try {
            return sqlQueryService.retrieveTopChunks(question, plan);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
