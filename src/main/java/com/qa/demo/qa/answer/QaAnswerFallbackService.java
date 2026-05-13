package com.qa.demo.qa.answer;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.core.ContextChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 置信度计算与模型不可用时的兜底回答拼装。
 */
@Service
public class QaAnswerFallbackService {

    public double calcConfidence(List<ContextChunk> evidence) {
        if (evidence.isEmpty()) {
            return 0.20;
        }
        double maxScore = evidence.stream().mapToDouble(ContextChunk::score).max().orElse(0.0);
        double scorePart = Math.min(maxScore / 20.0, 0.7);
        double sizePart = Math.min(evidence.size(), 5) * 0.05;
        return Math.min(0.95, Math.max(0.30, scorePart + sizePart));
    }

    public String buildFallbackAnswer(String question, List<ContextChunk> evidence) {
        if (evidence.isEmpty()) {
            return KnowledgeAssistantPrompts.insufficientEvidenceGeneralHint();
        }
        List<String> lines = new ArrayList<>();
        lines.add("系统正在生成详细解释，先给你一版简要结论：");
        lines.add("问题：" + question);
        lines.add("已找到与问题相关的知识片段，要点如下：");
        for (int i = 0; i < Math.min(evidence.size(), 4); i++) {
            ContextChunk c = evidence.get(i);
            lines.add(String.format("%d) %s：已命中相关片段。", i + 1, c.companyName()));
        }
        lines.add("提示：稍后重试可获取更完整、可读性更高的说明。");
        return String.join("\n", lines);
    }

    public String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return "model_unavailable";
        }
        if (message.length() > 180) {
            return message.substring(0, 180);
        }
        return message;
    }
}
