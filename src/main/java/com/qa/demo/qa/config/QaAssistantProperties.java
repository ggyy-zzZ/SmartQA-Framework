package com.qa.demo.qa.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

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
    private String qdrantCollection = "enterprise_knowledge_v1";
    private int vectorTopK = 6;
    private int vectorEmbeddingDim = 768;
    private boolean mysqlEnabled = true;
    private String mysqlUrl = "jdbc:mysql://localhost:3306/assistant?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    private String mysqlUsername = "root";
    private String mysqlPassword = "root";
    private String mysqlSchema = "assistant";
    private int mysqlTopK = 6;
    private int mysqlPerTableLimit = 3;

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
     * 清单 JSON 文件路径（仅运维配置本地路径）；含 {@code tables} 数组与可选 {@code jobName}。
     */
    private String structuredIngestManifestPath = "";

    /**
     * 任务日志路径；空则使用 {@link #docsDir} 的父目录下 {@code structured_ingest_job.log}。
     */
    private String structuredIngestJobLogPath = "";

    /**
     * 外部服务超时（毫秒）：Neo4j 连接。
     */
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
}
