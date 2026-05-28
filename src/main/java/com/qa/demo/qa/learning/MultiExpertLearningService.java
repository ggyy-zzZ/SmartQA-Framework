package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.retrieval.MysqlContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * 多专家协作学习系统：模拟数据分析专家、系统架构师、学习策略专家共同协作完成数据库学习。
 *
 * 工作流程：
 * 1. DataAnalystExpert（数据分析专家）：分析表结构、业务含义、关联关系
 * 2. SystemArchitectExpert（系统架构师）：评估存储方案（MySQL/Qdrant/Neo4j）
 * 3. LearningPlannerExpert（学习策略专家）：综合决策并生成执行方案
 *
 * 支持在本地 assistant 数据库创建辅助表/索引以辅助学习过程
 */
@Service
public class MultiExpertLearningService {

    private static final Logger log = LoggerFactory.getLogger(MultiExpertLearningService.class);
    private static final String WORKSPACE_SCHEMA = "assistant";

    private final QaAssistantProperties properties;
    private final ActiveLearningService activeLearningService;
    private final MysqlSchemaCatalogService schemaCatalogService;
    private final SyncTrackingService syncTrackingService;

    public MultiExpertLearningService(
            QaAssistantProperties properties,
            ActiveLearningService activeLearningService,
            MysqlSchemaCatalogService schemaCatalogService,
            SyncTrackingService syncTrackingService
    ) {
        this.properties = properties;
        this.activeLearningService = activeLearningService;
        this.schemaCatalogService = schemaCatalogService;
        this.syncTrackingService = syncTrackingService;
    }

    /**
     * 多专家协作学习入口
     * @param dynamicConn 动态数据库连接（可以是业务库）
     * @param scope 学习范围（统一为企业知识库 enterprise）
     * @return 学习结果
     */
    public MultiExpertLearningResult learnWithMultiExperts(MysqlSchemaCatalogService.DynamicConnection dynamicConn, String scope) {
        String sessionId = UUID.randomUUID().toString().substring(0, 16);
        log.info("[ExpertSession-{}] 多专家学习开始", sessionId);

        List<ExpertOpinion> allOpinions = new ArrayList<>();
        ExpertConsensus consensus;

        try {
            // ========== 阶段1：数据分析专家 - 理解表结构与业务 ==========
            log.info("[ExpertSession-{}] 阶段1：数据分析专家 分析表结构...", sessionId);
            DataAnalysisResult dataAnalysis = dataAnalystExpert.analyze(dynamicConn, sessionId);
            allOpinions.addAll(dataAnalysis.opinions);

            // ========== 阶段2：系统架构师 - 评估存储方案 ==========
            log.info("[ExpertSession-{}] 阶段2：系统架构师 评估存储方案...", sessionId);
            StorageEvaluationResult storageEval = systemArchitectExpert.evaluate(dataAnalysis, dynamicConn, sessionId);
            allOpinions.addAll(storageEval.opinions);

            // ========== 阶段3：学习策略专家 - 综合决策 ==========
            log.info("[ExpertSession-{}] 阶段3：学习策略专家 综合决策...", sessionId);
            consensus = learningPlannerExpert.plan(dataAnalysis, storageEval, sessionId);

            // ========== 阶段4：执行学习 ==========
            log.info("[ExpertSession-{}] 阶段4：执行学习，共 {} 个任务", sessionId, consensus.learningTasks().size());
            executeLearning(consensus, scope, dynamicConn, sessionId);

            return new MultiExpertLearningResult(true, sessionId, dataAnalysis, storageEval, consensus, allOpinions, "学习完成");

        } catch (Exception e) {
            log.error("[ExpertSession-{}] 多专家学习失败: {}", sessionId, e.getMessage(), e);
            return new MultiExpertLearningResult(false, sessionId, null, null, null, allOpinions, e.getMessage());
        }
    }

    // ========== 专家实例 ==========

