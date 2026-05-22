package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.learning.ActiveLearningService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按意图执行多路检索、融合与主动学习片段合并（原 {@code QaController} 内私有检索逻辑）。
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

    public QaRetrievalPipeline(
            GraphContextService graphContextService,
            VectorContextService vectorContextService,
            MysqlContextService mysqlContextService,
            SqlQueryService sqlQueryService,
            DocumentContextService documentContextService,
            ActiveLearningService activeLearningService,
            QaAssistantProperties properties,
            EntityTableMapper entityTableMapper,
            EmployeeBaseKnowledgeService employeeBaseKnowledge
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
    }

    public record RetrievalResult(String retrievalSource, List<ContextChunk> evidence) {
    }

    public RetrievalResult retrieveByIntent(String intent, String question) throws IOException {
        String normalized = intent == null ? "" : intent.toLowerCase();
        RetrievalResult base = switch (normalized) {
            case "graph" -> retrieveGraphFirst(question);
            case "vector" -> retrieveVectorFirst(question);
            case "document" -> retrieveDocumentFirst(question);
            case "mysql" -> retrieveMysqlFirst(question);
            case "sql" -> retrieveSqlFirst(question);
            case "hybrid" -> retrieveHybrid(question);
            case "unknown" -> new RetrievalResult("unknown", List.of());
            default -> retrieveHybrid(question);
        };
        RetrievalResult withSupplemental = appendSupplementalTables(base, question);
        return appendEmployeeBaseInfo(withSupplemental, question);
    }

    /**
     * 追加检测到的 supplemental tables 查询结果。
     */
    private RetrievalResult appendSupplementalTables(RetrievalResult base, String question) {
        List<String> supplementalTables = entityTableMapper.getSupplementalTablesForQuestion(question);
        if (supplementalTables.isEmpty()) {
            return base;
        }
        List<ContextChunk> merged = new ArrayList<>(base.evidence());
        Set<String> seen = new HashSet<>();
        for (ContextChunk c : base.evidence()) {
            seen.add(c.source() + "|" + c.companyId());
        }
        int supplementalLimit = Math.max(1, properties.getMysqlTopK());
        for (String table : supplementalTables) {
            List<ContextChunk> supplemental = mysqlContextService.querySupplementalTable(table, question, supplementalLimit);
            for (ContextChunk c : supplemental) {
                String key = c.source() + "|" + c.companyId();
                if (seen.add(key)) {
                    merged.add(c);
                }
            }
        }
        int cap = Math.max(properties.getRetrievalTopK(), 12);
        if (merged.size() > cap) {
            merged = new ArrayList<>(merged.subList(0, cap));
        }
        return new RetrievalResult("supplemental_appended_" + base.retrievalSource(), merged);
    }

    /**
     * 检测问题中的人名/花名，追加员工基础信息到检索结果。
     */
    private RetrievalResult appendEmployeeBaseInfo(RetrievalResult base, String question) {
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
            seen.add(c.source() + "|" + c.companyId());
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
            String snippet = String.format("员工ID=%d, 姓名=%s, 花名=%s",
                    record.id(),
                    record.name() != null ? record.name() : "",
                    record.anotherName() != null ? record.anotherName() : "");
            ContextChunk chunk = new ContextChunk(
                    String.valueOf(employeeId),
                    record.name() != null ? record.name() : "",
                    "employee_base",
                    snippet,
                    10.0,
                    "employee_base"
            );
            String key = chunk.source() + "|" + chunk.companyId();
            if (seen.add(key)) {
                merged.add(chunk);
            }
        }
        int cap = Math.max(properties.getRetrievalTopK(), 12);
        if (merged.size() > cap) {
            merged = new ArrayList<>(merged.subList(0, cap));
        }
        return new RetrievalResult("employee_base_appended_" + base.retrievalSource(), merged);
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
        return shortQuestion || memoryLike || strongLearnedHit;
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
            String key = c.companyId() + "|" + c.source();
            if (seen.add(key)) {
                merged.add(c);
            }
        }
        for (ContextChunk c : base.evidence()) {
            String key = c.companyId() + "|" + c.source();
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
        int base = Math.max(1, properties.getMysqlTopK());
        if (question == null || question.isBlank()) {
            return base;
        }
        String q = question.toLowerCase();
        boolean listStyle = q.contains("哪些")
                || q.contains("哪些公司")
                || q.contains("列表")
                || q.contains("所有")
                || q.contains("全部")
                || q.contains("有啥角色")
                || q.contains("什么角色")
                || q.contains("分别");
        if (listStyle) {
            return Math.max(base, 20);
        }
        boolean countStyle = q.contains("多少")
                || q.contains("统计")
                || q.contains("总数")
                || q.contains("count");
        if (countStyle) {
            return Math.max(base, 10);
        }
        return base;
    }

    private RetrievalResult retrieveGraphFirst(String question) throws IOException {
        List<ContextChunk> graph = safeGraphRetrieve(question);
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

    private RetrievalResult retrieveHybrid(String question) throws IOException {
        List<ContextChunk> merged = new ArrayList<>();
        List<ContextChunk> graph = safeGraphRetrieve(question);
        if (!graph.isEmpty()) {
            merged.addAll(graph);
        }
        List<ContextChunk> vector = vectorContextService.retrieveTopChunks(question);
        if (!vector.isEmpty()) {
            for (ContextChunk item : vector) {
                boolean exists = merged.stream().anyMatch(
                        x -> x.companyId().equals(item.companyId()) && x.source().equals(item.source())
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
                        x -> x.companyId().equals(item.companyId()) && x.source().equals(item.source())
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
                        x -> x.companyId().equals(item.companyId()) && x.source().equals(item.source())
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
        try {
            return graphContextService.retrieveTopChunks(question, properties.getRetrievalTopK());
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
        try {
            return sqlQueryService.retrieveTopChunks(question, resolveSqlTopK(question));
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
