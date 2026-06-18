package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.store.DocumentChunkRepository;
import com.qa.demo.qa.core.ContextChunk;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class DocumentContextService {

    private final QaAssistantProperties properties;
    private final DocumentChunkRepository documentChunkRepository;

    private static final int MAX_FILE_COUNT = 30;
    private static final int MAX_CHARS_PER_FILE = 8_000;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_RECORD_SCAN = 2000;
    private static final List<Charset> CANDIDATE_CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("GBK")
    );
    private static final Map<String, List<String>> FIELD_KEYWORDS = Map.ofEntries(
            Map.entry("经营状态", List.of("经营状态", "存续", "注销", "在业")),
            Map.entry("股权结构", List.of("股东", "持股", "认缴", "实缴", "股权")),
            Map.entry("地域与地址", List.of("地址", "地区", "办公", "注册地", "城市")),
            Map.entry("证照信息", List.of(
                    "证照", "许可证", "执照", "资质", "备案", "icp", "iso", "营业执照",
                    "人力资源", "劳务派遣", "高新", "增值电信", "广播电视"
            )),
            Map.entry("印章信息", List.of("印章", "公章", "合同章", "财务章", "印鉴", "用印")),
            Map.entry("管理层", List.of("法定代表人", "经理", "财务负责人", "联系人")),
            Map.entry("产品线", List.of("产品线", "模块", "关系"))
    );

    public DocumentContextService(QaAssistantProperties properties, DocumentChunkRepository documentChunkRepository) {
        this.properties = properties;
        this.documentChunkRepository = documentChunkRepository;
    }

    public String buildContext(String docsPath) throws IOException {
        Path root = Path.of(docsPath);
        if (!Files.exists(root)) {
            throw new IOException("Docs path does not exist: " + docsPath);
        }

        List<String> blocks = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            String block = readFileBlock(root, root.getFileName().toString());
            if (block != null) {
                blocks.add(block);
            }
        } else if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.naturalOrder())
                        .limit(MAX_FILE_COUNT)
                        .toList();

                for (Path file : files) {
                    Path relativePath = root.relativize(file);
                    String block = readFileBlock(file, relativePath.toString());
                    if (block != null) {
                        blocks.add(block);
                    }
                }
            }
        } else {
            throw new IOException("Docs path is neither a file nor a directory: " + docsPath);
        }

        if (blocks.isEmpty()) {
            return "No readable UTF-8 text files were found in the configured path.";
        }
        return String.join("\n\n", blocks);
    }

    public List<ContextChunk> retrieveTopChunks(String question, String docsPath, int topK) throws IOException {
        if (properties.isDocumentFromDb()) {
            List<ContextChunk> fromDb = retrieveTopChunksFromDb(question, topK);
            if (!fromDb.isEmpty()) {
                return fromDb;
            }
        }
        Path sourcePath = Path.of(docsPath);
        String content = readTextWithFallback(sourcePath);
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<CompanyRecord> records = parseCompanyRecords(content);
        if (records.isEmpty()) {
            return List.of();
        }

        Set<String> questionTokens = extractTokens(question);
        int limitedTopK = Math.max(1, Math.min(topK, 10));
        List<ScoredRecord> scored = new ArrayList<>();

        for (CompanyRecord record : records) {
            double score = scoreRecord(question, questionTokens, record);
            if (score <= 0) {
                continue;
            }
            scored.add(new ScoredRecord(record, score));
        }

        if (scored.isEmpty()) {
            return List.of();
        }

        scored.sort(Comparator.comparingDouble(ScoredRecord::score).reversed());

        List<ContextChunk> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limitedTopK, scored.size()); i++) {
            ScoredRecord item = scored.get(i);
            CompanyRecord record = item.record();
            String field = detectField(record.rawBlock(), question);
            String snippet = buildSnippet(record.rawBlock(), question);
            result.add(ContextChunk.ofCompany(
                    record.anchorId(),
                    record.displayLabel(),
                    field,
                    snippet,
                    item.score(),
                    sourcePath.getFileName().toString()
            ));
        }
        return result;
    }

    /** 仅检索用户上传语料（corpus=user_uploads）。 */
    public List<ContextChunk> retrieveUserUploadTopChunks(String question, int topK) {
        if (!properties.isDocumentFromDb()) {
            return List.of();
        }
        List<DocumentChunkRepository.ChunkRow> rows = documentChunkRepository.loadAll(
                properties.getConfigScope(), "user_uploads");
        return scoreChunkRows(question, rows, topK, "user-document-chunk");
    }

    private List<ContextChunk> scoreChunkRows(
            String question,
            List<DocumentChunkRepository.ChunkRow> rows,
            int topK,
            String sourceTag
    ) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<String> questionTokens = extractTokens(question);
        int limitedTopK = Math.max(1, Math.min(topK, 10));
        List<ScoredRecord> scored = new ArrayList<>();
        for (DocumentChunkRepository.ChunkRow row : rows) {
            CompanyRecord record = new CompanyRecord(row.anchorId(), row.displayLabel(), row.contentText());
            double score = scoreRecord(question, questionTokens, record);
            if (score > 0) {
                scored.add(new ScoredRecord(record, score));
            }
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        scored.sort(Comparator.comparingDouble(ScoredRecord::score).reversed());
        List<ContextChunk> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limitedTopK, scored.size()); i++) {
            ScoredRecord item = scored.get(i);
            CompanyRecord record = item.record();
            result.add(ContextChunk.ofCompany(
                    record.anchorId(),
                    record.displayLabel(),
                    detectField(record.rawBlock(), question),
                    buildSnippet(record.rawBlock(), question),
                    item.score(),
                    sourceTag
            ));
        }
        return result;
    }

    private List<ContextChunk> retrieveTopChunksFromDb(String question, int topK) {
        String scope = properties.getConfigScope();
        List<DocumentChunkRepository.ChunkRow> rows = documentChunkRepository.loadAllActiveForScope(scope);
        if (rows.isEmpty()) {
            rows = documentChunkRepository.loadAll(scope, properties.getDocumentCorpusCode());
        }
        return scoreChunkRows(question, rows, topK, "document-chunk-db");
    }

    private String readFileBlock(Path file, String displayName) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            return null;
        }

        String content = readTextWithFallback(file);
        if (content == null) {
            return null;
        }
        if (content.indexOf('\u0000') >= 0 || content.isBlank()) {
            return null;
        }

        String normalized = content.length() > MAX_CHARS_PER_FILE
                ? content.substring(0, MAX_CHARS_PER_FILE) + "\n...[truncated]"
                : content;
        return "[File] " + displayName + "\n" + normalized;
    }

    private String readTextWithFallback(Path file) {
        for (Charset charset : CANDIDATE_CHARSETS) {
            try {
                String content = Files.readString(file, charset);
                if (content != null && !content.isBlank()) {
                    return content;
                }
            } catch (Exception ignored) {
                // Try next charset.
            }
        }
        return null;
    }

    private List<CompanyRecord> parseCompanyRecords(String content) {
        String normalized = content.replace("\r\n", "\n");
        String prepared = normalized.replace("}; {companyId=", "};\n{companyId=");
        String[] chunks = prepared.split("\n\\{companyId=");
        List<CompanyRecord> records = new ArrayList<>();

        for (int i = 0; i < chunks.length; i++) {
            String chunk = chunks[i];
            if (i != 0) {
                chunk = "{companyId=" + chunk;
            }
            String trimmed = chunk.trim();
            if (!trimmed.startsWith("{companyId=")) {
                continue;
            }
            String companyId = extractBetween(trimmed, "{companyId=", ", companyName=");
            String companyName = extractBetween(trimmed, "companyName=", ", summary=");
            if (companyId == null || companyName == null) {
                continue;
            }
            records.add(new CompanyRecord(companyId.trim(), companyName.trim(), trimmed));
            if (records.size() >= MAX_RECORD_SCAN) {
                break;
            }
        }
        return records;
    }

    private double scoreRecord(String question, Set<String> questionTokens, CompanyRecord record) {
        String q = question.toLowerCase(Locale.ROOT);
        String block = record.rawBlock().toLowerCase(Locale.ROOT);
        double score = 0;

        if (q.contains(record.displayLabel().toLowerCase(Locale.ROOT))) {
            score += 12;
        }
        if (q.contains(record.anchorId().toLowerCase(Locale.ROOT))) {
            score += 12;
        }

        for (Map.Entry<String, List<String>> entry : FIELD_KEYWORDS.entrySet()) {
            long hit = entry.getValue().stream().filter(q::contains).count();
            String sectionKey = entry.getKey() + "：";
            if (hit > 0 && block.contains(sectionKey.toLowerCase(Locale.ROOT))) {
                score += 5 + hit;
                if ("证照信息".equals(entry.getKey()) && block.contains("类型:")) {
                    score += 6;
                }
            }
        }
        if (block.contains("企业证照与印章类型字典") && (q.contains("证照类型") || q.contains("有哪些证照") || q.contains("证照种类"))) {
            score += 15;
        }

        int tokenHit = 0;
        for (String token : questionTokens) {
            if (token.length() < 2) {
                continue;
            }
            if (block.contains(token)) {
                tokenHit++;
            }
        }
        score += Math.min(tokenHit, 8);
        score += scoreCjkOverlap(question, block);

        return score;
    }

    /** 中文问句与通用文档 chunk 的二字片段重合度。 */
    private static double scoreCjkOverlap(String question, String blockLower) {
        String q = question == null ? "" : question.replaceAll("\\s+", "");
        if (q.length() < 2) {
            return 0;
        }
        double score = 0;
        int hits = 0;
        for (int i = 0; i + 2 <= q.length(); i++) {
            String bi = q.substring(i, i + 2).toLowerCase(Locale.ROOT);
            if (bi.codePoints().anyMatch(cp -> cp > 127) && blockLower.contains(bi)) {
                hits++;
            }
        }
        score += Math.min(hits, 10);
        return score;
    }

    private Set<String> extractTokens(String question) {
        Set<String> tokens = new HashSet<>();
        String lower = question.toLowerCase(Locale.ROOT);
        for (String part : lower.split("[^\\p{L}\\p{N}]+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        for (int i = 0; i + 2 <= lower.length(); i++) {
            String bi = lower.substring(i, i + 2);
            if (bi.codePoints().anyMatch(cp -> cp > 127)) {
                tokens.add(bi);
            }
        }
        return tokens;
    }

    private String detectField(String block, String question) {
        String q = question.toLowerCase(Locale.ROOT);
        String b = block.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : FIELD_KEYWORDS.entrySet()) {
            if (entry.getValue().stream().anyMatch(q::contains) && b.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getKey();
            }
        }
        return "主体信息";
    }

    private String buildSnippet(String block, String question) {
        String[] lines = block.replace("\r\n", "\n").split("\n");
        List<String> matched = new ArrayList<>();
        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        for (String line : lines) {
            String l = line.trim();
            if (l.isBlank()) {
                continue;
            }
            if (l.startsWith("公司名称：")
                    || l.startsWith("经营状态：")
                    || l.startsWith("主体类型：")) {
                matched.add(l);
                continue;
            }
            if (lowerQuestion.contains("证照") && l.startsWith("证照信息：") && l.length() > "证照信息：".length()) {
                matched.add(l);
                continue;
            }
            if ((lowerQuestion.contains("印章") || lowerQuestion.contains("公章"))
                    && l.startsWith("印章信息：") && l.length() > "印章信息：".length()) {
                matched.add(l);
                continue;
            }
            if (lowerQuestion.length() >= 2 && l.toLowerCase(Locale.ROOT).contains(lowerQuestion)) {
                matched.add(l);
                continue;
            }
            for (String token : extractTokens(question)) {
                if (token.length() >= 2 && l.toLowerCase(Locale.ROOT).contains(token)) {
                    matched.add(l);
                    break;
                }
            }
            if (matched.size() >= 8) {
                break;
            }
        }

        if (matched.isEmpty()) {
            List<String> fallback = new ArrayList<>();
            for (int i = 0; i < Math.min(lines.length, 8); i++) {
                fallback.add(lines[i].trim());
            }
            return String.join(" | ", fallback);
        }
        return String.join(" | ", matched);
    }

    private String extractBetween(String text, String start, String end) {
        int startIdx = text.indexOf(start);
        if (startIdx < 0) {
            return null;
        }
        int from = startIdx + start.length();
        int endIdx = text.indexOf(end, from);
        if (endIdx < 0) {
            return null;
        }
        return text.substring(from, endIdx);
    }

    private record CompanyRecord(String anchorId, String displayLabel, String rawBlock) {
    }

    private record ScoredRecord(CompanyRecord record, double score) {
    }
}