    private final DataAnalystExpert dataAnalystExpert = new DataAnalystExpert();
    private final SystemArchitectExpert systemArchitectExpert = new SystemArchitectExpert();
    private final LearningPlannerExpert learningPlannerExpert = new LearningPlannerExpert();

    // ========== 数据分析专家 ==========

    class DataAnalystExpert {
        private final List<String> BUSINESS_TERMS = List.of(
                "股东", "持股", "股权", "投资", "公司", "企业", "员工", "经理", "董事",
                "产品", "客户", "供应商", "合同", "订单", "收入", "利润"
        );

        DataAnalysisResult analyze(MysqlSchemaCatalogService.DynamicConnection conn, String sessionId) {
            List<ExpertOpinion> opinions = new ArrayList<>();
            List<TableProfile> tableProfiles = new ArrayList<>();
            List<MysqlSchemaCatalogService.TableRelationship> relationships = Collections.emptyList();

            try {
                List<String> tables = schemaCatalogService.listAllTablesWithConnection(conn);
                relationships = schemaCatalogService.getTableRelationshipsWithConnection(conn);

                StringBuilder analysis = new StringBuilder();
                analysis.append("# 数据分析专家报告\n\n");
                analysis.append("## 概览\n");
                analysis.append("- 分析表数量：").append(tables.size()).append("\n");
                analysis.append("- 检测到关联数：").append(relationships.size()).append("\n\n");

                for (String table : tables) {
                    TableProfile profile = analyzeTable(conn, table, relationships);
                    tableProfiles.add(profile);

                    analysis.append("### 表：").append(table).append("\n");
                    analysis.append("- 行数：").append(profile.rowCount()).append("\n");
                    analysis.append("- 列数：").append(profile.columnCount()).append("\n");
                    analysis.append("- 业务类型：").append(profile.businessType()).append("\n");
                    analysis.append("- 关键列：").append(String.join(", ", profile.keyColumns())).append("\n");
                    if (!profile.relatedTables().isEmpty()) {
                        analysis.append("- 关联表：").append(String.join(", ", profile.relatedTables())).append("\n");
                    }
                    if (!profile.businessMeaning().isBlank()) {
                        analysis.append("- 业务含义：").append(profile.businessMeaning()).append("\n");
                    }
                    analysis.append("\n");
                }

                analysis.append("## 表间关联图\n");
                Map<String, List<String>> tableRelations = new LinkedHashMap<>();
                for (MysqlSchemaCatalogService.TableRelationship rel : relationships) {
                    tableRelations.computeIfAbsent(rel.fromTable(), k -> new ArrayList<>())
                            .add(rel.toTable() + "." + rel.fromColumn());
                }
                for (var entry : tableRelations.entrySet()) {
                    analysis.append("- ").append(entry.getKey()).append(" → ").append(String.join(", ", entry.getValue())).append("\n");
                }

                opinions.add(new ExpertOpinion(
                        "DataAnalyst",
                        "数据分析专家",
                        ExpertRole.DATA_ANALYST,
                        analysis.toString(),
                        0.95,
                        Map.of("tableCount", tables.size(), "relationshipCount", relationships.size())
                ));

                log.info("[ExpertSession-{}] DataAnalyst: 分析了 {} 个表，发现 {} 个关联",
                        sessionId, tables.size(), relationships.size());

            } catch (Exception e) {
                log.error("[ExpertSession-{}] DataAnalyst 分析失败: {}", sessionId, e.getMessage());
                opinions.add(new ExpertOpinion(
                        "DataAnalyst",
                        "数据分析专家",
                        ExpertRole.DATA_ANALYST,
                        "分析失败: " + e.getMessage(),
                        0.0,
                        Map.of()
                ));
            }

            return new DataAnalysisResult(tableProfiles, relationships, opinions);
        }

