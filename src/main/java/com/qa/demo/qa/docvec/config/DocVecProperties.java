package com.qa.demo.qa.docvec.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Doc-RAG 实验路径配置（与主 qa.assistant 隔离）。
 */
@Component
@ConfigurationProperties(prefix = "qa.docvec")
public class DocVecProperties {

    private boolean enabled = true;
    private String qdrantUrl = "http://localhost:6333";
    private String collection = "enterprise_doc_rag_v1";
    private int qdrantTimeoutMs = 15000;
    private int vectorTopK = 12;
    private int rerankTopK = 6;
    private boolean rerankEnabled = true;
    private int maxProfileCharsForLlm = 12000;
    private int maxProfileCharsForSql = 48000;
    private boolean sqlEnabled = true;
    private int sqlMaxRows = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getQdrantUrl() {
        return qdrantUrl;
    }

    public void setQdrantUrl(String qdrantUrl) {
        this.qdrantUrl = qdrantUrl;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getQdrantTimeoutMs() {
        return qdrantTimeoutMs;
    }

    public void setQdrantTimeoutMs(int qdrantTimeoutMs) {
        this.qdrantTimeoutMs = qdrantTimeoutMs;
    }

    public int getVectorTopK() {
        return vectorTopK;
    }

    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    public int getRerankTopK() {
        return rerankTopK;
    }

    public void setRerankTopK(int rerankTopK) {
        this.rerankTopK = rerankTopK;
    }

    public boolean isRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public int getMaxProfileCharsForLlm() {
        return maxProfileCharsForLlm;
    }

    public void setMaxProfileCharsForLlm(int maxProfileCharsForLlm) {
        this.maxProfileCharsForLlm = maxProfileCharsForLlm;
    }

    public int getMaxProfileCharsForSql() {
        return maxProfileCharsForSql;
    }

    public void setMaxProfileCharsForSql(int maxProfileCharsForSql) {
        this.maxProfileCharsForSql = maxProfileCharsForSql;
    }

    public boolean isSqlEnabled() {
        return sqlEnabled;
    }

    public void setSqlEnabled(boolean sqlEnabled) {
        this.sqlEnabled = sqlEnabled;
    }

    public int getSqlMaxRows() {
        return sqlMaxRows;
    }

    public void setSqlMaxRows(int sqlMaxRows) {
        this.sqlMaxRows = sqlMaxRows;
    }
}
