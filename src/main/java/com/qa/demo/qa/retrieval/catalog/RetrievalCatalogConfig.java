package com.qa.demo.qa.retrieval.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索证据维度目录配置（classpath:qa/retrieval-catalog.json）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetrievalCatalogConfig {

    private List<NeedInferenceRule> needInferenceRules = new ArrayList<>();
    /** 按 facet/granularity/槽位匹配的执行策略。 */
    private List<NeedExecutionProfile> needExecutionProfiles = new ArrayList<>();
    /** 按 granularity 匹配的检索思考文案（无 profile 命中时的兜底）。 */
    private Map<String, String> thinkingMessagesByGranularity = new LinkedHashMap<>();
    /** LLM 策略与规则 need 冲突时，按 reason 前缀保留规则 need。 */
    private List<String> llmMergePreserveReasonPrefixes = new ArrayList<>();
    private List<DimensionDef> dimensions = new ArrayList<>();
    private List<GateRule> gateRules = new ArrayList<>();

    public List<NeedInferenceRule> getNeedInferenceRules() {
        return needInferenceRules;
    }

    public void setNeedInferenceRules(List<NeedInferenceRule> needInferenceRules) {
        this.needInferenceRules = needInferenceRules == null ? new ArrayList<>() : needInferenceRules;
    }

    public List<NeedExecutionProfile> getNeedExecutionProfiles() {
        return needExecutionProfiles;
    }

    public void setNeedExecutionProfiles(List<NeedExecutionProfile> needExecutionProfiles) {
        this.needExecutionProfiles = needExecutionProfiles == null ? new ArrayList<>() : needExecutionProfiles;
    }

    public Map<String, String> getThinkingMessagesByGranularity() {
        return thinkingMessagesByGranularity;
    }

    public void setThinkingMessagesByGranularity(Map<String, String> thinkingMessagesByGranularity) {
        this.thinkingMessagesByGranularity = thinkingMessagesByGranularity == null
                ? new LinkedHashMap<>()
                : thinkingMessagesByGranularity;
    }

    public List<String> getLlmMergePreserveReasonPrefixes() {
        return llmMergePreserveReasonPrefixes;
    }

    public void setLlmMergePreserveReasonPrefixes(List<String> llmMergePreserveReasonPrefixes) {
        this.llmMergePreserveReasonPrefixes = llmMergePreserveReasonPrefixes == null
                ? new ArrayList<>()
                : llmMergePreserveReasonPrefixes;
    }

    public List<DimensionDef> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<DimensionDef> dimensions) {
        this.dimensions = dimensions == null ? new ArrayList<>() : dimensions;
    }

    public List<GateRule> getGateRules() {
        return gateRules;
    }

    public void setGateRules(List<GateRule> gateRules) {
        this.gateRules = gateRules == null ? new ArrayList<>() : gateRules;
    }

    public static class NeedInferenceRule {
        private String id;
        private List<String> anyKeywords = new ArrayList<>();
        private List<String> allKeywords = new ArrayList<>();
        private List<String> contextKeywords = new ArrayList<>();
        private List<String> excludeKeywords = new ArrayList<>();
        private NeedTemplate need = new NeedTemplate();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public List<String> getAnyKeywords() {
            return anyKeywords;
        }

        public void setAnyKeywords(List<String> anyKeywords) {
            this.anyKeywords = anyKeywords;
        }

        public List<String> getAllKeywords() {
            return allKeywords;
        }

        public void setAllKeywords(List<String> allKeywords) {
            this.allKeywords = allKeywords;
        }

        public List<String> getContextKeywords() {
            return contextKeywords;
        }

        public void setContextKeywords(List<String> contextKeywords) {
            this.contextKeywords = contextKeywords;
        }

        public List<String> getExcludeKeywords() {
            return excludeKeywords;
        }

        public void setExcludeKeywords(List<String> excludeKeywords) {
            this.excludeKeywords = excludeKeywords;
        }

        public NeedTemplate getNeed() {
            return need;
        }

        public void setNeed(NeedTemplate need) {
            this.need = need == null ? new NeedTemplate() : need;
        }
    }

    public static class NeedTemplate {
        private String facet = "";
        private String granularity = "";
        private boolean listExpected;
        private ExecutionTemplate execution;

        public String getFacet() {
            return facet;
        }

        public void setFacet(String facet) {
            this.facet = facet;
        }

        public String getGranularity() {
            return granularity;
        }

        public void setGranularity(String granularity) {
            this.granularity = granularity;
        }

        public boolean isListExpected() {
            return listExpected;
        }

        public void setListExpected(boolean listExpected) {
            this.listExpected = listExpected;
        }

        public ExecutionTemplate getExecution() {
            return execution;
        }

        public void setExecution(ExecutionTemplate execution) {
            this.execution = execution;
        }
    }

    /**
     * 按 InformationNeed + 可选槽位约束匹配检索执行策略。
     */
    public static class NeedExecutionProfile {
        private String id;
        private NeedExecutionMatch match = new NeedExecutionMatch();
        private ExecutionTemplate execution = new ExecutionTemplate();
        private BehaviorsTemplate behaviors = new BehaviorsTemplate();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public NeedExecutionMatch getMatch() {
            return match;
        }

        public void setMatch(NeedExecutionMatch match) {
            this.match = match == null ? new NeedExecutionMatch() : match;
        }

        public ExecutionTemplate getExecution() {
            return execution;
        }

        public void setExecution(ExecutionTemplate execution) {
            this.execution = execution == null ? new ExecutionTemplate() : execution;
        }

        public BehaviorsTemplate getBehaviors() {
            return behaviors;
        }

        public void setBehaviors(BehaviorsTemplate behaviors) {
            this.behaviors = behaviors == null ? new BehaviorsTemplate() : behaviors;
        }
    }

    /**
     * 与执行 profile 绑定的流程行为（澄清、思考文案、LLM 合并策略等），由配置驱动而非 Java facet 硬编码。
     */
    public static class BehaviorsTemplate {
        /** 规则推断的 need 在 LLM 判 semantic/structured 时是否保留 */
        private boolean preserveAgainstLlmSemantic;
        /** 人物指称不清时是否触发人物澄清 */
        private boolean personClarification;
        /** 检索时倾向纳入 compiled 文档（叙述/政策类） */
        private boolean preferCompiledDocs;
        /** 检索思考阶段展示文案 */
        private String thinkingMessage = "";

        public boolean isPreserveAgainstLlmSemantic() {
            return preserveAgainstLlmSemantic;
        }

        public void setPreserveAgainstLlmSemantic(boolean preserveAgainstLlmSemantic) {
            this.preserveAgainstLlmSemantic = preserveAgainstLlmSemantic;
        }

        public boolean isPersonClarification() {
            return personClarification;
        }

        public void setPersonClarification(boolean personClarification) {
            this.personClarification = personClarification;
        }

        public boolean isPreferCompiledDocs() {
            return preferCompiledDocs;
        }

        public void setPreferCompiledDocs(boolean preferCompiledDocs) {
            this.preferCompiledDocs = preferCompiledDocs;
        }

        public String getThinkingMessage() {
            return thinkingMessage;
        }

        public void setThinkingMessage(String thinkingMessage) {
            this.thinkingMessage = thinkingMessage == null ? "" : thinkingMessage;
        }
    }

    public static class NeedExecutionMatch {
        private List<String> facets = new ArrayList<>();
        private List<String> granularities = new ArrayList<>();
        /** 未配置则忽略；true/false 须与 need.listExpected 一致 */
        private Boolean listExpected;
        /** 未配置则忽略；true=须 intent 有人名，false=须无人名焦点 */
        private Boolean requiresPerson;

        public List<String> getFacets() {
            return facets;
        }

        public void setFacets(List<String> facets) {
            this.facets = facets;
        }

        public List<String> getGranularities() {
            return granularities;
        }

        public void setGranularities(List<String> granularities) {
            this.granularities = granularities;
        }

        public Boolean getListExpected() {
            return listExpected;
        }

        public void setListExpected(Boolean listExpected) {
            this.listExpected = listExpected;
        }

        public Boolean getRequiresPerson() {
            return requiresPerson;
        }

        public void setRequiresPerson(Boolean requiresPerson) {
            this.requiresPerson = requiresPerson;
        }
    }

    /**
     * 检索通路策略（早退 route、截断、纠偏实体、专用 list 路径等）。
     */
    public static class ExecutionTemplate {
        /** default | dedicated_list | dedicated_certificate */
        private String path = "default";
        private String routeLabel = "";
        private boolean skipTruncation;
        private boolean includeCompiledDocs;
        private String correctionEntityKind = "company";
        private boolean expandRecallTopK;
        private boolean skipEmployeeBaseAppend;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path == null ? "default" : path;
        }

        public String getRouteLabel() {
            return routeLabel;
        }

        public void setRouteLabel(String routeLabel) {
            this.routeLabel = routeLabel;
        }

        public boolean isSkipTruncation() {
            return skipTruncation;
        }

        public void setSkipTruncation(boolean skipTruncation) {
            this.skipTruncation = skipTruncation;
        }

        public boolean isIncludeCompiledDocs() {
            return includeCompiledDocs;
        }

        public void setIncludeCompiledDocs(boolean includeCompiledDocs) {
            this.includeCompiledDocs = includeCompiledDocs;
        }

        public String getCorrectionEntityKind() {
            return correctionEntityKind;
        }

        public void setCorrectionEntityKind(String correctionEntityKind) {
            this.correctionEntityKind = correctionEntityKind;
        }

        public boolean isExpandRecallTopK() {
            return expandRecallTopK;
        }

        public void setExpandRecallTopK(boolean expandRecallTopK) {
            this.expandRecallTopK = expandRecallTopK;
        }

        public boolean isSkipEmployeeBaseAppend() {
            return skipEmployeeBaseAppend;
        }

        public void setSkipEmployeeBaseAppend(boolean skipEmployeeBaseAppend) {
            this.skipEmployeeBaseAppend = skipEmployeeBaseAppend;
        }
    }

    public static class DimensionDef {
        private String dimensionId;
        private String evidenceSchema;
        private String source;
        private DimensionMatch match = new DimensionMatch();
        private RetrieverDef retriever = new RetrieverDef();

        public String getDimensionId() {
            return dimensionId;
        }

        public void setDimensionId(String dimensionId) {
            this.dimensionId = dimensionId;
        }

        public String getEvidenceSchema() {
            return evidenceSchema;
        }

        public void setEvidenceSchema(String evidenceSchema) {
            this.evidenceSchema = evidenceSchema;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public DimensionMatch getMatch() {
            return match;
        }

        public void setMatch(DimensionMatch match) {
            this.match = match == null ? new DimensionMatch() : match;
        }

        public RetrieverDef getRetriever() {
            return retriever;
        }

        public void setRetriever(RetrieverDef retriever) {
            this.retriever = retriever == null ? new RetrieverDef() : retriever;
        }
    }

    public static class DimensionMatch {
        private List<String> facets = new ArrayList<>();
        private List<String> granularities = new ArrayList<>();

        public List<String> getFacets() {
            return facets;
        }

        public void setFacets(List<String> facets) {
            this.facets = facets;
        }

        public List<String> getGranularities() {
            return granularities;
        }

        public void setGranularities(List<String> granularities) {
            this.granularities = granularities;
        }
    }

    public static class RetrieverDef {
        private String type = "";
        private String resource = "";
        private String jsonField = "";
        private String displayLabel = "";
        private String anchorId = "";

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getResource() {
            return resource;
        }

        public void setResource(String resource) {
            this.resource = resource;
        }

        public String getJsonField() {
            return jsonField;
        }

        public void setJsonField(String jsonField) {
            this.jsonField = jsonField;
        }

        public String getDisplayLabel() {
            return displayLabel;
        }

        public void setDisplayLabel(String displayLabel) {
            this.displayLabel = displayLabel;
        }

        public String getAnchorId() {
            return anchorId;
        }

        public void setAnchorId(String anchorId) {
            this.anchorId = anchorId;
        }
    }

    public static class GateRule {
        private List<String> granularities = new ArrayList<>();
        private List<String> facets = new ArrayList<>();
        private List<String> requiredSchemas = new ArrayList<>();
        private List<String> acceptableSources = new ArrayList<>();

        public List<String> getGranularities() {
            return granularities;
        }

        public void setGranularities(List<String> granularities) {
            this.granularities = granularities;
        }

        public List<String> getFacets() {
            return facets;
        }

        public void setFacets(List<String> facets) {
            this.facets = facets;
        }

        public List<String> getRequiredSchemas() {
            return requiredSchemas;
        }

        public void setRequiredSchemas(List<String> requiredSchemas) {
            this.requiredSchemas = requiredSchemas;
        }

        public List<String> getAcceptableSources() {
            return acceptableSources;
        }

        public void setAcceptableSources(List<String> acceptableSources) {
            this.acceptableSources = acceptableSources == null ? new ArrayList<>() : acceptableSources;
        }
    }
}
