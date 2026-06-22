package com.qa.demo.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 业务规则配置 - 从 business-rules.json 加载。
 * 所有特定业务的硬编码规则应在此配置文件中声明。
 */
@Component
@ConfigurationProperties(prefix = "qa.business-rules")
public class BusinessRulesConfig {

    private IntentRules intentRules = new IntentRules();
    private IntentRouting intentRouting = new IntentRouting();
    private ConversationScope conversationScope = new ConversationScope();
    private AnswerGate answerGate = new AnswerGate();
    private DataSources dataSources = new DataSources();
    private List<CorrectionRule> correctionRules = new ArrayList<>();
    private RetrievalThresholds retrievalThresholds = new RetrievalThresholds();
    private List<FilterFieldCoverageRule> filterFieldCoverageRules = new ArrayList<>();
    private Map<String, String> outputContracts = new HashMap<>();

    public IntentRouting getIntentRouting() { return intentRouting; }
    public void setIntentRouting(IntentRouting intentRouting) { this.intentRouting = intentRouting; }

    public ConversationScope getConversationScope() { return conversationScope; }
    public void setConversationScope(ConversationScope conversationScope) {
        this.conversationScope = conversationScope;
    }

    public AnswerGate getAnswerGate() { return answerGate; }
    public void setAnswerGate(AnswerGate answerGate) { this.answerGate = answerGate; }

    // ============= Intent Rules =============

    public IntentRules getIntentRules() { return intentRules; }
    public void setIntentRules(IntentRules intentRules) { this.intentRules = intentRules; }

    public static class IntentRules {
        private PersonNamePatterns personNamePatterns = new PersonNamePatterns();

        public PersonNamePatterns getPersonNamePatterns() { return personNamePatterns; }
        public void setPersonNamePatterns(PersonNamePatterns p) { this.personNamePatterns = p; }
    }

    public static class PersonNamePatterns {
        private List<String> beforeAction = new ArrayList<>();
        private List<String> beforeResign = new ArrayList<>();
        private List<String> leadingName = new ArrayList<>();
        private List<String> pronounBlocklist = new ArrayList<>();
        private List<String> honorificSuffixes = new ArrayList<>();

        public List<Pattern> getBeforeActionPatterns() {
            return compilePatterns(beforeAction);
        }
        public List<Pattern> getBeforeResignPatterns() {
            return compilePatterns(beforeResign);
        }
        public List<Pattern> getLeadingNamePatterns() {
            return compilePatterns(leadingName);
        }
        public List<String> getPronounBlocklist() { return pronounBlocklist; }
        public List<String> getHonorificSuffixes() { return honorificSuffixes; }

        // Getters for configuration loading (beforeAction -> getBeforeAction)
        public List<String> getBeforeAction() { return beforeAction; }
        public List<String> getBeforeResign() { return beforeResign; }
        public List<String> getLeadingName() { return leadingName; }

        /**
         * 生成敬称后缀的正则表达式，如 (先生|女士|小姐|老师)$。
         */
        public Pattern getHonorificSuffixPattern() {
            if (honorificSuffixes == null || honorificSuffixes.isEmpty()) {
                return Pattern.compile("(?<!^)$"); // 从不匹配
            }
            String suffix = String.join("|", honorificSuffixes);
            return Pattern.compile("(" + suffix + ")$");
        }

        private List<Pattern> compilePatterns(List<String> patterns) {
            List<Pattern> compiled = new ArrayList<>();
            for (String p : patterns) {
                try { compiled.add(Pattern.compile(p)); } catch (Exception ignored) {}
            }
            return compiled;
        }
    }

    // ============= Conversation scope (断链 / 全局列表 / 经营状态推断) =============

    public static class ConversationScope {
        private List<String> breakContextPhrases = new ArrayList<>();
        private List<String> globalListMarkers = new ArrayList<>();
        private List<String> globalListContextKeywords = new ArrayList<>();
        /** 枚举/目录类问句（如「经营状态包含哪些种类」），不应继承上轮 region/主体 hints */
        private List<String> catalogQuestionMarkers = new ArrayList<>();
        private List<String> continuationMarkers = new ArrayList<>();
        private List<String> continuationExcludePatterns = new ArrayList<>();
        private int continuationMaxLength = 36;
        private OperatingStatusScopeRules operatingStatus = new OperatingStatusScopeRules();

