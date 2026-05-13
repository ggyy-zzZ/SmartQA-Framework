package com.qa.demo.qa.learning;

import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Service;

/**
 * 在 {@link MysqlSchemaCatalogService} 导出结果之上，调用大模型生成「知识库沉淀方案评估」段落（Phase 2）。
 */
@Service
public class MysqlSchemaCatalogAssessmentService {

    private final QaAssistantProperties properties;
    private final MiniMaxClient miniMaxClient;

    public MysqlSchemaCatalogAssessmentService(QaAssistantProperties properties, MiniMaxClient miniMaxClient) {
        this.properties = properties;
        this.miniMaxClient = miniMaxClient;
    }

    public record AssessmentOutcome(
            String modelText,
            boolean failed,
            String errorMessage,
            boolean catalogTruncatedForModel
    ) {
    }

    /**
     * 调用模型；失败时 {@link AssessmentOutcome#failed()} 为 true，由 HTTP 层决定是否仍对纯目录做 persist。
     */
    public AssessmentOutcome assess(MysqlSchemaCatalogService.SchemaCatalogExport export) {
        int maxCatalog = Math.max(4_000, properties.getMaxSchemaAssessmentCatalogChars());
        int maxResp = Math.max(500, properties.getMaxSchemaAssessmentResponseChars());
        String catalog = export.markdown();
        boolean truncatedForModel = catalog.length() > maxCatalog;
        if (truncatedForModel) {
            catalog = catalog.substring(0, maxCatalog) + "\n\n[《数据库结构说明》已截断，仅截取前 "
                    + maxCatalog + " 字符供模型分析]\n";
        }
        String system = """
                你是企业知识库接入顾问，回答须专业、克制，不得臆造表或列。禁止建议执行未授权的写操作、DDL 或批量导出敏感数据。
                助手对外称谓可能为「%s」，不要在结论中绑定某一固定商业产品名。
                """.formatted(properties.getAssistantName());
        String header = """
                以下为通过 information_schema 只读整理的《数据库结构说明》（可能已截断）。请基于**仅元数据**评估如何沉淀到知识库（文档/向量/结构化检索/主动学习文本等），并判断是否适合接入。

                你必须在回答最前面用编号列表明确写出（不可省略）：
                1) 本次评估假设的 schema 与表范围（schema=%s，导出表数=%d，且已排除 qa_ 系统表）；
                2) 与结构化行数上限的关系：项目配置 qa.assistant.max-structured-ingest-rows=%d（仅作策略说明，本次未扫业务行数）；
                3) 判定结论：支持 / 条件支持 / 不支持，并逐条对应前提；
                4) 若支持或条件支持：建议沉淀路径及先后顺序与主要风险；
                5) 若不支持：列出须先满足的条件。

                ---《数据库结构说明》开始---

                """.formatted(export.schema(), export.tableCount(), properties.getMaxStructuredIngestRows());
        String user = header + catalog + "\n\n---《数据库结构说明》结束---\n";
        try {
            String raw = miniMaxClient.completeChat(system, user);
            if (raw == null || raw.isBlank()) {
                return new AssessmentOutcome("", true, "模型返回空内容", truncatedForModel);
            }
            String clipped = raw.length() > maxResp ? raw.substring(0, maxResp) + "\n\n…（评估输出已按 max-schema-assessment-response-chars 截断）\n" : raw;
            return new AssessmentOutcome(clipped.trim(), false, null, truncatedForModel);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "assessment_failed" : e.getMessage();
            return new AssessmentOutcome("", true, msg, truncatedForModel);
        }
    }

    public static String combineCatalogAndAssessment(String catalogMarkdown, String assessment) {
        if (assessment == null || assessment.isBlank()) {
            return catalogMarkdown;
        }
        return catalogMarkdown + "\n\n---\n\n## 大模型沉淀方案评估（自动生成，须人工复核）\n\n" + assessment.trim() + "\n";
    }
}
