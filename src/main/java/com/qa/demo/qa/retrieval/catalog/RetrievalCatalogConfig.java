package com.qa.demo.qa.retrieval.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索证据维度目录配置（classpath:qa/retrieval-catalog.json）。
 */
public class RetrievalCatalogConfig {

    private List<NeedInferenceRule> needInferenceRules = new ArrayList<>();
    private Map<String, NeedTemplate> queryTypeMapping = new LinkedHashMap<>();
    private List<DimensionDef> dimensions = new ArrayList<>();
    private List<GateRule> gateRules = new ArrayList<>();

    public List<NeedInferenceRule> getNeedInferenceRules() {
        return needInferenceRules;
    }

    public void setNeedInferenceRules(List<NeedInferenceRule> needInferenceRules) {
        this.needInferenceRules = needInferenceRules == null ? new ArrayList<>() : needInferenceRules;
    }

    public Map<String, NeedTemplate> getQueryTypeMapping() {
        return queryTypeMapping;
    }

    public void setQueryTypeMapping(Map<String, NeedTemplate> queryTypeMapping) {
        this.queryTypeMapping = queryTypeMapping == null ? new LinkedHashMap<>() : queryTypeMapping;
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
     * queryType 对应的检索通路策略（早退 route、截断、纠偏实体、专用 list 路径等）。
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
