package com.qa.demo.qa.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答助手运行时配置，前缀 {@code qa.assistant}；密钥类字段优先读环境变量（如 MINIMAX_API_KEY）。
 */
@Validated
@ConfigurationProperties(prefix = "qa.assistant")
public class QaAssistantProperties {

    @NotBlank
    private String docsDir;

    @NotBlank
    private String model;

    @NotBlank
    private String apiUrl;

    @NotBlank
    private String apiKey;

    private int retrievalTopK = 6;
    private boolean vectorEnabled = true;
    private String qdrantUrl = "http://localhost:6333";
    private String qdrantCollection = "enterprise_knowledge_v2";
    private String qdrantActiveLearningCollection = "enterprise_active_learning_v2";
    private int vectorTopK = 6;
    private int vectorEmbeddingDim = 1024;

    /**
     * 向量化实现：{@code dashscope}（百炼 text-embedding-v4）或 {@code hash}（本地伪向量）。
     * dashscope 在未配置 {@link #dashscopeApiKey} 时自动降级为 hash。
     */
    private String embeddingProvider = "dashscope";

    private String dashscopeApiKey = "";

    private String embeddingModel = "text-embedding-v4";

    private String embeddingApiUrl =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    /** 百炼单批最多 10 条 */
    private int embeddingBatchSize = 10;

    private int embeddingTimeoutMs = 30_000;

    /** 企业 scope：并行召回图/向量/MySQL/SQL 并合并主动学习，再重排 */
    private boolean unifiedRetrievalEnabled = true;

    /** 是否对合并候选调用百炼 gte-rerank（无 Key 时按原始 score 排序截断） */
    private boolean rerankEnabled = true;

    private String rerankModel = "gte-rerank-v2";

    private String rerankApiUrl =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /** 送入重排前的最大候选条数 */
    private int rerankCandidateMax = 32;

    /** 统一召回阶段向量路 TopK（可大于 retrieval-top-k） */
    private int recallVectorTopK = 12;

    /** 统一召回阶段图谱路 TopK */
    private int recallGraphTopK = 10;

    /** 人物任职列表类问题（person_role_list）图谱召回上限 */
    private int recallGraphPersonRoleTopK = 32;

    /** 是否用 MiniMax 做意图+实体抽取（无 API Key 时自动走规则） */
    private boolean intentLlmEnabled = true;

    /** 复杂问句多步 Planner + 跨源 Executor（对比/计算类） */
    private boolean agentMultiStepEnabled = true;

    /** 任职/证照等列表型问句槽位可由规则填满时，跳过意图 LLM（避免首包长时间无响应） */
    private boolean intentRuleFirstForStructured = true;

    /**
     * 当 structured query 已被规则命中时，是否仍尝试一次 LLM 辅助校验与补槽。
     * true: 规则先行但仍会尝试 LLM，失败/超时自动回退规则结果。
     */
    private boolean intentLlmAssistStructured = true;

    /**
     * LLM 意图置信度不低于该值且槽位已齐备时，跳过规则 enrich（仅补 reason 前缀）。
     */
    private double intentLlmEnrichMinConfidence = 0.72;

    /** 意图 LLM 单次调用超时（毫秒），超时后走规则路由 */
    private int intentLlmTimeoutMs = 45_000;

    /** 证据不足时是否拦截 LLM 生成（本地验证建议开启） */
    private boolean answerGateEnabled = true;

    private int answerGateMinEvidenceCount = 1;

    private double answerGateMinTopScore = 3.0;

    private boolean answerGateBlockOnUnknownIntent = true;

    /** 为 true 时，evidenceAlignment.lowOverlap 则不走 LLM，改模板答复 */
    private boolean alignmentStrict = false;

    private double alignmentLowOverlapThreshold = 0.08;

    private boolean mysqlEnabled = true;
    private String mysqlUrl = "jdbc:mysql://localhost:3306/assistant?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    private String mysqlUsername = "root";
    private String mysqlPassword = "root";
    private String mysqlSchema = "assistant";
    private int mysqlTopK = 6;
    private int mysqlPerTableLimit = 3;

    /**
     * 业务数据库 URL（用于 supplemental table 查询，如 tdcomp.employee）。
     */
    private String businessMysqlUrl = "jdbc:mysql://localhost:3306/tdcomp?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    private String businessMysqlUsername = "root";
    private String businessMysqlPassword = "root";

