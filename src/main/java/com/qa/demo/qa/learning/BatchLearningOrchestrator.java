package com.qa.demo.qa.learning;

import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * 批量学习编排器：黑盒处理用户操作
 * <p>
 * 流程：
 * 1. 接收多个 CSV 文件，创建学习任务（落库）
 * 2. 分析 CSV 结构、检测关联、生成学习方案（LLM 参与）
 * 3. 方案评估：是否需要知识图谱、向量（LLM 参与）
 * 4. 执行学习：MySQL 先行（减轻上下文压力），按需触发向量/图谱
 * 5. 更新任务状态
 * <p>
 * 用户视角：选择文件 → 一键学习 → 完成（中间过程对用户不可见）
 */
@Service
public class BatchLearningOrchestrator {

    private static final String LEARNING_TASK_TABLE = "qa_learning_task";
    private static final String LEARNING_TASK_ITEM_TABLE = "qa_learning_task_item";

    private final QaAssistantProperties properties;
    private final BatchCsvAnalysisService batchCsvAnalysisService;
    private final ActiveLearningService activeLearningService;
    private final MiniMaxClient miniMaxClient;

    public BatchLearningOrchestrator(
            QaAssistantProperties properties,
            BatchCsvAnalysisService batchCsvAnalysisService,
            ActiveLearningService activeLearningService,
            MiniMaxClient miniMaxClient
    ) {
        this.properties = properties;
        this.batchCsvAnalysisService = batchCsvAnalysisService;
        this.activeLearningService = activeLearningService;
        this.miniMaxClient = miniMaxClient;
    }

