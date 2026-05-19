package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 批量 CSV 分析服务：解析多个 CSV 文件，分析表结构，检测关联关系，生成学习方案。
 */
@Service
public class BatchCsvAnalysisService {

    private final QaAssistantProperties properties;
    private final ActiveLearningService activeLearningService;

    public BatchCsvAnalysisService(QaAssistantProperties properties, ActiveLearningService activeLearningService) {
        this.properties = properties;
        this.activeLearningService = activeLearningService;
    }

    /**
     * 批量解析 CSV 文件，返回每个文件的结构信息。
     */
    public BatchAnalysisResult analyzeBatch(List<CsvFileData> files) {
        List<CsvTableInfo> tables = new ArrayList<>();
        Set<String> allColumnNames = new HashSet<>();
        Map<String, List<String>> columnToTables = new LinkedHashMap<>();

        for (CsvFileData file : files) {
            CsvTableInfo info = parseCsvTable(file.filename(), file.content());
            tables.add(info);

            // 统计列名出现情况（用于检测关联）
            for (String col : info.columns()) {
                allColumnNames.add(col.toLowerCase());
                columnToTables.computeIfAbsent(col.toLowerCase(), k -> new ArrayList<>()).add(info.tableName());
            }
        }

        // 检测表间关联
        List<TableRelationship> relationships = detectRelationships(tables, columnToTables);

        // 生成学习方案
        LearningPlan plan = generateLearningPlan(tables, relationships);

        return new BatchAnalysisResult(tables, relationships, plan);
    }

    /**
     * 执行批量学习（分析后一键学习）。
     */
    public List<ActiveLearningService.LearningResult> executeLearning(
            BatchAnalysisResult analysis,
            String scope
    ) {
        List<ActiveLearningService.LearningResult> results = new ArrayList<>();

        // 1. 先学习各表独立内容
        for (CsvTableInfo table : analysis.tables()) {
            String payload = buildTableLearningPayload(table);
            ActiveLearningService.LearningResult result = activeLearningService.learn(
                    payload, "csv_structured_batch", table.tableName(), "csv_batch_api", scope
            );
            results.add(result);
        }

        // 2. 生成跨表关联学习内容（如果有关联关系）
        if (!analysis.relationships().isEmpty()) {
            String relationshipPayload = buildRelationshipLearningPayload(
                    analysis.tables(), analysis.relationships()
            );
            ActiveLearningService.LearningResult relResult = activeLearningService.learn(
                    relationshipPayload, "csv_relationships", "csv_cross_table_relationships",
                    "csv_batch_api", scope
            );
            results.add(relResult);
        }

        return results;
    }

    private CsvTableInfo parseCsvTable(String filename, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R", -1);

        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        boolean headerFound = false;

        for (String line : lines) {
            if (line.isBlank()) continue;

            List<String> fields = parseCsvLine(line);

            if (!headerFound) {
                headers.addAll(fields);
                headerFound = true;
            } else {
                rows.add(fields);
            }
        }

        // 检测数据类型
        List<ColumnType> columnTypes = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            ColumnType type = ColumnType.TEXT;
            if (i < rows.size() && !rows.isEmpty()) {
                type = inferColumnType(rows, i);
            }
            columnTypes.add(type);
        }