        public List<String> getBreakContextPhrases() { return breakContextPhrases; }
        public void setBreakContextPhrases(List<String> breakContextPhrases) {
            this.breakContextPhrases = breakContextPhrases;
        }
        public List<String> getGlobalListMarkers() { return globalListMarkers; }
        public void setGlobalListMarkers(List<String> globalListMarkers) {
            this.globalListMarkers = globalListMarkers;
        }
        public List<String> getGlobalListContextKeywords() { return globalListContextKeywords; }
        public void setGlobalListContextKeywords(List<String> globalListContextKeywords) {
            this.globalListContextKeywords = globalListContextKeywords;
        }
        public List<String> getCatalogQuestionMarkers() { return catalogQuestionMarkers; }
        public void setCatalogQuestionMarkers(List<String> catalogQuestionMarkers) {
            this.catalogQuestionMarkers = catalogQuestionMarkers;
        }
        public List<String> getContinuationMarkers() { return continuationMarkers; }
        public void setContinuationMarkers(List<String> continuationMarkers) {
            this.continuationMarkers = continuationMarkers;
        }
        public List<String> getContinuationExcludePatterns() { return continuationExcludePatterns; }
        public void setContinuationExcludePatterns(List<String> continuationExcludePatterns) {
            this.continuationExcludePatterns = continuationExcludePatterns;
        }
        public int getContinuationMaxLength() { return continuationMaxLength; }
        public void setContinuationMaxLength(int continuationMaxLength) {
            this.continuationMaxLength = continuationMaxLength;
        }
        public OperatingStatusScopeRules getOperatingStatus() { return operatingStatus; }
        public void setOperatingStatus(OperatingStatusScopeRules operatingStatus) {
            this.operatingStatus = operatingStatus;
        }
    }

    public static class OperatingStatusScopeRules {
        private List<String> activeMarkers = new ArrayList<>();
        private List<String> inactiveMarkers = new ArrayList<>();
        private List<String> negationPrefixes = new ArrayList<>();
        private List<String> inactivePhrases = new ArrayList<>();
        private List<String> certificateContextKeywords = new ArrayList<>();

        public List<String> getActiveMarkers() { return activeMarkers; }
        public void setActiveMarkers(List<String> activeMarkers) { this.activeMarkers = activeMarkers; }
        public List<String> getInactiveMarkers() { return inactiveMarkers; }
        public void setInactiveMarkers(List<String> inactiveMarkers) { this.inactiveMarkers = inactiveMarkers; }
        public List<String> getNegationPrefixes() { return negationPrefixes; }
        public void setNegationPrefixes(List<String> negationPrefixes) { this.negationPrefixes = negationPrefixes; }
        public List<String> getInactivePhrases() { return inactivePhrases; }
        public void setInactivePhrases(List<String> inactivePhrases) { this.inactivePhrases = inactivePhrases; }
        public List<String> getCertificateContextKeywords() { return certificateContextKeywords; }
        public void setCertificateContextKeywords(List<String> certificateContextKeywords) {
            this.certificateContextKeywords = certificateContextKeywords;
        }
    }

    // ============= Intent routing (strategy 槽位 / 追问) =============

    public static class IntentRouting {
        private List<String> structuredListStrategies = new ArrayList<>();
        private Map<String, String> defaultIntentByStrategy = new HashMap<>();
        private List<StrategySlotRequirement> strategySlotRequirements = new ArrayList<>();
        private List<String> followUpReferenceMarkers = new ArrayList<>();
        private List<String> filterRulePrefixes = new ArrayList<>();
        private List<String> compiledDocumentKeywords = new ArrayList<>();

        public List<String> getStructuredListStrategies() { return structuredListStrategies; }
        public void setStructuredListStrategies(List<String> s) { this.structuredListStrategies = s; }
        public Map<String, String> getDefaultIntentByStrategy() { return defaultIntentByStrategy; }
        public void setDefaultIntentByStrategy(Map<String, String> m) { this.defaultIntentByStrategy = m; }
        public List<StrategySlotRequirement> getStrategySlotRequirements() { return strategySlotRequirements; }
        public void setStrategySlotRequirements(List<StrategySlotRequirement> r) { this.strategySlotRequirements = r; }
        public List<String> getFollowUpReferenceMarkers() { return followUpReferenceMarkers; }
        public void setFollowUpReferenceMarkers(List<String> f) { this.followUpReferenceMarkers = f; }
        public List<String> getFilterRulePrefixes() { return filterRulePrefixes; }
        public void setFilterRulePrefixes(List<String> filterRulePrefixes) { this.filterRulePrefixes = filterRulePrefixes; }
        public List<String> getCompiledDocumentKeywords() { return compiledDocumentKeywords; }
        public void setCompiledDocumentKeywords(List<String> compiledDocumentKeywords) {
            this.compiledDocumentKeywords = compiledDocumentKeywords;
        }
    }

