package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.constraint.ConstraintResolver;
import com.qa.demo.qa.constraint.ConstraintSet;
import com.qa.demo.qa.constraint.HardConstraintGate;
import com.qa.demo.qa.constraint.RegionMatcher;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalExecutionProfile;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import com.qa.demo.qa.domain.EntityRef;
import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.qa.domain.ScenarioRuleEngine;
import com.qa.demo.qa.domain.PersonAliasIdentityParser;
import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.retrieval.catalog.CatalogEvidenceRetriever;
import com.qa.demo.qa.retrieval.catalog.NeedInferenceService;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogConfig;
import com.qa.demo.qa.retrieval.catalog.RetrievalCatalogRegistry;
import com.qa.demo.qa.retrieval.filter.FilterFieldQuestionSupport;
import com.qa.demo.qa.retrieval.sql.AggregateCountQueryService;
import com.qa.demo.qa.retrieval.sql.DistinctColumnQueryService;
import com.qa.demo.qa.retrieval.sql.FilterThresholdQueryService;
import com.qa.demo.qa.core.RetrievalStrategy;
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
 * 企业 scope 且开启统一召回时走 {@link #retrieveUnifiedEnterprise}（并行向量/MySQL/SQL + 重排）；
 * 否则按 {@link IntentDecision#intent()} 选择单路或混合召回。
 */
@Service
public class QaRetrievalPipeline {

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
    private final NeedInferenceService needInferenceService;
    private final RetrievalCatalogRegistry catalogRegistry;
    private final CatalogEvidenceRetriever catalogEvidenceRetriever;
    private final ConversationScopeSupport scopeSupport;
    private final ConstraintResolver constraintResolver;
    private final HardConstraintGate hardConstraintGate;
    private final AggregateCountQueryService aggregateCountQueryService;
    private final FilterThresholdQueryService filterThresholdQueryService;
    private final DistinctColumnQueryService distinctColumnQueryService;

    public QaRetrievalPipeline(
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
            NeedInferenceService needInferenceService,
            RetrievalCatalogRegistry catalogRegistry,
            CatalogEvidenceRetriever catalogEvidenceRetriever,
            ConversationScopeSupport scopeSupport,
            ConstraintResolver constraintResolver,
            HardConstraintGate hardConstraintGate,
            AggregateCountQueryService aggregateCountQueryService,
            FilterThresholdQueryService filterThresholdQueryService,
            DistinctColumnQueryService distinctColumnQueryService
    ) {
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
        this.needInferenceService = needInferenceService;
        this.catalogRegistry = catalogRegistry;
        this.catalogEvidenceRetriever = catalogEvidenceRetriever;
        this.scopeSupport = scopeSupport;
        this.constraintResolver = constraintResolver;
        this.hardConstraintGate = hardConstraintGate;
        this.aggregateCountQueryService = aggregateCountQueryService;
        this.filterThresholdQueryService = filterThresholdQueryService;
        this.distinctColumnQueryService = distinctColumnQueryService;
    }

    public record RetrievalResult(String retrievalSource, List<ContextChunk> evidence, EvidenceTruncationMeta truncation) {
        public RetrievalResult(String retrievalSource, List<ContextChunk> evidence) {
            this(retrievalSource, evidence, null);
        }
    }

    /**
     * P0：企业问答统一召回（向量 + MySQL + SQL + 主动学习）后重排截断。
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
        return retrieveUnifiedEnterprise(question, learned, intent, need, ConstraintSet.empty());
    }

    /**
     * P0：企业问答统一召回（向量 + MySQL + SQL + 主动学习）后两道硬约束闸门 + 重排截断。
     * <p>
     * 闸门策略（D2/D4）：
     * <ul>
     *   <li>recall 阶段：每个召回源已带 region/office 过滤（Vector/Graph/...）</li>
     *   <li>Pre-gate (rerank 前)：先过滤 evidence 池；避免 rerank 排错边</li>
     *   <li>Rerank：在已过滤池里排序</li>
     *   <li>Post-gate (rerank 后)：rerank 可能因"语义命中"误塞违例，再丢一道</li>
     * </ul>
     */
    public RetrievalResult retrieveUnifiedEnterprise(
            String question,
            List<ContextChunk> learned,
            IntentDecision intent,
            InformationNeed need,
            ConstraintSet constraint
    ) throws IOException {
        return retrieveUnifiedEnterprise(question, learned, intent, need, constraint, null);
    }

    public RetrievalResult retrieveUnifiedEnterprise(
            String question,
            List<ContextChunk> learned,
            IntentDecision intent,
            InformationNeed need,
            ConstraintSet constraint,
            EvidencePresentationContext presentation
    ) throws IOException {
        RetrievalPlan plan = retrievalPlanFactory.from(intent, need, presentation);
        RetrievalStrategy strategy = intent != null ? intent.resolvedRetrievalStrategy() : RetrievalStrategy.UNKNOWN;

        if (strategy == RetrievalStrategy.AGGREGATE_COUNT
                || (need != null && need.isAggregate() && !FilterFieldQuestionSupport.isFilterThresholdNeed(need))) {
            List<ContextChunk> countEvidence = aggregateCountQueryService.retrieve(question);
            if (!countEvidence.isEmpty()) {
                return new RetrievalResult("llm_aggregate_count", countEvidence);
            }
            return dedicatedMiss("aggregate_count");
        }

        if (need != null && FilterFieldQuestionSupport.isFilterThresholdNeed(need)) {
            List<ContextChunk> thresholdEvidence = filterThresholdQueryService.retrieve(
                    question, need, plan.finalEvidenceTopK());
            if (!thresholdEvidence.isEmpty()) {
                return new RetrievalResult("filter_threshold", thresholdEvidence);
            }
            return dedicatedMiss("filter_threshold");
        }

        if (strategy == RetrievalStrategy.TYPE_CATALOG || (need != null && need.isTypeCatalog())) {
            RetrievalResult catalogOnly = retrieveTypeCatalogOnly(question, need, plan);
            if (catalogOnly != null) {
                return catalogOnly;
            }
            return dedicatedMiss("type_catalog");
        }

        RetrievalExecutionProfile execution = plan.execution();
        if (execution.dedicatedListPath() || execution.dedicatedCertificatePath()) {
            return retrieveDedicatedStructured(
                    question, intent, plan, need, constraint, execution);
        }

        List<ContextChunk> merged = collectHybridCandidatesExpanded(question, plan, need, constraint);
        merged = mergeLearnedUnconditionally(learned, merged);
        RetrievalResult base = new RetrievalResult("unified_hybrid", merged);
        base = appendSupplementalTables(base, question, plan);
        base = appendEmployeeBaseInfo(base, question, plan);
        base = appendPersonIdentityEvidence(base, question, intent);

        base = applyConfigDrivenTruncation(base, plan);

        // Pre-rerank 闸门：rerank 前再过一道硬约束（防"召回源过滤 + 主动学习合并"引入违例）
        List<ContextChunk> preGateKept = hardConstraintGate.apply(base.evidence(), constraint).kept();
        List<ContextChunk> forRerank = execution.shouldApplyCorrectionNarrow()
                ? applyCorrectionNarrow(question, preGateKept, execution.correctionEntityKind())
                : preGateKept;
        List<ContextChunk> reranked = evidenceRerankService.rerank(
                question, forRerank, plan.finalEvidenceTopK());
        // Post-rerank 闸门：rerank 后兜底，避免 rerank 把上海分公司塞进"北京"问句
        List<ContextChunk> postGateKept = hardConstraintGate.apply(reranked, constraint).kept();
        String source = "unified_constrained_rerank_" + evidenceRerankService.activeProvider();
        EvidenceTruncationMeta truncation = EvidenceTruncationMeta.of(
                forRerank.size(), postGateKept.size(), plan.finalEvidenceTopK());
        return new RetrievalResult(source, postGateKept, truncation);
    }

    /**
     * P3：type_catalog 专用通路 — schema 列匹配 + DISTINCT 查库；无法匹配列时回退 enum catalog。
     */
    private RetrievalResult retrieveTypeCatalogOnly(
            String question,
            InformationNeed need,
            RetrievalPlan plan
    ) {
        List<ContextChunk> distinct = distinctColumnQueryService.retrieve(question, plan.finalEvidenceTopK());
        if (!distinct.isEmpty()) {
            return trimTypeCatalogEvidence(distinct, plan.finalEvidenceTopK());
        }
        if (distinctColumnQueryService.resolveColumn(question).isPresent()) {
            return null;
        }
        List<RetrievalCatalogConfig.DimensionDef> dimensions = catalogRegistry.matchDimensions(need);
        if (dimensions.isEmpty()) {
            return null;
        }
        List<ContextChunk> catalog = catalogEvidenceRetriever.retrieve(dimensions);
        if (catalog.isEmpty()) {
            return null;
        }
        return trimTypeCatalogEvidence(catalog, plan.finalEvidenceTopK());
    }

    private static RetrievalResult dedicatedMiss(String reason) {
        return new RetrievalResult("dedicated_miss:" + reason, List.of());
    }

    private RetrievalResult retrieveDedicatedStructured(
            String question,
            IntentDecision intent,
            RetrievalPlan plan,
            InformationNeed need,
            ConstraintSet constraint,
            RetrievalExecutionProfile execution
    ) throws IOException {
        List<ContextChunk> merged = collectDedicatedCandidates(question, plan);
        if (merged.isEmpty()) {
            String miss = execution.dedicatedListPath() ? "dedicated_list" : "dedicated_certificate";
            return dedicatedMiss(miss);
        }
        RetrievalResult base = new RetrievalResult("dedicated_structured", merged);
        if (!execution.skipEmployeeBaseAppend()) {
            base = appendEmployeeBaseInfo(base, question, plan);
        }
        base = appendPersonIdentityEvidence(base, question, intent);
        base = applyConfigDrivenTruncation(base, plan);
        List<ContextChunk> kept = hardConstraintGate.apply(base.evidence(), constraint).kept();
        if (kept.isEmpty()) {
            String miss = execution.dedicatedListPath() ? "dedicated_list" : "dedicated_certificate";
            return dedicatedMiss(miss);
        }
        String label = execution.hasRouteLabel() ? execution.routeLabel() : "dedicated_structured";
        if (execution.skipTruncation()) {
            return new RetrievalResult(label, kept);
        }
        return trimEvidence(kept, plan.finalEvidenceTopK(), label);
    }

    private List<ContextChunk> collectDedicatedCandidates(String question, RetrievalPlan plan) throws IOException {
        List<ContextChunk> merged = new ArrayList<>();
        RetrievalExecutionProfile execution = plan.execution();
        appendUnique(merged, safeSqlRetrieve(question, plan));
        if (execution.includeCompiledDocs()) {
            appendUnique(merged, safeDocumentRetrieve(question));
        }
        return merged;
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

    private RetrievalResult applyConfigDrivenTruncation(RetrievalResult base, RetrievalPlan plan) {
        if (plan != null && plan.execution() != null && plan.execution().skipTruncation()) {
            return base;
        }
        String truncationKey = "";
        if (plan != null && plan.intent() != null) {
            truncationKey = plan.intent().resolvedRetrievalStrategy().token();
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
            if (ruleEngine.shouldTruncate(source, truncationKey, count)) {
                int threshold = ruleEngine.getTruncationThreshold(source, truncationKey);
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
        InformationNeed need = needInferenceService.infer(question, intent);
        RetrievalPlan plan = retrievalPlanFactory.from(intent, need);
        String normalized = intent.intent() == null ? "" : intent.intent().toLowerCase();
        RetrievalResult base = switch (normalized) {
            case "graph", "hybrid" -> retrieveHybrid(question, plan);
            case "vector" -> retrieveVectorFirst(question);
            case "document" -> retrieveDocumentFirst(question);
            case "mysql" -> retrieveMysqlFirst(question);
            case "sql" -> retrieveSqlFirst(question);
            case "unknown" -> new RetrievalResult("unknown", List.of());
            default -> retrieveHybrid(question, plan);
        };
        RetrievalResult withSupplemental = appendSupplementalTables(base, question, plan);
        RetrievalResult withEmployee = appendEmployeeBaseInfo(withSupplemental, question, plan);
        RetrievalResult withIdentity = appendPersonIdentityEvidence(withEmployee, question, intent);
        RetrievalResult withCatalog = appendCatalogEvidence(withIdentity, need);
        List<ContextChunk> narrowed = plan.execution().shouldApplyCorrectionNarrow()
                ? applyCorrectionNarrow(question, withCatalog.evidence(), plan.execution().correctionEntityKind())
                : withCatalog.evidence();
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
        int before = merged.size();
        List<ContextChunk> out = merged;
        if (merged.size() > limit) {
            out = new ArrayList<>(merged.subList(0, limit));
        }
        EvidenceTruncationMeta truncation = EvidenceTruncationMeta.of(before, out.size(), limit);
        return new RetrievalResult(retrievalSource, out, truncation);
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

    private RetrievalResult retrieveVectorFirst(String question) throws IOException {
        List<ContextChunk> vector = vectorContextService.retrieveTopChunks(question);
        if (!vector.isEmpty()) {
            return new RetrievalResult("vector", vector);
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
        return collectHybridCandidatesExpanded(question, plan, need, ConstraintSet.empty());
    }

    /**
     * 约束感知混合召回：把 {@link ConstraintSet} 透传给向量召回源；其他源（MySQL/SQL/证书）
     * 由硬约束闸门在 rerank 前后两道闸门兜底（D2/D4 设计）。
     */
    private List<ContextChunk> collectHybridCandidatesExpanded(
            String question,
            RetrievalPlan plan,
            InformationNeed need,
            ConstraintSet constraint
    ) throws IOException {
        List<ContextChunk> merged = new ArrayList<>();
        RetrievalExecutionProfile execution = plan.execution();
        if (execution.dedicatedListPath() || execution.dedicatedCertificatePath()) {
            appendUnique(merged, safeSqlRetrieve(question, plan));
            if (execution.includeCompiledDocs()) {
                appendUnique(merged, safeDocumentRetrieve(question));
            }
            return merged;
        }
        // 向量召回带 region/office 过滤（D3："在北京" = registered OR office）
        appendUnique(merged, vectorContextService.retrieveTopChunks(
                question, properties.getRecallVectorTopK(), constraint));
        appendUnique(merged, safeMysqlRetrieve(question));
        appendUnique(merged, safeSqlRetrieve(question, plan));
        if (shouldIncludeCompiledDocs(plan, question)) {
            appendUnique(merged, safeDocumentRetrieve(question));
        }
        return merged;
    }

    private void appendHybridRecallFallback(
            String question,
            RetrievalPlan plan,
            InformationNeed need,
            ConstraintSet constraint,
            List<ContextChunk> merged
    ) throws IOException {
        appendUnique(merged, vectorContextService.retrieveTopChunks(
                question, properties.getRecallVectorTopK(), constraint));
        appendUnique(merged, safeMysqlRetrieve(question));
        appendUnique(merged, safeSqlRetrieve(question, plan));
        if (shouldIncludeCompiledDocs(plan, question)) {
            appendUnique(merged, safeDocumentRetrieve(question));
        }
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

    private RetrievalResult retrieveHybrid(String question) throws IOException {
        return retrieveHybrid(question, retrievalPlanFactory.from(null));
    }

    private RetrievalResult retrieveHybrid(String question, RetrievalPlan plan) throws IOException {
        List<ContextChunk> merged = new ArrayList<>();
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
        return new RetrievalResult("hybrid_vector_mysql_sql", merged.subList(0, limited));
    }

    private boolean shouldIncludeCompiledDocs(RetrievalPlan plan, String question) {
        if (plan.execution() != null && plan.execution().includeCompiledDocs()) {
            return true;
        }
        if (ruleEngine.questionSuggestsCompiledDocs(question)) {
            return true;
        }
        if (plan.intent() == null) {
            return false;
        }
        if (plan.need() != null && catalogRegistry.preferCompiledDocsForNeed(plan.need(), plan.intent())) {
            return true;
        }
        return false;
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

    /** P5：用户上传语料（user_uploads）始终作为补充证据源。 */
    private List<ContextChunk> safeUserDocumentRetrieve(String question) {
        try {
            return documentContextService.retrieveUserUploadTopChunks(question, 3);
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