    /**
     * 一键批量学习：分析 → 方案生成 → 评估 → 执行（黑盒）
     */
    public LearningTaskResult learnBatch(List<BatchCsvAnalysisService.CsvFileData> files, String scope) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        try {
            // Step 1: 创建任务记录
            long taskDbId = createTask(taskId, "csv_batch", files.size(), calcTotalRows(files), scope);

            // Step 2: 批量分析
            BatchCsvAnalysisService.BatchAnalysisResult analysis = batchCsvAnalysisService.analyzeBatch(files);
            updateTaskAnalysis(taskDbId, analysis);

            // Step 3: LLM 评估方案（决定是否需要向量/图谱）
            LearningPlanEvaluation evaluation = evaluatePlanWithLlm(analysis, scope);
            updateTaskPlan(taskDbId, evaluation);

            // Step 4: 创建任务项明细
            createTaskItems(taskDbId, analysis, evaluation);

            // Step 5: 执行学习（MySQL 先行，向量/图谱按需）
            executeLearning(taskDbId, analysis, evaluation, scope);

            // Step 6: 更新任务状态为完成
            completeTask(taskDbId);

            return new LearningTaskResult(taskId, true, "学习完成", files.size(), taskDbId);

        } catch (Exception e) {
            failTask(taskId, e.getMessage());
            return new LearningTaskResult(taskId, false, e.getMessage(), 0, -1);
        }
    }

    /**
     * 查询任务状态（供前端轮询）
     */
    public LearningTaskStatus getTaskStatus(String taskId) {
        String sql = "SELECT status, file_count, plan_json, error_message, created_at, updated_at FROM " + LEARNING_TASK_TABLE + " WHERE task_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<TaskItemStatus> items = getTaskItems(taskId);
                    return new LearningTaskStatus(
                            taskId,
                            rs.getString("status"),
                            rs.getInt("file_count"),
                            rs.getString("plan_json"),
                            rs.getString("error_message"),
                            rs.getTimestamp("created_at"),
                            rs.getTimestamp("updated_at"),
                            items
                    );
                }
            }
        } catch (SQLException e) {
            return new LearningTaskStatus(taskId, "error", 0, null, e.getMessage(), null, null, List.of());
        }
        return new LearningTaskStatus(taskId, "not_found", 0, null, "任务不存在", null, null, List.of());
    }

    // ========== Private Methods ==========

    private long createTask(String taskId, String sourceType, int fileCount, long totalRows, String scope) throws SQLException {
        ensureTablesExist();
        String sql = "INSERT INTO " + LEARNING_TASK_TABLE + " (task_id, source_type, status, file_count, total_rows, scope) VALUES (?, ?, 'created', ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, taskId);
            ps.setString(2, sourceType);
            ps.setInt(3, fileCount);
            ps.setLong(4, totalRows);
            ps.setString(5, scope);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return -1;
    }

    private void updateTaskAnalysis(long taskDbId, BatchCsvAnalysisService.BatchAnalysisResult analysis) throws SQLException {
        String json = toJson(analysis);
        String sql = "UPDATE " + LEARNING_TASK_TABLE + " SET analysis_json = ?, status = 'analyzing' WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, json);
            ps.setLong(2, taskDbId);
            ps.executeUpdate();
        }
    }

    private void updateTaskPlan(long taskDbId, LearningPlanEvaluation evaluation) throws SQLException {
        String json = toJson(evaluation);
        String sql = "UPDATE " + LEARNING_TASK_TABLE + " SET plan_json = ?, status = 'planned' WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, json);
            ps.setLong(2, taskDbId);
            ps.executeUpdate();
        }
    }

    private void createTaskItems(long taskDbId, BatchCsvAnalysisService.BatchAnalysisResult analysis, LearningPlanEvaluation evaluation) throws SQLException {
        String sql = "INSERT INTO " + LEARNING_TASK_ITEM_TABLE + " (task_id, filename, table_name, row_count, column_count, strategy_type, strategy_note, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < analysis.tables().size(); i++) {
                var table = analysis.tables().get(i);
                var strategy = evaluation.strategies().get(i);
                ps.setString(1, String.valueOf(taskDbId));
                ps.setString(2, table.filename());
                ps.setString(3, table.tableName());
                ps.setInt(4, table.rowCount());
                ps.setInt(5, table.columns().size());
                ps.setString(6, strategy.strategyType().name());
                ps.setString(7, strategy.reason());
                ps.executeUpdate();
            }
        }
    }

    private void executeLearning(long taskDbId, BatchCsvAnalysisService.BatchAnalysisResult analysis, LearningPlanEvaluation evaluation, String scope) throws SQLException {
        String sql = "UPDATE " + LEARNING_TASK_ITEM_TABLE + " SET status = 'learning' WHERE task_id = ? AND status = 'pending'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(taskDbId));
            ps.executeUpdate();
        }

        // 更新主任务状态
        String updateTask = "UPDATE " + LEARNING_TASK_TABLE + " SET status = 'executing' WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(updateTask)) {
            ps.setLong(1, taskDbId);
            ps.executeUpdate();
        }

        // 逐表学习
        for (int i = 0; i < analysis.tables().size(); i++) {
            var table = analysis.tables().get(i);
            var strategy = evaluation.strategies().get(i);

            try {
                // 构建学习内容
                String payload = buildTableLearningPayload(table, strategy);

                // 根据策略决定沉淀通道
                var sinkPolicy = determineSinkPolicy(strategy, evaluation);

                // MySQL 通道始终启用，向量/图谱按需
                var result = activeLearningService.learnWithSinkPolicy(
                        payload, "csv_structured_batch", table.tableName(), "csv_batch_api", scope, sinkPolicy
                );

                // 更新任务项
                String itemSql = "UPDATE " + LEARNING_TASK_ITEM_TABLE + " SET status = ?, knowledge_id = ? WHERE task_id = ? AND table_name = ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps2 = conn.prepareStatement(itemSql)) {
                    ps2.setString(1, result.success() ? "completed" : "failed");
                    ps2.setString(2, result.success() ? result.knowledgeId() : "");
                    ps2.setString(3, String.valueOf(taskDbId));
                    ps2.setString(4, table.tableName());
                    ps2.executeUpdate();
                }
            } catch (Exception e) {
                String itemSql = "UPDATE " + LEARNING_TASK_ITEM_TABLE + " SET status = 'failed', error_message = ? WHERE task_id = ? AND table_name = ?";
                try (Connection conn = getConnection();
                     PreparedStatement ps2 = conn.prepareStatement(itemSql)) {
                    ps2.setString(1, e.getMessage());
                    ps2.setString(2, String.valueOf(taskDbId));
                    ps2.setString(3, table.tableName());
                    ps2.executeUpdate();
                }
            }
        }

        // 跨表关联学习（如果有）
        if (!analysis.relationships().isEmpty()) {
            try {
                String relPayload = buildRelationshipPayload(analysis.tables(), analysis.relationships());
                activeLearningService.learn(relPayload, "csv_relationships", "csv_cross_table", "csv_batch_api", scope);
            } catch (Exception e) {
                // 关联学习失败不影响主流程
            }
        }
    }

    private void completeTask(long taskDbId) throws SQLException {
        String sql = "UPDATE " + LEARNING_TASK_TABLE + " SET status = 'completed' WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, taskDbId);
            ps.executeUpdate();
        }
    }

    private void failTask(String taskId, String errorMsg) {
        String sql = "UPDATE " + LEARNING_TASK_TABLE + " SET status = 'failed', error_message = ? WHERE task_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, errorMsg);
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    private List<TaskItemStatus> getTaskItems(String taskId) {
        List<TaskItemStatus> items = new ArrayList<>();
        String sql = "SELECT table_name, filename, status, knowledge_id, error_message FROM " + LEARNING_TASK_ITEM_TABLE + " WHERE task_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new TaskItemStatus(
                            rs.getString("table_name"),
                            rs.getString("filename"),
                            rs.getString("status"),
                            rs.getString("knowledge_id"),
                            rs.getString("error_message")
                    ));
                }
            }
        } catch (SQLException ignored) {
        }
        return items;
    }

    private LearningPlanEvaluation evaluatePlanWithLlm(BatchCsvAnalysisService.BatchAnalysisResult analysis, String scope) {
        // 构建提示
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个数据学习规划专家。请评估以下 CSV 批量学习的方案：\n\n");
        prompt.append("待学习 ").append(analysis.tables().size()).append(" 个表：\n");
        for (var table : analysis.tables()) {
            prompt.append("- ").append(table.tableName())
                    .append("（").append(table.rowCount()).append("行，").append(table.columns().size()).append("列）\n");
        }
        if (!analysis.relationships().isEmpty()) {
            prompt.append("\n检测到的关联：\n");
            for (var rel : analysis.relationships()) {
                prompt.append("- ").append(rel.fromTable()).append(".").append(rel.sharedColumn())
                        .append(" -> ").append(rel.toTable()).append("\n");
            }
        }
        prompt.append("\n请评估每个表是否需要向量检索（适用于文本搜索）或知识图谱（适用于关系查询）。");
        prompt.append("回答格式：JSON {strategies: [{tableName, needVector, needGraph, strategyType, reason}, ...]}");

        try {
            String response = miniMaxClient.completeChat(
                    "你是一个数据学习规划专家。",
                    prompt.toString()
            );
            if (response != null && !response.isEmpty()) {
                return parseEvaluation(response, analysis);
            }
        } catch (Exception e) {
            // LLM 调用失败，使用默认策略（仅 MySQL）
        }
        return defaultEvaluation(analysis);
    }

    private LearningPlanEvaluation parseEvaluation(String llmText, BatchCsvAnalysisService.BatchAnalysisResult analysis) {
        // 简单策略：从 LLM 响应中提取关键信息，构建评估
        List<TableLearningStrategy> strategies = new ArrayList<>();
        boolean needVector = llmText.contains("向量") || llmText.contains("vector");
        boolean needGraph = llmText.contains("图谱") || llmText.contains("graph");

        for (var table : analysis.tables()) {
            String strategyType = "FULL_LEARN";
            if (table.rowCount() > 10000) strategyType = "SAMPLE_THEN_FULL";
            else if (table.columns().size() > 20) strategyType = "KEY_COLUMNS_ONLY";

            strategies.add(new TableLearningStrategy(
                    table.tableName(),
                    needVector,
                    needGraph,
                    BatchCsvAnalysisService.LearningStrategyType.valueOf(strategyType),
                    "LLM 评估"
            ));
        }
        return new LearningPlanEvaluation(strategies, needVector, needGraph, llmText);
    }

    private LearningPlanEvaluation defaultEvaluation(BatchCsvAnalysisService.BatchAnalysisResult analysis) {
        List<TableLearningStrategy> strategies = new ArrayList<>();
        for (var table : analysis.tables()) {
            String strategyType = "FULL_LEARN";
            if (table.rowCount() > 10000) strategyType = "SAMPLE_THEN_FULL";
            else if (table.columns().size() > 20) strategyType = "KEY_COLUMNS_ONLY";

            strategies.add(new TableLearningStrategy(
                    table.tableName(),
                    false, // 默认不启用向量（减轻压力）
                    false, // 默认不启用图谱
                    BatchCsvAnalysisService.LearningStrategyType.valueOf(strategyType),
                    "默认策略"
            ));
        }
        return new LearningPlanEvaluation(strategies, false, false, "默认评估");
    }

    private LearningSinkPolicy determineSinkPolicy(TableLearningStrategy strategy, LearningPlanEvaluation evaluation) {
        // MySQL 始终启用，向量和图谱按评估结果
        boolean enableVector = strategy.needVector() || evaluation.enableVectorByDefault();
        boolean enableGraph = strategy.needGraph() || evaluation.enableGraphByDefault();
        return new LearningSinkPolicy(true, enableVector, enableGraph, LearningSinkPolicy.DEFAULT_KEYWORD_LIMIT);
    }

    private long calcTotalRows(List<BatchCsvAnalysisService.CsvFileData> files) {
        long total = 0;
        for (var file : files) {
            String text = new String(file.content(), java.nio.charset.StandardCharsets.UTF_8);
            total += text.split("\\R", -1).length;
        }
        return total;
    }

    private String buildTableLearningPayload(BatchCsvAnalysisService.CsvTableInfo table, TableLearningStrategy strategy) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CSV 数据表学习：").append(table.tableName()).append("\n\n");
        sb.append("来源文件：").append(table.filename()).append("\n\n");
        sb.append("## 表结构\n");
        sb.append("| 列名 | 数据类型 |\n");
        sb.append("|------|----------|\n");
        for (int i = 0; i < table.columns().size(); i++) {
            sb.append("| ").append(escapeMd(table.columns().get(i)))
                    .append(" | ").append(table.columnTypes().get(i).name()).append(" |\n");
        }
        sb.append("\n数据行数：").append(table.rowCount()).append(" 行\n");
        sb.append("学习策略：").append(strategy.strategyType().name()).append("\n");
        return sb.toString();
    }

    private String buildRelationshipPayload(List<BatchCsvAnalysisService.CsvTableInfo> tables, List<BatchCsvAnalysisService.TableRelationship> relationships) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CSV 多表关联学习\n\n");
        for (var rel : relationships) {
            sb.append("- ").append(rel.fromTable()).append(".").append(rel.sharedColumn())
                    .append(" -> ").append(rel.toTable()).append("\n");
        }
        return sb.toString();
    }

    private String escapeMd(String text) {
        if (text == null) return "";
        return text.replace("|", "/").replace("\n", " ");
    }

    private String toJson(Object obj) {
        return obj.toString();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword()
        );
    }

    private void ensureTablesExist() {
        String createTaskTable = """
            CREATE TABLE IF NOT EXISTS qa_learning_task (
              id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              task_id VARCHAR(64) NOT NULL,
              source_type VARCHAR(32) NOT NULL,
              status VARCHAR(32) NOT NULL DEFAULT 'created',
              file_count INT NOT NULL DEFAULT 0,
              total_rows BIGINT NOT NULL DEFAULT 0,
              scope VARCHAR(32) NOT NULL DEFAULT 'enterprise',
              plan_json LONGTEXT,
              analysis_json LONGTEXT,
              error_message TEXT,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              UNIQUE KEY uk_task_id (task_id),
              KEY idx_status_created (status, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

        String createItemTable = """
            CREATE TABLE IF NOT EXISTS qa_learning_task_item (
              id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              task_id VARCHAR(64) NOT NULL,
              filename VARCHAR(255) NOT NULL,
              table_name VARCHAR(128) NOT NULL,
              row_count INT NOT NULL DEFAULT 0,
              column_count INT NOT NULL DEFAULT 0,
              strategy_type VARCHAR(32) NOT NULL DEFAULT 'FULL_LEARN',
              strategy_note VARCHAR(512),
              status VARCHAR(32) NOT NULL DEFAULT 'pending',
              knowledge_id VARCHAR(64),
              error_message TEXT,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              KEY idx_task_id (task_id),
              KEY idx_knowledge_id (knowledge_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTaskTable);
            stmt.execute(createItemTable);
        } catch (SQLException ignored) {
        }
    }

    // ========== Records ==========

    public record LearningTaskResult(String taskId, boolean ok, String message, int fileCount, long taskDbId) {}

    public record LearningTaskStatus(
            String taskId,
            String status,
            int fileCount,
            String planJson,
            String errorMessage,
            Timestamp createdAt,
            Timestamp updatedAt,
            List<TaskItemStatus> items
    ) {}

    public record TaskItemStatus(String tableName, String filename, String status, String knowledgeId, String errorMessage) {}

    public record LearningPlanEvaluation(
            List<TableLearningStrategy> strategies,
            boolean enableVectorByDefault,
            boolean enableGraphByDefault,
            String llmReasoning
    ) {}

    public record TableLearningStrategy(
            String tableName,
            boolean needVector,
            boolean needGraph,
            BatchCsvAnalysisService.LearningStrategyType strategyType,
            String reason
    ) {}
}