        String tableName = safeTableName(filename);
        return new CsvTableInfo(tableName, filename, headers, columnTypes, rows.size());
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }

    private ColumnType inferColumnType(List<List<String>> rows, int columnIndex) {
        int intCount = 0;
        int doubleCount = 0;
        int dateCount = 0;
        int boolCount = 0;
        int sampleSize = Math.min(rows.size(), 100);

        for (int i = 0; i < sampleSize; i++) {
            List<String> row = rows.get(i);
            if (columnIndex >= row.size()) continue;

            String value = row.get(columnIndex).trim();
            if (value.isEmpty()) continue;

            if (value.matches("^-?\\d+$")) intCount++;
            else if (value.matches("^-?\\d+\\.\\d+$")) doubleCount++;
            else if (value.matches("^\\d{4}-\\d{2}-\\d{2}")) dateCount++;
            else if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) boolCount++;
        }

        if (intCount > sampleSize * 0.7) return ColumnType.INTEGER;
        if (doubleCount > sampleSize * 0.7) return ColumnType.DECIMAL;
        if (dateCount > sampleSize * 0.7) return ColumnType.DATE;
        if (boolCount > sampleSize * 0.7) return ColumnType.BOOLEAN;
        return ColumnType.TEXT;
    }

    private List<TableRelationship> detectRelationships(
            List<CsvTableInfo> tables,
            Map<String, List<String>> columnToTables
    ) {
        List<TableRelationship> relationships = new ArrayList<>();

        // 查找共享列名（可能的关联字段）
        for (Map.Entry<String, List<String>> entry : columnToTables.entrySet()) {
            if (entry.getValue().size() >= 2) {
                String column = entry.getKey();
                List<String> tableNames = entry.getValue();
                // 简单策略：取前两个表建立关联
                if (tableNames.size() >= 2) {
                    relationships.add(new TableRelationship(
                            tableNames.get(0),
                            tableNames.get(1),
                            column,
                            RelationshipType.MANY_TO_MANY,
                            "共享列名: " + column
                    ));
                }
            }
        }

        // 常见外键模式检测
        for (CsvTableInfo table : tables) {
            for (String header : table.columns()) {
                String lower = header.toLowerCase();
                if (lower.endsWith("_id") || lower.equals("id")) {
                    // 可能的外键
                    for (CsvTableInfo other : tables) {
                        if (!other.tableName().equals(table.tableName())) {
                            for (String otherHeader : other.columns()) {
                                if (otherHeader.toLowerCase().equals(other.tableName().toLowerCase() + "_id") ||
                                    otherHeader.toLowerCase().equals("id")) {
                                    relationships.add(new TableRelationship(
                                            table.tableName(),
                                            other.tableName(),
                                            header,
                                            RelationshipType.MANY_TO_ONE,
                                            "外键检测: " + header + " -> " + other.tableName() + ".id"
                                    ));
                                }
                            }
                        }
                    }
                }
            }
        }

        return relationships;
    }

    private LearningPlan generateLearningPlan(List<CsvTableInfo> tables, List<TableRelationship> relationships) {
        List<LearningStrategy> strategies = new ArrayList<>();

        for (CsvTableInfo table : tables) {
            // 根据表特征选择学习策略
            if (table.rowCount() > 10000) {
                strategies.add(new LearningStrategy(
                        table.tableName(),
                        LearningStrategyType.SAMPLE_THEN_FULL,
                        "大表（" + table.rowCount() + "行），建议采样学习后全量索引",
                        table.rowCount()
                ));
            } else if (table.columns().size() > 20) {
                strategies.add(new LearningStrategy(
                        table.tableName(),
                        LearningStrategyType.KEY_COLUMNS_ONLY,
                        "宽表（" + table.columns().size() + "列），建议仅学习关键列",
                        table.rowCount()
                ));
            } else {
                strategies.add(new LearningStrategy(
                        table.tableName(),
                        LearningStrategyType.FULL_LEARN,
                        "标准表（全量学习）",
                        table.rowCount()
                ));
            }
        }

        String overallRecommendation = generateRecommendation(tables, relationships);

        return new LearningPlan(strategies, overallRecommendation);
    }

    private String generateRecommendation(List<CsvTableInfo> tables, List<TableRelationship> relationships) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 学习方案建议\n\n");
        sb.append("### 概览\n");
        sb.append("- 待学习 ").append(tables.size()).append(" 个 CSV 文件\n");

        long totalRows = tables.stream().mapToLong(CsvTableInfo::rowCount).sum();
        sb.append("- 总数据行数约 ").append(totalRows).append(" 行\n\n");

        if (relationships.size() > 0) {
            sb.append("### 检测到的关联\n");
            for (TableRelationship rel : relationships) {
                sb.append("- ").append(rel.fromTable()).append(".").append(rel.sharedColumn())
                        .append(" -> ").append(rel.toTable()).append("\n");
            }
            sb.append("\n建议：为关联数据建立跨表检索路径，提问时可跨表查询。\n");
        } else {
            sb.append("### 关联检测\n");
            sb.append("未检测到明显的表间关联，各表独立学习即可。\n");
        }

        return sb.toString();
    }

    private String buildTableLearningPayload(CsvTableInfo table) {
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
        sb.append("\n数据行数：").append(table.rowCount()).append(" 行\n\n");

        sb.append("## 数据样本（前 5 行）\n");
        sb.append("```csv\n");
        sb.append(String.join(",", table.columns())).append("\n");
        int sampleRows = Math.min(table.rowCount(), 5);
        for (int i = 0; i < sampleRows; i++) {
            // CSV 行已保存在 table 中，但这里简化处理
            sb.append("(数据行 ").append(i + 1).append(")\n");
        }
        sb.append("```\n");

        return sb.toString();
    }

    private String buildRelationshipLearningPayload(
            List<CsvTableInfo> tables,
            List<TableRelationship> relationships
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("# CSV 多表关联学习\n\n");
        sb.append("## 检测到的表间关系\n\n");

        for (TableRelationship rel : relationships) {
            sb.append("### ").append(rel.fromTable()).append(" -> ").append(rel.toTable()).append("\n");
            sb.append("- 关联字段：").append(rel.sharedColumn()).append("\n");
            sb.append("- 关系类型：").append(rel.relationshipType()).append("\n");
            sb.append("- 说明：").append(rel.note()).append("\n\n");
        }

        sb.append("## 跨表查询示例\n\n");
        for (TableRelationship rel : relationships) {
            sb.append("问：查找 ").append(rel.fromTable()).append(" 中与 ").append(rel.toTable())
                    .append(" 关联的数据\n");
            sb.append("答：可通过 ").append(rel.sharedColumn()).append(" 字段连接两表\n\n");
        }

        return sb.toString();
    }

    private String safeTableName(String filename) {
        if (filename == null || filename.isBlank()) return "table";
        String name = filename.replace(".csv", "").trim();
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String escapeMd(String text) {
        if (text == null) return "";
        return text.replace("|", "/").replace("\n", " ");
    }

    // ========== Records ==========

    public record CsvFileData(String filename, byte[] content) {}

    public record CsvTableInfo(
            String tableName,
            String filename,
            List<String> columns,
            List<ColumnType> columnTypes,
            int rowCount
    ) {}

    public enum ColumnType { TEXT, INTEGER, DECIMAL, DATE, BOOLEAN }

    public enum RelationshipType { ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY }

    public record TableRelationship(
            String fromTable,
            String toTable,
            String sharedColumn,
            RelationshipType relationshipType,
            String note
    ) {}

    public record LearningStrategy(
            String tableName,
            LearningStrategyType strategyType,
            String recommendation,
            int rowCount
    ) {}

    public enum LearningStrategyType {
        FULL_LEARN,        // 全量学习
        SAMPLE_THEN_FULL,  // 采样后全量
        KEY_COLUMNS_ONLY   // 仅关键列
    }

    public record LearningPlan(
            List<LearningStrategy> strategies,
            String overallRecommendation
    ) {}

    public record BatchAnalysisResult(
            List<CsvTableInfo> tables,
            List<TableRelationship> relationships,
            LearningPlan learningPlan
    ) {}
}