        private TableProfile analyzeTable(MysqlSchemaCatalogService.DynamicConnection conn, String tableName,
                                           List<MysqlSchemaCatalogService.TableRelationship> allRelationships) {
            int rowCount = 0;
            int columnCount = 0;
            List<String> columns = new ArrayList<>();
            List<String> keyColumns = new ArrayList<>();
            String businessType = "未知";
            String businessMeaning = "";
            Set<String> relatedTables = new HashSet<>();

            try (Connection connection = DriverManager.getConnection(conn.toJdbcUrl(), conn.username(), conn.password())) {
                // 获取表样本数据
                List<Map<String, String>> sampleData = schemaCatalogService.readTableSampleData(connection, conn.database(), tableName, 10);
                columnCount = sampleData.isEmpty() ? 0 : sampleData.get(0).size();
                rowCount = estimateRowCount(connection, conn.database(), tableName);
                columns = sampleData.isEmpty() ? List.of() : new ArrayList<>(sampleData.get(0).keySet());

                // 检测关键列
                for (String col : columns) {
                    String lower = col.toLowerCase();
                    if (lower.contains("name") || lower.contains("名称") || lower.contains("标题")) {
                        keyColumns.add(col);
                    }
                    if (lower.contains("id") || lower.endsWith("_id")) {
                        keyColumns.add(col);
                    }
                }

                // 推断业务类型
                businessType = inferBusinessType(tableName, columns);

                // 找关联表
                for (MysqlSchemaCatalogService.TableRelationship rel : allRelationships) {
                    if (rel.fromTable().equals(tableName)) {
                        relatedTables.add(rel.toTable());
                    }
                    if (rel.toTable().equals(tableName)) {
                        relatedTables.add(rel.fromTable());
                    }
                }

                // 推断业务含义
                businessMeaning = inferBusinessMeaning(tableName, columns, sampleData);

            } catch (Exception e) {
                log.warn("Table analysis warning for {}: {}", tableName, e.getMessage());
            }

            return new TableProfile(tableName, rowCount, columnCount, columns, keyColumns,
                    businessType, businessMeaning, new ArrayList<>(relatedTables));
        }

        private int estimateRowCount(Connection connection, String schema, String table) throws SQLException {
            String sql = "SELECT COUNT(*) FROM `" + schema + "`.`" + table + "`";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;
        }

        private String inferBusinessType(String tableName, List<String> columns) {
            String lower = tableName.toLowerCase();
            for (String term : BUSINESS_TERMS) {
                if (lower.contains(term)) {
                    if (term.contains("股东") || term.contains("持股")) return "股东关系";
                    if (term.contains("产品")) return "产品信息";
                    if (term.contains("员工") || term.contains("经理")) return "人员组织";
                    if (term.contains("合同") || term.contains("订单")) return "业务交易";
                    if (term.contains("客户")) return "客户管理";
                }
            }
            return "通用数据";
        }

        private String inferBusinessMeaning(String tableName, List<String> columns, List<Map<String, String>> sampleData) {
            String lower = tableName.toLowerCase();
            if (lower.contains("shareholder") || lower.contains("股东")) {
                return "记录公司股东信息，包括股东名称、持股比例、出资额等";
            }
            if (lower.contains("employee") || lower.contains("员工")) {
                return "记录员工基本信息，包括姓名、职位、部门等";
            }
            if (sampleData.size() >= 3) {
                return "包含 " + columns.size() + " 个字段的 " + sampleData.size() + " 条样本数据的业务表";
            }
            return "";
        }
    }

    // ========== 系统架构师 ==========

    class SystemArchitectExpert {
        private final List<String> VECTOR_TRIGGER_COLUMNS = List.of("description", "desc", "简介", "描述", "content", "内容", "remark", "备注", "text");
        private final List<String> GRAPH_TRIGGER_COLUMNS = List.of("name", "名称", "title", "标题", "company", "公司");

