package com.qa.demo.qa.rules.fact;

import com.qa.demo.qa.rules.QaQueryType;

import java.util.ArrayList;
import java.util.List;

/**
 * Drools Fact：意图路由决策上下文。
 * <p>
 * 用于 {@code qa-intent-routing} session；DRL 写入 {@code skipActiveLearningBootstrap} /
 * {@code preferActiveLearning} / {@code includeCompiledDocs} 等布尔位，Java 侧读回执行分支。
 */
public class IntentFact {
    private String queryType;
    private QaQueryType queryTypeEnum;
    private String intent;
    private boolean skipActiveLearningBootstrap;
    private boolean preferActiveLearning;
    private boolean includeCompiledDocs;
    private boolean needsPersonClarification;
    private boolean needsCompanyClarification;
    private String clarificationReason;
    private final List<String> matchedKeywords = new ArrayList<>();

    public IntentFact() {
    }

    public String getQueryType() { return queryType; }
    public void setQueryType(String queryType) { this.queryType = queryType; }

    public QaQueryType getQueryTypeEnum() { return queryTypeEnum; }
    public void setQueryTypeEnum(QaQueryType queryTypeEnum) { this.queryTypeEnum = queryTypeEnum; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public boolean isSkipActiveLearningBootstrap() { return skipActiveLearningBootstrap; }
    public void setSkipActiveLearningBootstrap(boolean v) { this.skipActiveLearningBootstrap = v; }

    public boolean isPreferActiveLearning() { return preferActiveLearning; }
    public void setPreferActiveLearning(boolean v) { this.preferActiveLearning = v; }

    public boolean isIncludeCompiledDocs() { return includeCompiledDocs; }
    public void setIncludeCompiledDocs(boolean v) { this.includeCompiledDocs = v; }

    public boolean isNeedsPersonClarification() { return needsPersonClarification; }
    public void setNeedsPersonClarification(boolean v) { this.needsPersonClarification = v; }

    public boolean isNeedsCompanyClarification() { return needsCompanyClarification; }
    public void setNeedsCompanyClarification(boolean v) { this.needsCompanyClarification = v; }

    public String getClarificationReason() { return clarificationReason; }
    public void setClarificationReason(String s) { this.clarificationReason = s; }

    public List<String> getMatchedKeywords() { return matchedKeywords; }
    public void addMatchedKeyword(String k) { if (k != null) this.matchedKeywords.add(k); }
}
