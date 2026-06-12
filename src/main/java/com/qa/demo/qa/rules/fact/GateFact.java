package com.qa.demo.qa.rules.fact;

import com.qa.demo.qa.rules.QaQueryType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Drools Fact：答案闸门评估上下文。
 * <p>
 * DRL 规则根据当前 queryType / evidence 形态，叠加 {@code rejectionReasons}，并可设置
 * {@code allowGenerate} / {@code allowByLlmAssist}。
 * <p>
 * 注意：DRL 不直接决定最终 {@code rejectReason}（避免规则短路 LLM 兜底），Java 侧
 * 在收集 rejectionReasons 后再决定 LLM 兜底是否调用。
 */
public class GateFact {
    private String queryType;
    private QaQueryType queryTypeEnum;
    private String facet;
    private int evidenceCount;
    private double maxScore;
    private double avgScore;
    private final Set<String> schemaIds = new LinkedHashSet<>();
    private final Set<String> sourcePrefixes = new LinkedHashSet<>();
    private final List<String> rejectionReasons = new ArrayList<>();
    private boolean allowGenerate;
    private boolean allowByLlmAssist;

    public GateFact() {
    }

    public String getQueryType() { return queryType; }
    public void setQueryType(String queryType) { this.queryType = queryType; }

    public QaQueryType getQueryTypeEnum() { return queryTypeEnum; }
    public void setQueryTypeEnum(QaQueryType queryTypeEnum) { this.queryTypeEnum = queryTypeEnum; }

    public String getFacet() { return facet; }
    public void setFacet(String facet) { this.facet = facet; }

    public int getEvidenceCount() { return evidenceCount; }
    public void setEvidenceCount(int evidenceCount) { this.evidenceCount = evidenceCount; }

    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }

    public double getAvgScore() { return avgScore; }
    public void setAvgScore(double avgScore) { this.avgScore = avgScore; }

    public Set<String> getSchemaIds() { return schemaIds; }
    public Set<String> getSourcePrefixes() { return sourcePrefixes; }
    public List<String> getRejectionReasons() { return rejectionReasons; }

    public boolean isAllowGenerate() { return allowGenerate; }
    public void setAllowGenerate(boolean allowGenerate) { this.allowGenerate = allowGenerate; }

    public boolean isAllowByLlmAssist() { return allowByLlmAssist; }
    public void setAllowByLlmAssist(boolean allowByLlmAssist) { this.allowByLlmAssist = allowByLlmAssist; }

    public void addRejectionReason(String reason) {
        if (reason != null && !reason.isBlank()) {
            this.rejectionReasons.add(reason);
        }
    }

    public boolean hasFlexibleCertSource() {
        for (String prefix : sourcePrefixes) {
            String p = prefix == null ? "" : prefix.toLowerCase();
            if (p.contains("document-chunk-db")
                    || p.contains("enterprise_mysql_compiled")
                    || p.startsWith("mysql-structured-")
                    || p.contains("neo4j-certificate-instance")) {
                return true;
            }
        }
        return false;
    }
}