        StorageEvaluationResult evaluate(DataAnalysisResult dataAnalysis, MysqlSchemaCatalogService.DynamicConnection conn, String sessionId) {
            List<ExpertOpinion> opinions = new ArrayList<>();
            List<TableStorageStrategy> strategies = new ArrayList<>();

            StringBuilder evaluation = new StringBuilder();
            evaluation.append("# 系统架构师评估报告\n\n");
            evaluation.append("## 存储方案评估\n\n");

            for (TableProfile profile : dataAnalysis.tableProfiles()) {
                TableStorageStrategy strategy = evaluateTableStorage(profile, dataAnalysis.relationships());
                strategies.add(strategy);

                evaluation.append("### ").append(profile.tableName()).append("\n");
                evaluation.append("- 推荐存储：").append(strategy.primaryStorage()).append("\n");
                evaluation.append("- 启用向量：").append(strategy.enableVector()).append("\n");
                evaluation.append("- 启用图谱：").append(strategy.enableGraph()).append("\n");
                evaluation.append("- 理由：").append(strategy.reason()).append("\n\n");
            }

            evaluation.append("## 总体建议\n");
            boolean anyGraph = strategies.stream().anyMatch(TableStorageStrategy::enableGraph);
            boolean anyVector = strategies.stream().anyMatch(TableStorageStrategy::enableVector);
            evaluation.append("- 需要图谱支持：").append(anyGraph).append("\n");
            evaluation.append("- 需要向量支持：").append(anyVector).append("\n");
            evaluation.append("- MySQL 必须启用：").append(true).append("\n");

            opinions.add(new ExpertOpinion(
                    "SystemArchitect",
                    "系统架构师",
                    ExpertRole.SYSTEM_ARCHITECT,
                    evaluation.toString(),
                    0.90,
                    Map.of("tableCount", strategies.size(), "graphEnabled", anyGraph, "vectorEnabled", anyVector)
            ));

            log.info("[ExpertSession-{}] SystemArchitect: 评估了 {} 个表，graph={}, vector={}",
                    sessionId, strategies.size(), anyGraph, anyVector);

            return new StorageEvaluationResult(strategies, opinions);
        }

        private TableStorageStrategy evaluateTableStorage(TableProfile profile, List<MysqlSchemaCatalogService.TableRelationship> relationships) {
            boolean enableVector = shouldEnableVector(profile);
            boolean enableGraph = shouldEnableGraph(profile, relationships);
            String primaryStorage = enableGraph ? "Neo4j + MySQL" : (enableVector ? "Qdrant + MySQL" : "MySQL only");
            String reason = buildStrategyReason(profile, enableVector, enableGraph);

            return new TableStorageStrategy(
                    profile.tableName(),
                    primaryStorage,
                    enableVector,
                    enableGraph,
                    enableVector ? 20 : 12, // keyword limit
                    LearningSinkPolicy.allEnabled(), // will be adjusted by planner
                    reason
            );
        }

        private boolean shouldEnableVector(TableProfile profile) {
            for (String col : profile.columns()) {
                String lower = col.toLowerCase();
                for (String trigger : VECTOR_TRIGGER_COLUMNS) {
                    if (lower.contains(trigger)) return true;
                }
            }
            // 文本类型列超过3个
            long textCols = profile.columns().stream()
                    .filter(c -> c.toLowerCase().contains("text") || c.toLowerCase().contains("desc") || c.toLowerCase().contains("content"))
                    .count();
            return textCols >= 2;
        }

        private boolean shouldEnableGraph(TableProfile profile, List<MysqlSchemaCatalogService.TableRelationship> relationships) {
            // 有外键关联的表适合图谱
            if (!profile.relatedTables().isEmpty()) return true;

            // 业务类型为关系型
            String bizType = profile.businessType();
            if (bizType.contains("股东") || bizType.contains("人员") || bizType.contains("组织")) {
                return true;
            }

            // 有 *_id 列的表
            long idCols = profile.columns().stream()
                    .filter(c -> c.toLowerCase().endsWith("_id"))
                    .count();
            return idCols >= 1 && profile.columnCount() <= 15;
        }

