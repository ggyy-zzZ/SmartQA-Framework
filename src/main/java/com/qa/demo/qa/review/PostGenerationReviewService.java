package com.qa.demo.qa.review;

import com.qa.demo.qa.alignment.EvidenceAlignmentService;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 生成后硬校验：拒答语义、模板兜底、证据对齐低重合时回写 canAnswer。
 */
@Service
public class PostGenerationReviewService {

    private static final String[] REFUSAL_MARKERS = {
            "无法回答", "不能回答", "无法根据", "证据不足", "未包含", "没有足够", "无法确定",
            "无法准确", "暂无法", "无法提供", "无法列出", "无法确认", "无法从当前证据"
    };

    private final QaAssistantProperties properties;
    private final EvidenceAlignmentService evidenceAlignmentService;

    public PostGenerationReviewService(
            QaAssistantProperties properties,
            EvidenceAlignmentService evidenceAlignmentService
    ) {
        this.properties = properties;
        this.evidenceAlignmentService = evidenceAlignmentService;
    }

    public record Adjustment(boolean canAnswer, double confidence) {
    }

    public Adjustment adjust(
            String question,
            String answer,
            List<ContextChunk> evidence,
            boolean canAnswer,
            double confidence,
            boolean degraded
    ) {
        if (!canAnswer) {
            return new Adjustment(false, confidence);
        }
        if (degraded) {
            return new Adjustment(false, Math.min(confidence, 0.20));
        }
        if (answerIndicatesRefusal(answer)) {
            return new Adjustment(false, Math.min(confidence, 0.25));
        }
        if (properties.isAlignmentStrict()) {
            EvidenceAlignmentService.AlignmentInsight insight =
                    evidenceAlignmentService.analyze(question, answer, evidence, true);
            if (insight.lowOverlap()) {
                return new Adjustment(false, Math.min(confidence, 0.25));
            }
        }
        return new Adjustment(canAnswer, confidence);
    }

    static boolean answerIndicatesRefusal(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String normalized = answer.strip();
        for (String marker : REFUSAL_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