    /**
     * 员工基础知识加载最大条数（0 表示不限制）。
     * 用于控制 name/another_name → id 索引的构建规模。
     */
    private int employeeBaseLimit = 50000;

    /**
     * 在系统提示中展示的助手称谓，勿绑定某一商业产品名。
     */
    private String assistantName = "知识库问答助手";

    /**
     * 规划：单次可接入的结构化数据行数上限（检索侧校验可逐步落实）。
     */
    private int maxStructuredIngestRows = 10_000;

    /**
     * 是否在启动时对 {@link #mysqlUrl} 指向的库执行 Flyway（classpath:db/migration/assistant）。
     * 本地无 MySQL 或仍用手工执行 {@code assistant_bootstrap.sql} 时可保持 false。
     */
    private boolean flywayEnabled = false;

    /**
     * 人员-角色预检：员工表物理名（仅字母数字下划线）。
     */
    private String mysqlPersonRoleEmployeeTable = "employee";

    /**
     * 人员-角色预检：企业表物理名（仅字母数字下划线）。
     */
    private String mysqlPersonRoleCompanyTable = "company";

    /**
     * schema 目录导出：最多包含几张业务表（字母序，排除 qa_）。
     */
    private int maxSchemaExportTables = 15;

    /**
     * schema 目录 Markdown 最大字符数（导出阶段硬截断）。
     */
    private int maxSchemaExportChars = 250_000;

    /**
     * schema 目录 Markdown 送入模型前的最大字符数（超出截断，避免上下文过长）。
     */
    private int maxSchemaAssessmentCatalogChars = 24_000;

    /**
     * 模型评估段落写入学习文档前的最大字符数（超出硬截断）。
     */
    private int maxSchemaAssessmentResponseChars = 6_000;

    /**
     * 是否启用结构化入库清单的定时门禁（读取 manifest 做行数审计并追加 JSON 日志）。
     */
    private boolean structuredIngestScheduleEnabled = false;

    /**
     * Cron（秒 分 时 日 月 周），默认每日 02:00。
     */
    private String structuredIngestScheduleCron = "0 0 2 * * ?";

    /**
     * 是否启用定时增量同步（每30分钟检查一次已学习的数据源是否有新增数据）。
     */
    private boolean enableScheduledSync = true;

    /** EKSP 增量同步域（如 org_master） */
    private String knowledgeSyncDomain = "org_master";

    /** 首次/无水位时默认回溯小时数 */
    private int knowledgeSyncDefaultSinceHours = 24;

    /** 定时任务是否触发 EKSP incremental sync（Python 灌库） */
    private boolean knowledgeSyncIncrementalScheduledEnabled = false;

    /**
     * 为 true 时先探测业务库水位（updated_at > since），有变更才跑增量；为 false 则按轮询间隔无条件增量（旧行为）。
     */
    private boolean knowledgeSyncWatermarkWatchOnly = true;

    /** 自动同步轮询间隔（毫秒），仅在水位监测模式下作为 {@code @Scheduled} 间隔 */
    private long knowledgeSyncPollIntervalMs = 60_000L;

    /**
     * 锚点表水位列（如 tdcomp.company 的 modifytime）；空则按候选列自动探测。
     */
    private String knowledgeSyncWatermarkColumn = "modifytime";

    // ==================== CDC / Debezium 配置 ====================

    /**
     * 是否启用 Debezium CDC（替代水位 Polling）。
     */
    private boolean cdcEnabled = false;

    /**
     * CDC Kafka Bootstrap Servers。
     */
    private String cdcKafkaBootstrapServers = "localhost:9092";

    /**
     * CDC Consumer Group ID。
     */
    private String cdcKafkaGroupId = "demo-cdc-consumer";

    /**
     * Debezium 监听的数据库名（逗号分隔，如 tdcomp）。
     */
    private String cdcDatabaseIncludeList = "tdcomp";

    /**
     * Debezium 监听的表名（逗号分隔，如 tdcomp.company,tdcomp.employee）。
     */
    private String cdcTableIncludeList = "tdcomp.company,tdcomp.employee,tdcomp.branch,tdcomp.partner";

    /**
     * CDC 写入重试次数。
     */
    private int cdcMaxRetries = 3;

    /**
     * CDC 写入重试间隔（毫秒）。
     */
    private long cdcRetryDelayMs = 5000L;

    /**
     * CDC 目标库写入并行度。
     */
    private int cdcWriteParallelism = 4;