        private String buildStrategyReason(TableProfile profile, boolean enableVector, boolean enableGraph) {
            StringBuilder sb = new StringBuilder();
            sb.append(profile.businessType());
            if (enableGraph) {
                sb.append(", 涉及实体关联（Neo4j）");
            }
            if (enableVector) {
                sb.append(", 含文本检索需求（Qdrant）");
            }
            if (!enableGraph && !enableVector) {
                sb.append(", 结构化数据（MySQL only）");
            }
            return sb.toString();
        }
    }

    // ========== 学习策略专家 ==========

    class LearningPlannerExpert {
        ExpertConsensus plan(DataAnalysisResult dataAnalysis, StorageEvaluationResult storageEval, String sessionId) {
            List<ExpertOpinion> opinions = new ArrayList<>();
            List<LearningTask> tasks = new ArrayList<>();

            StringBuilder plan = new StringBuilder();
            plan.append("# 学习策略专家决策报告\n\n");
            plan.append("## 综合决策\n\n");

            Map<String, TableStorageStrategy> strategyMap = new LinkedHashMap<>();
            for (TableStorageStrategy s : storageEval.strategies()) {
                strategyMap.put(s.tableName(), s);
            }

            for (TableProfile profile : dataAnalysis.tableProfiles()) {
                TableStorageStrategy strategy = strategyMap.get(profile.tableName());
                LearningSinkPolicy sinkPolicy = buildSinkPolicy(strategy);
                LearningTask task = new LearningTask(
                        profile.tableName(),
                        profile.businessType(),
                        profile.businessMeaning(),
                        sinkPolicy,
                        profile.rowCount(),
                        profile.columns()
                );
                tasks.add(task);

                plan.append("### 任务：").append(profile.tableName()).append("\n");
                plan.append("- 类型：").append(strategy.primaryStorage()).append("\n");
                plan.append("- Sink策略：mysql=").append(sinkPolicy.mysql())
                        .append(", qdrant=").append(sinkPolicy.qdrant())
                        .append(", neo4j=").append(sinkPolicy.neo4j()).append("\n");
                plan.append("- 预期行数：").append(profile.rowCount()).append("\n\n");
            }

            // 跨表关联任务
            if (!dataAnalysis.relationships().isEmpty()) {
                LearningTask crossTableTask = new LearningTask(
                        "_cross_table_relationships",
                        "表间关联",
                        "跨表关联学习",
                        LearningSinkPolicy.allEnabled(),
                        dataAnalysis.relationships().size(),
                        List.of()
                );
                tasks.add(crossTableTask);

                plan.append("### 跨表关联任务\n");
                plan.append("- 关联数量：").append(dataAnalysis.relationships().size()).append("\n");
                plan.append("- 启用：Neo4j（图谱）+ Qdrant（向量）\n\n");
            }

            opinions.add(new ExpertOpinion(
                    "LearningPlanner",
                    "学习策略专家",
                    ExpertRole.LEARNING_PLANNER,
                    plan.toString(),
                    0.92,
                    Map.of("taskCount", tasks.size())
            ));

            log.info("[ExpertSession-{}] LearningPlanner: 生成了 {} 个学习任务", sessionId, tasks.size());

            return new ExpertConsensus(tasks, opinions);
        }

        private LearningSinkPolicy buildSinkPolicy(TableStorageStrategy strategy) {
            boolean mysql = true;
            boolean qdrant = strategy.enableVector();
            boolean neo4j = strategy.enableGraph();
            int keywordLimit = strategy.keywordLimit();
            return new LearningSinkPolicy(mysql, qdrant, neo4j, keywordLimit);
        }
    }

    // ========== 执行学习 ==========