    public static class StrategySlotRequirement {
        private String retrievalStrategy;
        private boolean requiresPerson;
        private boolean requiresCompany;
        private boolean requiresRoleFocus;

        public String getRetrievalStrategy() { return retrievalStrategy; }
        public void setRetrievalStrategy(String s) { this.retrievalStrategy = s; }
        public boolean isRequiresPerson() { return requiresPerson; }
        public void setRequiresPerson(boolean r) { this.requiresPerson = r; }
        public boolean isRequiresCompany() { return requiresCompany; }
        public void setRequiresCompany(boolean r) { this.requiresCompany = r; }
        public boolean isRequiresRoleFocus() { return requiresRoleFocus; }
        public void setRequiresRoleFocus(boolean r) { this.requiresRoleFocus = r; }
    }

    public static class AnswerGate {
        private List<AnswerGateNeedRule> requiredEvidenceByNeed = new ArrayList<>();

        public List<AnswerGateNeedRule> getRequiredEvidenceByNeed() { return requiredEvidenceByNeed; }
        public void setRequiredEvidenceByNeed(List<AnswerGateNeedRule> r) {
            this.requiredEvidenceByNeed = r;
        }
    }

    public static class AnswerGateNeedRule {
        private String facet;
        private String granularity;
        private boolean requiresPerson;
        private List<String> schemaIds = new ArrayList<>();

        public String getFacet() { return facet; }
        public void setFacet(String f) { this.facet = f; }
        public String getGranularity() { return granularity; }
        public void setGranularity(String g) { this.granularity = g; }
        public boolean isRequiresPerson() { return requiresPerson; }
        public void setRequiresPerson(boolean r) { this.requiresPerson = r; }
        public List<String> getSchemaIds() { return schemaIds; }
        public void setSchemaIds(List<String> s) { this.schemaIds = s; }
    }

    // ============= Data Sources =============

    public DataSources getDataSources() { return dataSources; }
    public void setDataSources(DataSources d) { this.dataSources = d; }

    public static class DataSources {
        private List<StructuredQueryConfig> structuredQueries = new ArrayList<>();

        public List<StructuredQueryConfig> getStructuredQueries() { return structuredQueries; }
        public void setStructuredQueries(List<StructuredQueryConfig> s) { this.structuredQueries = s; }
    }

    public static class CertificateRetrievalConfig {
        private String personQueryConfigId = "person_stewardship";
        private String companyQueryConfigId = "certificate_by_company";
        private String graphInstanceSource = "neo4j-certificate-instance";
        private boolean forbidUnscopedGlobalScan = true;
        private List<String> activeCompanyOperatingStatusCodes = new ArrayList<>();
        private List<String> activeCertificateStatusValues = new ArrayList<>();

        public String getPersonQueryConfigId() { return personQueryConfigId; }
        public void setPersonQueryConfigId(String id) { this.personQueryConfigId = id; }
        public String getCompanyQueryConfigId() { return companyQueryConfigId; }
        public void setCompanyQueryConfigId(String id) { this.companyQueryConfigId = id; }
        public String getGraphInstanceSource() { return graphInstanceSource; }
        public void setGraphInstanceSource(String s) { this.graphInstanceSource = s; }
        public boolean isForbidUnscopedGlobalScan() { return forbidUnscopedGlobalScan; }
        public void setForbidUnscopedGlobalScan(boolean f) { this.forbidUnscopedGlobalScan = f; }
        public List<String> getActiveCompanyOperatingStatusCodes() { return activeCompanyOperatingStatusCodes; }
        public void setActiveCompanyOperatingStatusCodes(List<String> c) { this.activeCompanyOperatingStatusCodes = c; }
        public List<String> getActiveCertificateStatusValues() { return activeCertificateStatusValues; }
        public void setActiveCertificateStatusValues(List<String> v) { this.activeCertificateStatusValues = v; }
    }

    private CertificateRetrievalConfig certificateRetrieval = new CertificateRetrievalConfig();

