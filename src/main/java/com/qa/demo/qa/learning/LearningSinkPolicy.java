package com.qa.demo.qa.learning;

/**
 * 主动学习写入策略：由「沉淀方案」解析后传入 {@link ActiveLearningService#learnWithSinkPolicy}，
 * 仅允许关闭某一路，不改变表名 / 集合名 / Cypher 形态（安全白名单）。
 */
public record LearningSinkPolicy(boolean mysql, boolean qdrant, boolean neo4j, int keywordLimit) {

    public static final int DEFAULT_KEYWORD_LIMIT = 12;

    public LearningSinkPolicy {
        keywordLimit = Math.max(4, Math.min(24, keywordLimit));
    }

    public static LearningSinkPolicy allEnabled() {
        return new LearningSinkPolicy(true, true, true, DEFAULT_KEYWORD_LIMIT);
    }

    public boolean anySinkEnabled() {
        return mysql || qdrant || neo4j;
    }
}