    private void executeLearning(ExpertConsensus consensus, String scope, MysqlSchemaCatalogService.DynamicConnection conn, String sessionId) {
        for (LearningTask task : consensus.learningTasks()) {
            try {
                if (task.tableName().equals("_cross_table_relationships")) {
                    // 跨表关联学习
                    String payload = "# 跨表关联学习\n\n检测到表间存在关联关系，可用于跨表查询。";
                    activeLearningService.learn(payload, "cross_table", "cross_table_relationships", "multi_expert", scope);
                    log.info("[ExpertSession-{}] 跨表关联学习完成", sessionId);
                } else {
                    // 单表学习
                    String payload = buildTablePayload(task);
                    LearningSinkPolicy policy = task.sinkPolicy();
                    activeLearningService.learnWithSinkPolicy(
                            payload, "multi_expert", task.tableName(), "multi_expert", scope, policy
                    );
                    log.info("[ExpertSession-{}] 表 {} 学习完成 (mysql={}, qdrant={}, neo4j={})",
                            sessionId, task.tableName(), policy.mysql(), policy.qdrant(), policy.neo4j());

                    // 记录同步状态
                    syncTrackingService.recordSync(
                            conn.host(),
                            conn.port(),
                            conn.database(),
                            task.tableName(),
                            task.estimatedRows()
                    );
                }
            } catch (Exception e) {
                log.error("[ExpertSession-{}] 表 {} 学习失败: {}", sessionId, task.tableName(), e.getMessage());
            }
        }
    }

    private String buildTablePayload(LearningTask task) {
        return String.format("""
                # %s 学习

                ## 表信息
                - 表名：%s
                - 业务类型：%s
                - 业务含义：%s

                ## 列清单
                %s

                ## 学习策略
                - MySQL写入：启用
                - 向量索引：%s
                - 图谱构建：%s
                """,
                task.tableName(),
                task.tableName(),
                task.businessType(),
                task.businessMeaning(),
                task.columns().isEmpty() ? "(待填充)" : String.join(", ", task.columns()),
                task.sinkPolicy().qdrant() ? "启用" : "禁用",
                task.sinkPolicy().neo4j() ? "启用" : "禁用"
        );
    }

    // ========== Records ==========

    public enum ExpertRole { DATA_ANALYST, SYSTEM_ARCHITECT, LEARNING_PLANNER }

    public record ExpertOpinion(
            String expertId,
            String expertName,
            ExpertRole role,
            String report,
            double confidence,
            Map<String, Object> metadata
    ) {}

    public record TableProfile(
            String tableName,
            int rowCount,
            int columnCount,
            List<String> columns,
            List<String> keyColumns,
            String businessType,
            String businessMeaning,
            List<String> relatedTables
    ) {}

    public record DataAnalysisResult(
            List<TableProfile> tableProfiles,
            List<MysqlSchemaCatalogService.TableRelationship> relationships,
            List<ExpertOpinion> opinions
    ) {}

    public record TableStorageStrategy(
            String tableName,
            String primaryStorage,
            boolean enableVector,
            boolean enableGraph,
            int keywordLimit,
            LearningSinkPolicy initialPolicy,
            String reason
    ) {}

    public record StorageEvaluationResult(
            List<TableStorageStrategy> strategies,
            List<ExpertOpinion> opinions
    ) {}

    public record LearningTask(
            String tableName,
            String businessType,
            String businessMeaning,
            LearningSinkPolicy sinkPolicy,
            int estimatedRows,
            List<String> columns
    ) {}

    public record ExpertConsensus(
            List<LearningTask> learningTasks,
            List<ExpertOpinion> opinions
    ) {}

    public record MultiExpertLearningResult(
            boolean success,
            String sessionId,
            DataAnalysisResult dataAnalysis,
            StorageEvaluationResult storageEval,
            ExpertConsensus consensus,
            List<ExpertOpinion> allOpinions,
            String message
    ) {}
}