    public CertificateRetrievalConfig getCertificateRetrieval() { return certificateRetrieval; }
    public void setCertificateRetrieval(CertificateRetrievalConfig c) { this.certificateRetrieval = c; }

    public static class StructuredQueryConfig {
        private String id;
        private String description;
        private String table;
        private String deleteflagColumn = "deleteflag";
        private String entityIdColumn = "id";
        /** employee_ids（角色列匹配）| company_ids（scopeColumn IN 过滤） */
        private String scopeKind = "employee_ids";
        private String scopeColumn = "company_id";
        private List<String> boundStrategies = new ArrayList<>();
        private List<ProjectionColumnConfig> projections = new ArrayList<>();
        private List<String> displayNameColumns = new ArrayList<>();
        private List<RoleColumnConfig> roleColumns = new ArrayList<>();
        private Map<String, String> enumMappings = new HashMap<>();
        private Map<String, String> statusNormalization = new HashMap<>();
        private List<String> statusActiveValues = new ArrayList<>();
        private List<String> supplementalTables = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getDescription() { return description; }
        public void setDescription(String d) { this.description = d; }
        public String getTable() { return table; }
        public void setTable(String t) { this.table = t; }
        public String getDeleteflagColumn() { return deleteflagColumn; }
        public void setDeleteflagColumn(String c) { this.deleteflagColumn = c; }
        public String getEntityIdColumn() { return entityIdColumn; }
        public void setEntityIdColumn(String c) { this.entityIdColumn = c; }
        public List<String> getDisplayNameColumns() { return displayNameColumns; }
        public void setDisplayNameColumns(List<String> c) { this.displayNameColumns = c; }
        public List<RoleColumnConfig> getRoleColumns() { return roleColumns; }
        public void setRoleColumns(List<RoleColumnConfig> r) { this.roleColumns = r; }
        public Map<String, String> getEnumMappings() { return enumMappings; }
        public void setEnumMappings(Map<String, String> e) { this.enumMappings = e; }
        public Map<String, String> getStatusNormalization() { return statusNormalization; }
        public void setStatusNormalization(Map<String, String> s) { this.statusNormalization = s; }
        public List<String> getSupplementalTables() { return supplementalTables; }
        public void setSupplementalTables(List<String> s) { this.supplementalTables = s; }
        public String getScopeKind() { return scopeKind; }
        public void setScopeKind(String s) { this.scopeKind = s; }
        public String getScopeColumn() { return scopeColumn; }
        public void setScopeColumn(String c) { this.scopeColumn = c; }
        public List<String> getBoundStrategies() { return boundStrategies; }
        public void setBoundStrategies(List<String> t) { this.boundStrategies = t; }
        public List<ProjectionColumnConfig> getProjections() { return projections; }
        public void setProjections(List<ProjectionColumnConfig> p) { this.projections = p; }
        public List<String> getStatusActiveValues() { return statusActiveValues; }
        public void setStatusActiveValues(List<String> v) { this.statusActiveValues = v; }

        public boolean appliesToStrategy(String strategyToken) {
            if (strategyToken == null || strategyToken.isBlank()) {
                return false;
            }
            if (id != null && strategyToken.equalsIgnoreCase(id)) {
                return true;
            }
            for (String bound : boundStrategies) {
                if (bound != null && strategyToken.equalsIgnoreCase(bound)) {
                    return true;
                }
            }
            return false;
        }

        public boolean isCompanyScope() {
            return "company_ids".equalsIgnoreCase(scopeKind);
        }
    }

    public static class ProjectionColumnConfig {
        private String column;
        private String label;
        private String enumField;

        public String getColumn() { return column; }
        public void setColumn(String c) { this.column = c; }
        public String getLabel() { return label; }
        public void setLabel(String l) { this.label = l; }
        public String getEnumField() { return enumField; }
        public void setEnumField(String e) { this.enumField = e; }
    }

    public static class RoleColumnConfig {
        private String column;
        private String label;
        private String type;

        public String getColumn() { return column; }
        public void setColumn(String c) { this.column = c; }
        public String getLabel() { return label; }
        public void setLabel(String l) { this.label = l; }
        public String getType() { return type; }
        public void setType(String t) { this.type = t; }
    }

    // ============= Correction Rules =============

    public List<CorrectionRule> getCorrectionRules() { return correctionRules; }
    public void setCorrectionRules(List<CorrectionRule> c) { this.correctionRules = c; }