    /**
     * CDC 死信队列 Topic 名称。
     */
    private String cdcDltTopic = "cdc_dlt";

    /**
     * 是否将 CDC 变更与 Neo4j/Qdrant 写入审计写入本地文件。
     */
    private boolean cdcAuditLogEnabled = true;

    /**
     * CDC 审计日志路径（JSONL）；相对路径基于进程工作目录。
     */
    private String cdcAuditLogPath = "data/qa_logs/cdc_sync.jsonl";

    /** 配置域 scope（enterprise / crm 等） */
    private String configScope = "enterprise";

    /** 配置来源：classpath | mysql | mysql_fallback */
    private String configSource = "mysql_fallback";

    /** 启动时若 MySQL 无配置则种子导入 classpath */
    private boolean configSeedOnStartup = true;

    /** 文档语料库编码（qa_document_corpus.corpus_code） */
    private String documentCorpusCode = "enterprise_mysql_compiled";

    /** 文档召回优先读 qa_document_chunk */
    private boolean documentFromDb = true;

    /** 用户上传文档切块后同步写入 Qdrant（P5） */
    private boolean documentVectorIngestEnabled = true;

    /** 实体快照来源：jsonl | mysql | mysql_fallback */
    private String entitySnapshotSource = "mysql_fallback";

    /** 审计事件双写 qa_audit_event */
    private boolean auditMysqlEnabled = true;

    /** 富图 P0：重建 Neo4j 时的批大小（仅作运维参考，本类不直接读取） */
    private int graphRebuildBatchSize = 200;

    /** 富图 P0：重建时是否先清空图谱（默认 false，避免误操作） */
    private boolean graphWipeOnRebuild = false;

    /** 富图 P0：CDC 写入范围声明（classpath:qa/cdc-write-scope.json） */
    private String cdcWriteScopeRef = "qa/cdc-write-scope.json";

    /** 富图 P0：长文本截断上限（与 graph-node-definitions.json#global.truncation.maxChars 对齐） */
    private int graphTruncateMaxChars = 4000;

    /**
     * 清单 JSON 文件路径（仅运维配置本地路径）；含 {@code tables} 数组与可选 {@code jobName}。
     */
    private String structuredIngestManifestPath = "";

    /**
     * 任务日志路径；空则使用 {@link #docsDir} 的父目录下 {@code structured_ingest_job.log}。
     */
    private String structuredIngestJobLogPath = "";

    private int neo4jConnectTimeoutMs = 5000;

    /**
     * 外部服务超时（毫秒）：Neo4j 查询。
     */
    private int neo4jQueryTimeoutMs = 10000;

    /**
     * 外部服务超时（毫秒）：Qdrant HTTP 请求。
     */
    private int qdrantTimeoutMs = 8000;

    /**
     * 外部服务超时（毫秒）：MySQL 查询。
     */
    private int mysqlQueryTimeoutSeconds = 15;

    /**
     * 外部服务超时（毫秒）：MiniMax API 请求（流式/非流式）。
     */
    private int minimaxTimeoutMs = 60000;

    /**
     * 歧义指代短语配置（逗号分隔），按 scope 分类。
     * 格式：scope.ambiguous-phrases（如 qa.ambiguous-phrases.enterprise=咱们公司,我们公司,...）
     */
    private Map<String, String> ambiguousPhrases = new HashMap<>();

    /**
     * 实体类型 → 表名映射（逗号分隔多表）。
     * 用于检索时追加查询 supplemental tables。
     * 格式：entity-type.table（如 qa.assistant.entity-table-mapping.employee=tdcomp.employee）
     */
    private Map<String, String> entityTableMapping = new HashMap<>();

    public String getAssistantName() {
        return assistantName;
    }

    public void setAssistantName(String assistantName) {
        this.assistantName = assistantName;
    }

    public int getMaxStructuredIngestRows() {
        return maxStructuredIngestRows;
    }

    public void setMaxStructuredIngestRows(int maxStructuredIngestRows) {
        this.maxStructuredIngestRows = maxStructuredIngestRows;
    }

    public boolean isFlywayEnabled() {
        return flywayEnabled;
    }

    public void setFlywayEnabled(boolean flywayEnabled) {
        this.flywayEnabled = flywayEnabled;
    }

    public String getMysqlPersonRoleEmployeeTable() {
        return mysqlPersonRoleEmployeeTable;
    }

