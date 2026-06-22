package com.qa.demo.qa.retrieval;

/**
 * 检索/呈现阶段的截断元数据，供响应与对用户提示使用。
 */
public record EvidenceTruncationMeta(
        int candidatesConsidered,
        int presentedToModel,
        int configuredCap,
        boolean truncated
) {
    public static EvidenceTruncationMeta of(int candidatesConsidered, int presentedToModel, int configuredCap) {
        int considered = Math.max(0, candidatesConsidered);
        int presented = Math.max(0, presentedToModel);
        int cap = Math.max(1, configuredCap);
        boolean truncated = considered > presented;
        return new EvidenceTruncationMeta(considered, presented, cap, truncated);
    }

    public int omittedCount() {
        return truncated ? Math.max(0, candidatesConsidered - presentedToModel) : 0;
    }
}
