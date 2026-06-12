package com.qa.demo.qa.rules.fact;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.rules.QaQueryType;

/**
 * Drools Fact：一条 {@link ContextChunk} 的规则化形态。
 * <p>
 * DRL 可设置 {@code decision}（KEEP/DROP/DEMOTE/OVERRIDE）以及 {@code adjustedScore}，
 * Java 侧再根据 decision 把 Fact 还原成 ContextChunk 输出。
 */
public class EvidenceFact {
    public static final String DECISION_KEEP = "KEEP";
    public static final String DECISION_DROP = "DROP";
    public static final String DECISION_DEMOTE = "DEMOTE";
    public static final String DECISION_OVERRIDE = "OVERRIDE";

    private String chunkId;
    private String source;
    private String anchorId;
    private String field;
    private String displayLabel;
    private String snippet;
    private String evidenceSchema;
    private double score;
    private String queryTypeAtFetch;
    private QaQueryType queryTypeEnum;
    private String stage;
    private String decision;
    private double adjustedScore;

    public EvidenceFact() {
    }

    public static EvidenceFact fromChunk(ContextChunk chunk, QaQueryType qt) {
        EvidenceFact f = new EvidenceFact();
        f.chunkId = chunk.source() + "|" + chunk.anchorId() + "|" + chunk.field() + "|" + chunk.displayLabel();
        f.source = chunk.source();
        f.anchorId = chunk.anchorId();
        f.field = chunk.field();
        f.displayLabel = chunk.displayLabel();
        f.snippet = chunk.snippet();
        f.evidenceSchema = chunk.evidenceSchema();
        f.score = chunk.score();
        f.adjustedScore = chunk.score();
        f.queryTypeAtFetch = qt == null ? "" : qt.name();
        f.queryTypeEnum = qt == null ? QaQueryType.UNKNOWN : qt;
        f.stage = "candidate";
        f.decision = DECISION_KEEP;
        return f;
    }

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getAnchorId() { return anchorId; }
    public void setAnchorId(String anchorId) { this.anchorId = anchorId; }

    public String getField() { return field; }
    public void setField(String field) { this.field = field; }

    public String getDisplayLabel() { return displayLabel; }
    public void setDisplayLabel(String displayLabel) { this.displayLabel = displayLabel; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public String getEvidenceSchema() { return evidenceSchema; }
    public void setEvidenceSchema(String evidenceSchema) { this.evidenceSchema = evidenceSchema; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public String getQueryTypeAtFetch() { return queryTypeAtFetch; }
    public void setQueryTypeAtFetch(String queryTypeAtFetch) { this.queryTypeAtFetch = queryTypeAtFetch; }

    public QaQueryType getQueryTypeEnum() { return queryTypeEnum; }
    public void setQueryTypeEnum(QaQueryType queryTypeEnum) { this.queryTypeEnum = queryTypeEnum; }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public double getAdjustedScore() { return adjustedScore; }
    public void setAdjustedScore(double adjustedScore) { this.adjustedScore = adjustedScore; }
}
