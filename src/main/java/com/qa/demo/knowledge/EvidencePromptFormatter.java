package com.qa.demo.knowledge;

import com.qa.demo.qa.core.ContextChunk;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 将检索证据格式化为模型可读文本；使用来源/锚点/展示名等中性标签。
 */
public final class EvidencePromptFormatter {

    private EvidencePromptFormatter() {
    }

    public static String format(List<ContextChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "无可用证据。";
        }
        return chunks.stream()
                .map(EvidencePromptFormatter::formatOne)
                .collect(Collectors.joining("\n"));
    }

    private static String formatOne(ContextChunk chunk) {
        String anchor = chunk.anchorId() == null ? "" : chunk.anchorId().trim();
        String label = chunk.displayLabel() == null ? "" : chunk.displayLabel().trim();
        String kind = chunk.entityKind() == null ? "" : chunk.entityKind().trim();
        String topic = chunk.field() == null ? "" : chunk.field().trim();
        String snippet = chunk.snippet() == null ? "" : chunk.snippet().trim();
        String source = chunk.source() == null ? "" : chunk.source().trim();
        String schema = chunk.evidenceSchema() == null ? "" : chunk.evidenceSchema().trim();
        return String.format(
                "- [来源:%s] [形态:%s] [实体:%s] [锚点:%s] [展示名:%s] [主题:%s] 片段:%s",
                source.isBlank() ? "-" : source,
                schema.isBlank() ? "-" : schema,
                kind.isBlank() ? "-" : kind,
                anchor.isBlank() ? "-" : anchor,
                label.isBlank() ? "-" : label,
                topic.isBlank() ? "-" : topic,
                snippet.isBlank() ? "-" : snippet
        );
    }
}
