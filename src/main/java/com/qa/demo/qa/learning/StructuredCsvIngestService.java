package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * CSV 结构化接入：先做数据行行数门禁（与 {@link QaAssistantProperties#getMaxStructuredIngestRows()} 对齐），
 * 通过后再将整份 CSV 包装为可学习文本走 {@link ActiveLearningService}（写入主动学习通道，不直接改业务表）。
 * <p>
 * 行数统计按换行分段计数，不处理 RFC4180 引号内换行等复杂 CSV；此类文件请预处理或走专用 ETL。
 */
@Service
public class StructuredCsvIngestService {

    /** 单次上传体积上限，防止大文件占用内存。 */
    private static final int MAX_CSV_BYTES = 10 * 1024 * 1024;

    private final QaAssistantProperties properties;
    private final ActiveLearningService activeLearningService;

    public StructuredCsvIngestService(QaAssistantProperties properties, ActiveLearningService activeLearningService) {
        this.properties = properties;
        this.activeLearningService = activeLearningService;
    }

    public record CsvRowCountAudit(long dataRowCount, int limit, boolean withinLimit) {
    }

    /**
     * 统计「数据行」：空白行忽略；若 {@code skipHeaderRow} 为 true，则第一个非空行视为表头不计入数据行。
     */
    public CsvRowCountAudit auditDataRows(byte[] raw, boolean skipHeaderRow) {
        int limit = Math.max(1, properties.getMaxStructuredIngestRows());
        if (raw == null || raw.length == 0) {
            return new CsvRowCountAudit(0, limit, true);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        long dataRows = countDataRows(text, skipHeaderRow);
        return new CsvRowCountAudit(dataRows, limit, dataRows <= limit);
    }

    /**
     * 将已通过行数审计的 CSV 送入主动学习。调用方须已用 {@link #auditDataRows(byte[], boolean)} 确认 {@code withinLimit}。
     */
    public ActiveLearningService.LearningResult learnCsvBytes(
            byte[] raw,
            boolean skipHeaderRow,
            String sourceFilename,
            String scope
    ) {
        String name = safeFilename(sourceFilename);
        if (raw == null || raw.length == 0) {
            return activeLearningService.learn("(empty)", "csv_structured", name, "csv_api", scope);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return activeLearningService.learn("(blank)", "csv_structured", name, "csv_api", scope);
        }
        String payload = buildLearningPayload(text, name);
        return activeLearningService.learn(payload, "csv_structured", name, "csv_api", scope);
    }

    public int maxAllowedUploadBytes() {
        int limit = Math.max(1, properties.getMaxStructuredIngestRows());
        return Math.min(MAX_CSV_BYTES, Math.max(65_536, limit * 512));
    }

    static long countDataRows(String text, boolean skipHeaderRow) {
        long dataRows = 0;
        boolean headerConsumed = false;
        for (String line : text.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            if (skipHeaderRow && !headerConsumed) {
                headerConsumed = true;
                continue;
            }
            dataRows++;
        }
        return dataRows;
    }

    private static String buildLearningPayload(String csvText, String filename) {
        return """
                # 结构化数据（CSV）学习样本

                来源文件：%s

                下列为原始 CSV 内容（按行保留，供检索与关键词抽取）：

                ```csv
                %s
                ```
                """.formatted(filename, csvText.trim());
    }

    private static String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "upload.csv";
        }
        String trimmed = name.trim();
        if (trimmed.length() > 200) {
            trimmed = trimmed.substring(trimmed.length() - 200);
        }
        return trimmed.replace("..", "").replace("\\", "").replace("/", "");
    }
}