    public static class CorrectionRule {
        private String id;
        private String description;
        private List<String> prefixPatterns = new ArrayList<>();
        private String entityPattern;
        private String entityType;
        private List<String> suffixes = new ArrayList<>();
        private List<String> relatedKeywords = new ArrayList<>();

        private transient Pattern compiledEntityPattern;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getDescription() { return description; }
        public void setDescription(String d) { this.description = d; }
        public List<String> getPrefixPatterns() { return prefixPatterns; }
        public void setPrefixPatterns(List<String> p) { this.prefixPatterns = p; }
        public String getEntityPattern() { return entityPattern; }
        public void setEntityPattern(String p) { this.entityPattern = p; }
        public String getEntityType() { return entityType; }
        public void setEntityType(String e) { this.entityType = e; }
        public List<String> getSuffixes() { return suffixes; }
        public void setSuffixes(List<String> s) { this.suffixes = s; }
        public List<String> getRelatedKeywords() { return relatedKeywords; }
        public void setRelatedKeywords(List<String> r) { this.relatedKeywords = r; }

        public Pattern getCompiledEntityPattern() {
            if (compiledEntityPattern == null && entityPattern != null) {
                try { compiledEntityPattern = Pattern.compile(entityPattern); }
                catch (Exception ignored) {}
            }
            return compiledEntityPattern;
        }

        public List<Pattern> getCompiledPrefixPatterns() {
            List<Pattern> compiled = new ArrayList<>();
            for (String p : prefixPatterns) {
                try { compiled.add(Pattern.compile(p)); } catch (Exception ignored) {}
            }
            return compiled;
        }
    }

    // ============= Retrieval Thresholds =============

    public RetrievalThresholds getRetrievalThresholds() { return retrievalThresholds; }
    public void setRetrievalThresholds(RetrievalThresholds r) { this.retrievalThresholds = r; }

    public static class RetrievalThresholds {
        private List<SourceThreshold> sourceThresholds = new ArrayList<>();

        public List<SourceThreshold> getSourceThresholds() { return sourceThresholds; }
        public void setSourceThresholds(List<SourceThreshold> s) { this.sourceThresholds = s; }
    }

    public static class SourceThreshold {
        private String source;
        private String retrievalStrategy;
        private int minCount;
        private String description;

        public String getSource() { return source; }
        public void setSource(String s) { this.source = s; }
        public String getRetrievalStrategy() { return retrievalStrategy; }
        public void setRetrievalStrategy(String s) { this.retrievalStrategy = s; }
        public int getMinCount() { return minCount; }
        public void setMinCount(int m) { this.minCount = m; }
        public String getDescription() { return description; }
        public void setDescription(String d) { this.description = d; }
    }

    public List<FilterFieldCoverageRule> getFilterFieldCoverageRules() { return filterFieldCoverageRules; }
    public void setFilterFieldCoverageRules(List<FilterFieldCoverageRule> rules) {
        this.filterFieldCoverageRules = rules;
    }

    public static class FilterFieldCoverageRule {
        private String id;
        private String displayLabel;
        private List<String> questionAnyKeywords = new ArrayList<>();
        private List<String> filterIntentKeywords = new ArrayList<>();
        private List<String> snippetMarkers = new ArrayList<>();
        /** 业务库源表列名（P3b 专用检索） */
        private List<String> sourceColumns = new ArrayList<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getDisplayLabel() { return displayLabel; }
        public void setDisplayLabel(String displayLabel) { this.displayLabel = displayLabel; }
        public List<String> getQuestionAnyKeywords() { return questionAnyKeywords; }
        public void setQuestionAnyKeywords(List<String> k) { this.questionAnyKeywords = k; }
        public List<String> getFilterIntentKeywords() { return filterIntentKeywords; }
        public void setFilterIntentKeywords(List<String> k) { this.filterIntentKeywords = k; }
        public List<String> getSnippetMarkers() { return snippetMarkers; }
        public void setSnippetMarkers(List<String> m) { this.snippetMarkers = m; }
        public List<String> getSourceColumns() { return sourceColumns; }
        public void setSourceColumns(List<String> sourceColumns) { this.sourceColumns = sourceColumns; }
    }

    // ============= Output Contracts =============

    public Map<String, String> getOutputContracts() { return outputContracts; }
    public void setOutputContracts(Map<String, String> o) { this.outputContracts = o; }
}