    public void setMysqlPersonRoleEmployeeTable(String mysqlPersonRoleEmployeeTable) {
        this.mysqlPersonRoleEmployeeTable = mysqlPersonRoleEmployeeTable;
    }

    public String getMysqlPersonRoleCompanyTable() {
        return mysqlPersonRoleCompanyTable;
    }

    public void setMysqlPersonRoleCompanyTable(String mysqlPersonRoleCompanyTable) {
        this.mysqlPersonRoleCompanyTable = mysqlPersonRoleCompanyTable;
    }

    public int getMaxSchemaExportTables() {
        return maxSchemaExportTables;
    }

    public void setMaxSchemaExportTables(int maxSchemaExportTables) {
        this.maxSchemaExportTables = maxSchemaExportTables;
    }

    public int getMaxSchemaExportChars() {
        return maxSchemaExportChars;
    }

    public void setMaxSchemaExportChars(int maxSchemaExportChars) {
        this.maxSchemaExportChars = maxSchemaExportChars;
    }

    public int getMaxSchemaAssessmentCatalogChars() {
        return maxSchemaAssessmentCatalogChars;
    }

    public void setMaxSchemaAssessmentCatalogChars(int maxSchemaAssessmentCatalogChars) {
        this.maxSchemaAssessmentCatalogChars = maxSchemaAssessmentCatalogChars;
    }

    public int getMaxSchemaAssessmentResponseChars() {
        return maxSchemaAssessmentResponseChars;
    }

    public void setMaxSchemaAssessmentResponseChars(int maxSchemaAssessmentResponseChars) {
        this.maxSchemaAssessmentResponseChars = maxSchemaAssessmentResponseChars;
    }

    public boolean isStructuredIngestScheduleEnabled() {
        return structuredIngestScheduleEnabled;
    }

    public void setStructuredIngestScheduleEnabled(boolean structuredIngestScheduleEnabled) {
        this.structuredIngestScheduleEnabled = structuredIngestScheduleEnabled;
    }

    public String getStructuredIngestScheduleCron() {
        return structuredIngestScheduleCron;
    }

    public void setStructuredIngestScheduleCron(String structuredIngestScheduleCron) {
        this.structuredIngestScheduleCron = structuredIngestScheduleCron;
    }

    public String getStructuredIngestManifestPath() {
        return structuredIngestManifestPath;
    }

    public void setStructuredIngestManifestPath(String structuredIngestManifestPath) {
        this.structuredIngestManifestPath = structuredIngestManifestPath;
    }

    public String getStructuredIngestJobLogPath() {
        return structuredIngestJobLogPath;
    }

    public void setStructuredIngestJobLogPath(String structuredIngestJobLogPath) {
        this.structuredIngestJobLogPath = structuredIngestJobLogPath;
    }

    public String getDocsDir() {
        return docsDir;
    }

    public void setDocsDir(String docsDir) {
        this.docsDir = docsDir;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? null : apiKey.trim();
    }

    public int getRetrievalTopK() {
        return retrievalTopK;
    }

    public void setRetrievalTopK(int retrievalTopK) {
        this.retrievalTopK = retrievalTopK;
    }

    public boolean isVectorEnabled() {
        return vectorEnabled;
    }

    public void setVectorEnabled(boolean vectorEnabled) {
        this.vectorEnabled = vectorEnabled;
    }

    public String getQdrantUrl() {
        return qdrantUrl;
    }

    public void setQdrantUrl(String qdrantUrl) {
        this.qdrantUrl = qdrantUrl;
    }

    public String getQdrantCollection() {
        return qdrantCollection;
    }

    public void setQdrantCollection(String qdrantCollection) {
        this.qdrantCollection = qdrantCollection;
    }

    public int getVectorTopK() {
        return vectorTopK;
    }

    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    public int getVectorEmbeddingDim() {
        return vectorEmbeddingDim;
    }

    public void setVectorEmbeddingDim(int vectorEmbeddingDim) {
        this.vectorEmbeddingDim = vectorEmbeddingDim;
    }

    public String getQdrantActiveLearningCollection() {
        return qdrantActiveLearningCollection;
    }

