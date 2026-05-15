package com.qa.demo.qa.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.knowledge.SedimentationPlanPrompts;
import com.qa.demo.qa.answer.MiniMaxClient;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * MySQL 元数据目录 → 结构化「可行性 + 沉淀方案 JSON」→ 可选二次模型生成正文 →
 * 按方案启用的写入路调用 {@link ActiveLearningService#learnWithSinkPolicy}。
 *
 * @see com.qa.demo.qa.web.QaController#mysqlSedimentationPipeline
 */
@Service
public class SchemaSedimentationPlanService {

    private final QaAssistantProperties properties;
    private final MiniMaxClient miniMaxClient;
    private final ObjectMapper objectMapper;
    private final ActiveLearningService activeLearningService;

    public SchemaSedimentationPlanService(
            QaAssistantProperties properties,
            MiniMaxClient miniMaxClient,
            ObjectMapper objectMapper,
            ActiveLearningService activeLearningService
    ) {
        this.properties = properties;
        this.miniMaxClient = miniMaxClient;
        this.objectMapper = objectMapper;
        this.activeLearningService = activeLearningService;
    }

    public enum IngestBodyStrategy {
        CATALOG_AS_IS,
        MODEL_DIGEST
    }

    public record ParsedSedimentationPlan(
            boolean feasible,
            String feasibilityRationale,
            double confidence,
            String planSummaryMarkdown,
            LearningSinkPolicy sinkPolicy,
            IngestBodyStrategy bodyStrategy,
            String titleHint
    ) {
    }

    public record PipelineOutcome(
            boolean ok,
            String message,
            ParsedSedimentationPlan plan,
            String rawPlanJson,
            boolean digestApplied,
            String ingestPreview,
            ActiveLearningService.LearningResult learningResult
    ) {
        public static PipelineOutcome fail(String message) {
            return new PipelineOutcome(false, message, null, null, false, "", null);
        }
    }

    public PipelineOutcome runPipeline(
            MysqlSchemaCatalogService.SchemaCatalogExport export,
            boolean persist,
            String normalizedScope
    ) {
        int maxCatalog = Math.max(4_000, properties.getMaxSchemaAssessmentCatalogChars());
        int maxPlanJson = Math.max(2_000, properties.getMaxSchemaAssessmentResponseChars() * 3);
        int maxDigest = Math.max(8_000, Math.min(100_000, properties.getMaxSchemaExportChars()));

        String catalog = export.markdown();
        boolean catalogTruncatedForPlan = catalog.length() > maxCatalog;
        String catalogForPlan = catalogTruncatedForPlan
                ? catalog.substring(0, maxCatalog) + "\n\n[《数据库结构说明》已截断，仅前 " + maxCatalog + " 字符参与方案生成]\n"
                : catalog;

        String planUser = """
                schema=%s
                导出业务表数=%d
                项目结构化行数上限配置 qa.assistant.max-structured-ingest-rows=%d

                ---《数据库结构说明》开始---
                %s
                ---《数据库结构说明》结束---

                请严格输出 JSON，不要 markdown 围栏，不要任何 JSON 以外字符。
                """.formatted(export.schema(), export.tableCount(), properties.getMaxStructuredIngestRows(), catalogForPlan);

        final String rawJson;
        try {
            String raw = miniMaxClient.completeChat(
                    SedimentationPlanPrompts.planJsonSystemPrompt(properties.getAssistantName()),
                    planUser
            );
            if (raw == null || raw.isBlank()) {
                return PipelineOutcome.fail("模型返回空内容，无法生成沉淀方案。");
            }
            String clipped = raw.length() > maxPlanJson ? raw.substring(0, maxPlanJson) : raw;
            rawJson = extractJsonObject(clipped.trim());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "plan_generation_failed" : e.getMessage();
            return PipelineOutcome.fail("沉淀方案生成失败: " + msg);
        }

        final ParsedSedimentationPlan plan;
        try {
            plan = parsePlanJson(objectMapper.readTree(rawJson));
        } catch (Exception e) {
            return PipelineOutcome.fail("沉淀方案 JSON 解析失败: " + e.getMessage());
        }

        if (!plan.feasible()) {
            return new PipelineOutcome(true, "模型判定当前不宜沉淀。", plan, rawJson, false, "", null);
        }
        if (!plan.sinkPolicy().anySinkEnabled()) {
            return new PipelineOutcome(true, "模型判定可行但未启用任何写入通路。", plan, rawJson, false, "", null);
        }

        if (!persist) {
            return new PipelineOutcome(true, "已生成沉淀方案，未请求持久化。", plan, rawJson, false, "", null);
        }

        String ingestBody;
        boolean digestApplied = false;
        String triggerSuffix = "catalog_as_is";
        try {
            if (plan.bodyStrategy() == IngestBodyStrategy.MODEL_DIGEST) {
                digestApplied = true;
                triggerSuffix = "model_digest";
                ingestBody = synthesizeDigestMarkdown(export, plan, maxCatalog, maxDigest);
            } else {
                ingestBody = catalog;
                if (ingestBody.length() > maxDigest) {
                    ingestBody = ingestBody.substring(0, maxDigest) + "\n\n[正文已按长度上限截断]\n";
                }
            }
        } catch (Exception e) {
            return new PipelineOutcome(false, "正文生成失败: " + e.getMessage(), plan, rawJson, digestApplied, "", null);
        }

        String preview = ingestBody.length() > 800 ? ingestBody.substring(0, 800) + "…" : ingestBody;

        String sourceName = "schema-" + export.schema();
        if (plan.titleHint() != null && !plan.titleHint().isBlank()) {
            sourceName = sourceName + ":" + plan.titleHint().trim();
        }

        ActiveLearningService.LearningResult learningResult = activeLearningService.learnWithSinkPolicy(
                ingestBody,
                "mysql_schema_sedimentation_plan",
                sourceName,
                "sedimentation_pipeline_" + triggerSuffix,
                normalizedScope,
                plan.sinkPolicy()
        );

        String msg = learningResult.success() ? "已按沉淀方案写入启用的通路。" : "沉淀执行未完全成功: " + learningResult.message();
        return new PipelineOutcome(learningResult.success(), msg, plan, rawJson, digestApplied, preview, learningResult);
    }

    public ParsedSedimentationPlan parsePlanJson(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new IllegalArgumentException("empty_json");
        }
        boolean feasible = root.path("feasible").asBoolean(false);
        String rationale = root.path("feasibilityRationale").asText("").trim();
        double confidence = Math.max(0, Math.min(1, root.path("confidence").asDouble(0)));
        String planMd = root.path("planSummaryMarkdown").asText("").trim();
        String titleHint = root.path("ingest").path("titleHint").asText("").trim();

        JsonNode sinks = root.path("sinks");
        boolean mysql = sinks.path("mysql").path("enabled").asBoolean(true);
        boolean qdrant = sinks.path("qdrant").path("enabled").asBoolean(true);
        boolean neo4j = sinks.path("neo4j").path("enabled").asBoolean(true);
        int keywordLimit = sinks.path("neo4j").path("keywordLimit").asInt(LearningSinkPolicy.DEFAULT_KEYWORD_LIMIT);

        String strategyRaw = root.path("ingest").path("bodyStrategy").asText("model_digest").trim().toLowerCase(Locale.ROOT);
        IngestBodyStrategy bodyStrategy = switch (strategyRaw) {
            case "catalog_as_is" -> IngestBodyStrategy.CATALOG_AS_IS;
            case "model_digest", "model_rewritten_digest" -> IngestBodyStrategy.MODEL_DIGEST;
            default -> IngestBodyStrategy.MODEL_DIGEST;
        };

        LearningSinkPolicy policy = new LearningSinkPolicy(mysql, qdrant, neo4j, keywordLimit);
        if (!feasible) {
            policy = new LearningSinkPolicy(false, false, false, LearningSinkPolicy.DEFAULT_KEYWORD_LIMIT);
        }
        return new ParsedSedimentationPlan(feasible, rationale, confidence, planMd, policy, bodyStrategy, titleHint);
    }

    private String synthesizeDigestMarkdown(
            MysqlSchemaCatalogService.SchemaCatalogExport export,
            ParsedSedimentationPlan plan,
            int maxCatalog,
            int maxDigest
    ) {
        String catalog = export.markdown();
        if (catalog.length() > maxCatalog) {
            catalog = catalog.substring(0, maxCatalog) + "\n\n[目录已截断]\n";
        }
        String user = """
                目标 schema=%s

                ---《数据库结构说明》（可能已截断）---
                %s
                ---结束---

                ---《沉淀方案摘要》（Markdown）---
                %s
                ---结束---

                请输出**一份**可直接写入企业知识库的 Markdown 正文（允许使用标题、列表、表格），用于后续的 MySQL 文本存档、向量检索与图谱关键词扩展。
                要求：基于元数据做归纳与接入说明，不要编造未出现的表或列；不要输出 JSON；不要重复粘贴整份原始目录。
                """.formatted(export.schema(), catalog, plan.planSummaryMarkdown().isBlank()
                ? "（无摘要，请自行根据目录撰写接入说明）"
                : plan.planSummaryMarkdown());

        String raw = miniMaxClient.completeChat(
                SedimentationPlanPrompts.digestSystemPrompt(properties.getAssistantName()),
                user
        );
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("digest_empty");
        }
        String body = raw.trim();
        if (body.length() > maxDigest) {
            body = body.substring(0, maxDigest) + "\n\n…（正文已截断）\n";
        }
        return body;
    }

    static String extractJsonObject(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1).trim();
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no_json_object");
        }
        return s.substring(start, end + 1);
    }
}