    public void setQdrantActiveLearningCollection(String qdrantActiveLearningCollection) {
        this.qdrantActiveLearningCollection = qdrantActiveLearningCollection;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getDashscopeApiKey() {
        return dashscopeApiKey;
    }

    public void setDashscopeApiKey(String dashscopeApiKey) {
        this.dashscopeApiKey = dashscopeApiKey == null ? "" : dashscopeApiKey.trim();
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getEmbeddingApiUrl() {
        return embeddingApiUrl;
    }

    public void setEmbeddingApiUrl(String embeddingApiUrl) {
        this.embeddingApiUrl = embeddingApiUrl;
    }

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public int getEmbeddingTimeoutMs() {
        return embeddingTimeoutMs;
    }

    public void setEmbeddingTimeoutMs(int embeddingTimeoutMs) {
        this.embeddingTimeoutMs = embeddingTimeoutMs;
    }

    public boolean isUnifiedRetrievalEnabled() {
        return unifiedRetrievalEnabled;
    }

    public void setUnifiedRetrievalEnabled(boolean unifiedRetrievalEnabled) {
        this.unifiedRetrievalEnabled = unifiedRetrievalEnabled;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public String getRerankApiUrl() {
        return rerankApiUrl;
    }

    public void setRerankApiUrl(String rerankApiUrl) {
        this.rerankApiUrl = rerankApiUrl;
    }

    public int getRerankCandidateMax() {
        return rerankCandidateMax;
    }

    public void setRerankCandidateMax(int rerankCandidateMax) {
        this.rerankCandidateMax = rerankCandidateMax;
    }

    public int getRecallVectorTopK() {
        return recallVectorTopK;
    }

    public void setRecallVectorTopK(int recallVectorTopK) {
        this.recallVectorTopK = recallVectorTopK;
    }

    public int getRecallGraphTopK() {
        return recallGraphTopK;
    }

    public void setRecallGraphTopK(int recallGraphTopK) {
        this.recallGraphTopK = recallGraphTopK;
    }

    public int getRecallGraphPersonRoleTopK() {
        return recallGraphPersonRoleTopK;
    }

    public void setRecallGraphPersonRoleTopK(int recallGraphPersonRoleTopK) {
        this.recallGraphPersonRoleTopK = recallGraphPersonRoleTopK;
    }

    public boolean isIntentLlmEnabled() {
        return intentLlmEnabled;
    }

    public void setIntentLlmEnabled(boolean intentLlmEnabled) {
        this.intentLlmEnabled = intentLlmEnabled;
    }

    public boolean isAgentMultiStepEnabled() {
        return agentMultiStepEnabled;
    }

    public void setAgentMultiStepEnabled(boolean agentMultiStepEnabled) {
        this.agentMultiStepEnabled = agentMultiStepEnabled;
    }

    public boolean isIntentRuleFirstForStructured() {
        return intentRuleFirstForStructured;
    }

    public void setIntentRuleFirstForStructured(boolean intentRuleFirstForStructured) {
        this.intentRuleFirstForStructured = intentRuleFirstForStructured;
    }

    public boolean isIntentLlmAssistStructured() {
        return intentLlmAssistStructured;
    }

    public void setIntentLlmAssistStructured(boolean intentLlmAssistStructured) {
        this.intentLlmAssistStructured = intentLlmAssistStructured;
    }

    public double getIntentLlmEnrichMinConfidence() {
        return intentLlmEnrichMinConfidence;
    }

    public void setIntentLlmEnrichMinConfidence(double intentLlmEnrichMinConfidence) {
        this.intentLlmEnrichMinConfidence = intentLlmEnrichMinConfidence;
    }

    public int getIntentLlmTimeoutMs() {
        return intentLlmTimeoutMs;
    }

    public void setIntentLlmTimeoutMs(int intentLlmTimeoutMs) {
        this.intentLlmTimeoutMs = intentLlmTimeoutMs;
    }

    public boolean isAnswerGateEnabled() {
        return answerGateEnabled;
    }

    public void setAnswerGateEnabled(boolean answerGateEnabled) {
        this.answerGateEnabled = answerGateEnabled;
    }

    public int getAnswerGateMinEvidenceCount() {
        return answerGateMinEvidenceCount;
    }

    public void setAnswerGateMinEvidenceCount(int answerGateMinEvidenceCount) {
        this.answerGateMinEvidenceCount = answerGateMinEvidenceCount;
    }

    public double getAnswerGateMinTopScore() {
        return answerGateMinTopScore;
    }

    public void setAnswerGateMinTopScore(double answerGateMinTopScore) {
        this.answerGateMinTopScore = answerGateMinTopScore;
    }

    public boolean isAnswerGateBlockOnUnknownIntent() {
        return answerGateBlockOnUnknownIntent;
    }

    public void setAnswerGateBlockOnUnknownIntent(boolean answerGateBlockOnUnknownIntent) {
        this.answerGateBlockOnUnknownIntent = answerGateBlockOnUnknownIntent;
    }

    public boolean isAlignmentStrict() {
        return alignmentStrict;
    }

    public void setAlignmentStrict(boolean alignmentStrict) {
        this.alignmentStrict = alignmentStrict;
    }

    public double getAlignmentLowOverlapThreshold() {
        return alignmentLowOverlapThreshold;
    }

    public void setAlignmentLowOverlapThreshold(double alignmentLowOverlapThreshold) {
        this.alignmentLowOverlapThreshold = alignmentLowOverlapThreshold;
    }

    public boolean isMysqlEnabled() {
        return mysqlEnabled;
    }

    public void setMysqlEnabled(boolean mysqlEnabled) {
        this.mysqlEnabled = mysqlEnabled;
    }

    public String getMysqlUrl() {
        return mysqlUrl;
    }

    public void setMysqlUrl(String mysqlUrl) {
        this.mysqlUrl = mysqlUrl;
    }

    public String getMysqlUsername() {
        return mysqlUsername;
    }

    public void setMysqlUsername(String mysqlUsername) {
        this.mysqlUsername = mysqlUsername;
    }

    public String getMysqlPassword() {
        return mysqlPassword;
    }

    public void setMysqlPassword(String mysqlPassword) {
        this.mysqlPassword = mysqlPassword;
    }

    public String getMysqlSchema() {
        return mysqlSchema;
    }

    public void setMysqlSchema(String mysqlSchema) {
        this.mysqlSchema = mysqlSchema;
    }

    public int getMysqlTopK() {
        return mysqlTopK;
    }

    public void setMysqlTopK(int mysqlTopK) {
        this.mysqlTopK = mysqlTopK;
    }

    public int getMysqlPerTableLimit() {
        return mysqlPerTableLimit;
    }

    public void setMysqlPerTableLimit(int mysqlPerTableLimit) {
        this.mysqlPerTableLimit = mysqlPerTableLimit;
    }

    public int getNeo4jConnectTimeoutMs() {
        return neo4jConnectTimeoutMs;
    }

    public void setNeo4jConnectTimeoutMs(int neo4jConnectTimeoutMs) {
        this.neo4jConnectTimeoutMs = neo4jConnectTimeoutMs;
    }

    public int getNeo4jQueryTimeoutMs() {
        return neo4jQueryTimeoutMs;
    }

    public void setNeo4jQueryTimeoutMs(int neo4jQueryTimeoutMs) {
        this.neo4jQueryTimeoutMs = neo4jQueryTimeoutMs;
    }

    public int getQdrantTimeoutMs() {
        return qdrantTimeoutMs;
    }

    public void setQdrantTimeoutMs(int qdrantTimeoutMs) {
        this.qdrantTimeoutMs = qdrantTimeoutMs;
    }

    public int getMysqlQueryTimeoutSeconds() {
        return mysqlQueryTimeoutSeconds;
    }

    public void setMysqlQueryTimeoutSeconds(int mysqlQueryTimeoutSeconds) {
        this.mysqlQueryTimeoutSeconds = mysqlQueryTimeoutSeconds;
    }

    public int getMinimaxTimeoutMs() {
        return minimaxTimeoutMs;
    }

    public void setMinimaxTimeoutMs(int minimaxTimeoutMs) {
        this.minimaxTimeoutMs = minimaxTimeoutMs;
    }

    public Map<String, String> getAmbiguousPhrases() {
        return ambiguousPhrases;
    }

    public void setAmbiguousPhrases(Map<String, String> ambiguousPhrases) {
        this.ambiguousPhrases = ambiguousPhrases;
    }

    /**
     * 获取指定 scope 的歧义短语列表。
     */
    public List<String> getAmbiguousPhrasesForScope(String scope) {
        String phrases = ambiguousPhrases.get(scope);
        if (phrases == null || phrases.isBlank()) {
            return List.of();
        }
        return Arrays.asList(phrases.split(","));
    }

    public Map<String, String> getEntityTableMapping() {
        return entityTableMapping;
    }

    public void setEntityTableMapping(Map<String, String> entityTableMapping) {
        this.entityTableMapping = entityTableMapping;
    }

    /**
     * 获取指定实体类型对应的表名列表。
     */
    public List<String> getTablesForEntityType(String entityType) {
        String tables = entityTableMapping.get(entityType);
        if (tables == null || tables.isBlank()) {
            return List.of();
        }
        return Arrays.asList(tables.split(","));
    }

    public String getBusinessMysqlUrl() {
        return businessMysqlUrl;
    }

    public void setBusinessMysqlUrl(String businessMysqlUrl) {
        this.businessMysqlUrl = businessMysqlUrl;
    }

    public String getBusinessMysqlUsername() {
        return businessMysqlUsername;
    }

    public void setBusinessMysqlUsername(String businessMysqlUsername) {
        this.businessMysqlUsername = businessMysqlUsername;
    }

    public String getBusinessMysqlPassword() {
        return businessMysqlPassword;
    }

    public void setBusinessMysqlPassword(String businessMysqlPassword) {
        this.businessMysqlPassword = businessMysqlPassword;
    }

    public int getEmployeeBaseLimit() {
        return employeeBaseLimit;
    }

    public void setEmployeeBaseLimit(int employeeBaseLimit) {
        this.employeeBaseLimit = employeeBaseLimit;
    }

    public boolean getEnableScheduledSync() {
        return enableScheduledSync;
    }

    public void setEnableScheduledSync(boolean enableScheduledSync) {
        this.enableScheduledSync = enableScheduledSync;
    }

    public String getKnowledgeSyncDomain() {
        return knowledgeSyncDomain;
    }

    public void setKnowledgeSyncDomain(String knowledgeSyncDomain) {
        this.knowledgeSyncDomain = knowledgeSyncDomain;
    }

    public int getKnowledgeSyncDefaultSinceHours() {
        return knowledgeSyncDefaultSinceHours;
    }

    public void setKnowledgeSyncDefaultSinceHours(int knowledgeSyncDefaultSinceHours) {
        this.knowledgeSyncDefaultSinceHours = knowledgeSyncDefaultSinceHours;
    }

    public boolean isKnowledgeSyncIncrementalScheduledEnabled() {
        return knowledgeSyncIncrementalScheduledEnabled;
    }

    public void setKnowledgeSyncIncrementalScheduledEnabled(boolean knowledgeSyncIncrementalScheduledEnabled) {
        this.knowledgeSyncIncrementalScheduledEnabled = knowledgeSyncIncrementalScheduledEnabled;
    }

    public boolean isKnowledgeSyncWatermarkWatchOnly() {
        return knowledgeSyncWatermarkWatchOnly;
    }

    public void setKnowledgeSyncWatermarkWatchOnly(boolean knowledgeSyncWatermarkWatchOnly) {
        this.knowledgeSyncWatermarkWatchOnly = knowledgeSyncWatermarkWatchOnly;
    }

    public long getKnowledgeSyncPollIntervalMs() {
        return knowledgeSyncPollIntervalMs;
    }

    public void setKnowledgeSyncPollIntervalMs(long knowledgeSyncPollIntervalMs) {
        this.knowledgeSyncPollIntervalMs = knowledgeSyncPollIntervalMs;
    }

    public String getKnowledgeSyncWatermarkColumn() {
        return knowledgeSyncWatermarkColumn;
    }

    public void setKnowledgeSyncWatermarkColumn(String knowledgeSyncWatermarkColumn) {
        this.knowledgeSyncWatermarkColumn = knowledgeSyncWatermarkColumn;
    }

    // ==================== CDC / Debezium Getters & Setters ====================

    public boolean isCdcEnabled() {
        return cdcEnabled;
    }

    public void setCdcEnabled(boolean cdcEnabled) {
        this.cdcEnabled = cdcEnabled;
    }

    public String getCdcKafkaBootstrapServers() {
        return cdcKafkaBootstrapServers;
    }

    public void setCdcKafkaBootstrapServers(String cdcKafkaBootstrapServers) {
        this.cdcKafkaBootstrapServers = cdcKafkaBootstrapServers;
    }

    public String getCdcKafkaGroupId() {
        return cdcKafkaGroupId;
    }

    public void setCdcKafkaGroupId(String cdcKafkaGroupId) {
        this.cdcKafkaGroupId = cdcKafkaGroupId;
    }

    public String getCdcDatabaseIncludeList() {
        return cdcDatabaseIncludeList;
    }

    public void setCdcDatabaseIncludeList(String cdcDatabaseIncludeList) {
        this.cdcDatabaseIncludeList = cdcDatabaseIncludeList;
    }

    public String getCdcTableIncludeList() {
        return cdcTableIncludeList;
    }

    public void setCdcTableIncludeList(String cdcTableIncludeList) {
        this.cdcTableIncludeList = cdcTableIncludeList;
    }

    public int getCdcMaxRetries() {
        return cdcMaxRetries;
    }

    public void setCdcMaxRetries(int cdcMaxRetries) {
        this.cdcMaxRetries = cdcMaxRetries;
    }

    public long getCdcRetryDelayMs() {
        return cdcRetryDelayMs;
    }

    public void setCdcRetryDelayMs(long cdcRetryDelayMs) {
        this.cdcRetryDelayMs = cdcRetryDelayMs;
    }

    public int getCdcWriteParallelism() {
        return cdcWriteParallelism;
    }

    public void setCdcWriteParallelism(int cdcWriteParallelism) {
        this.cdcWriteParallelism = cdcWriteParallelism;
    }

    public String getCdcDltTopic() {
        return cdcDltTopic;
    }

    public void setCdcDltTopic(String cdcDltTopic) {
        this.cdcDltTopic = cdcDltTopic;
    }

    public boolean isCdcAuditLogEnabled() {
        return cdcAuditLogEnabled;
    }

    public void setCdcAuditLogEnabled(boolean cdcAuditLogEnabled) {
        this.cdcAuditLogEnabled = cdcAuditLogEnabled;
    }

    public String getCdcAuditLogPath() {
        return cdcAuditLogPath;
    }

    public void setCdcAuditLogPath(String cdcAuditLogPath) {
        this.cdcAuditLogPath = cdcAuditLogPath;
    }

    public String getConfigScope() {
        return configScope;
    }

    public void setConfigScope(String configScope) {
        this.configScope = configScope;
    }

    public String getConfigSource() {
        return configSource;
    }

    public void setConfigSource(String configSource) {
        this.configSource = configSource;
    }

    public boolean isConfigSeedOnStartup() {
        return configSeedOnStartup;
    }

    public void setConfigSeedOnStartup(boolean configSeedOnStartup) {
        this.configSeedOnStartup = configSeedOnStartup;
    }

    public String getDocumentCorpusCode() {
        return documentCorpusCode;
    }

    public void setDocumentCorpusCode(String documentCorpusCode) {
        this.documentCorpusCode = documentCorpusCode;
    }

    public boolean isDocumentFromDb() {
        return documentFromDb;
    }

    public void setDocumentFromDb(boolean documentFromDb) {
        this.documentFromDb = documentFromDb;
    }

    public boolean isDocumentVectorIngestEnabled() {
        return documentVectorIngestEnabled;
    }

    public void setDocumentVectorIngestEnabled(boolean documentVectorIngestEnabled) {
        this.documentVectorIngestEnabled = documentVectorIngestEnabled;
    }

    public String getEntitySnapshotSource() {
        return entitySnapshotSource;
    }

    public void setEntitySnapshotSource(String entitySnapshotSource) {
        this.entitySnapshotSource = entitySnapshotSource;
    }

    public boolean isAuditMysqlEnabled() {
        return auditMysqlEnabled;
    }

    public void setAuditMysqlEnabled(boolean auditMysqlEnabled) {
        this.auditMysqlEnabled = auditMysqlEnabled;
    }

    public int getGraphRebuildBatchSize() {
        return graphRebuildBatchSize;
    }

    public void setGraphRebuildBatchSize(int graphRebuildBatchSize) {
        this.graphRebuildBatchSize = graphRebuildBatchSize;
    }

    public boolean isGraphWipeOnRebuild() {
        return graphWipeOnRebuild;
    }

    public void setGraphWipeOnRebuild(boolean graphWipeOnRebuild) {
        this.graphWipeOnRebuild = graphWipeOnRebuild;
    }

    public String getCdcWriteScopeRef() {
        return cdcWriteScopeRef;
    }

    public void setCdcWriteScopeRef(String cdcWriteScopeRef) {
        this.cdcWriteScopeRef = cdcWriteScopeRef;
    }

    public int getGraphTruncateMaxChars() {
        return graphTruncateMaxChars;
    }

    public void setGraphTruncateMaxChars(int graphTruncateMaxChars) {
        this.graphTruncateMaxChars = graphTruncateMaxChars;
    }
}
