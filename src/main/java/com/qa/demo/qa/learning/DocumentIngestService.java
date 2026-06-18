package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.store.DocumentChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * P5：用户文档上传 → 切块 → 写入 {@code qa_document_chunk}，供 {@link com.qa.demo.qa.retrieval.DocumentContextService} 检索。
 */
@Service
public class DocumentIngestService {

    private static final int MAX_CHUNK_CHARS = 4_000;
    private static final int MAX_TOTAL_CHARS = 500_000;
    private static final List<Charset> TEXT_CHARSETS = List.of(
            StandardCharsets.UTF_8,
            Charset.forName("GB18030"),
            Charset.forName("GBK")
    );

    private final DocumentChunkRepository documentChunkRepository;
    private final DocumentVectorIngestService documentVectorIngestService;

    public DocumentIngestService(
            DocumentChunkRepository documentChunkRepository,
            DocumentVectorIngestService documentVectorIngestService
    ) {
        this.documentChunkRepository = documentChunkRepository;
        this.documentVectorIngestService = documentVectorIngestService;
    }

    public IngestResult ingestUpload(
            MultipartFile file,
            String scope,
            String corpusCode,
            String title,
            boolean replaceExisting
    ) {
        if (file == null || file.isEmpty()) {
            return IngestResult.failed("上传文件为空");
        }
        String filename = file.getOriginalFilename() == null ? "document.txt" : file.getOriginalFilename().strip();
        if (!isSupportedFilename(filename)) {
            return IngestResult.failed("仅支持 .md / .txt / .markdown 文本文件");
        }
        String text;
        try {
            text = decodeText(file.getBytes());
        } catch (java.io.IOException ex) {
            return IngestResult.failed("读取上传文件失败: " + ex.getMessage());
        }
        if (text == null || text.isBlank()) {
            return IngestResult.failed("无法读取文本内容（请使用 UTF-8 或 GBK 编码）");
        }
        if (text.length() > MAX_TOTAL_CHARS) {
            text = text.substring(0, MAX_TOTAL_CHARS) + "\n...[truncated]";
        }
        String normalizedCorpus = corpusCode == null || corpusCode.isBlank()
                ? "user_uploads"
                : corpusCode.strip();
        String docTitle = title == null || title.isBlank() ? filename : title.strip();
        List<DocumentChunkRepository.GenericChunkInput> chunks = splitIntoChunks(text, filename);
        if (chunks.isEmpty()) {
            return IngestResult.failed("文档内容为空，未生成 chunk");
        }
        int imported = documentChunkRepository.importGenericChunks(
                scope,
                normalizedCorpus,
                docTitle,
                chunks,
                replaceExisting
        );
        int vectorized = documentVectorIngestService.ingestChunks(
                scopeOrDefault(scope),
                normalizedCorpus,
                docTitle,
                chunks.stream()
                        .map(c -> new DocumentVectorIngestService.ChunkPayload(c.chunkKey(), c.contentText()))
                        .toList()
        );
        return IngestResult.ok(filename, normalizedCorpus, imported, vectorized);
    }

    private static boolean isSupportedFilename(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md")
                || lower.endsWith(".markdown")
                || lower.endsWith(".txt");
    }

    private static String decodeText(byte[] bytes) {
        for (Charset charset : TEXT_CHARSETS) {
            try {
                String text = new String(bytes, charset);
                if (text.indexOf('\u0000') >= 0) {
                    continue;
                }
                if (!text.isBlank()) {
                    return text;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static List<DocumentChunkRepository.GenericChunkInput> splitIntoChunks(String text, String filename) {
        String normalized = text.replace("\r\n", "\n").strip();
        if (normalized.isBlank()) {
            return List.of();
        }
        String docId = UUID.randomUUID().toString().substring(0, 8);
        String[] paragraphs = normalized.split("\n\\s*\n");
        List<DocumentChunkRepository.GenericChunkInput> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (String paragraph : paragraphs) {
            String part = paragraph.strip();
            if (part.isBlank()) {
                continue;
            }
            if (part.length() > MAX_CHUNK_CHARS) {
                for (int i = 0; i < part.length(); i += MAX_CHUNK_CHARS) {
                    String slice = part.substring(i, Math.min(part.length(), i + MAX_CHUNK_CHARS));
                    chunks.add(buildChunk(filename, docId, chunkIndex++, slice));
                }
                continue;
            }
            chunks.add(buildChunk(filename, docId, chunkIndex++, part));
        }
        return chunks;
    }

    private static DocumentChunkRepository.GenericChunkInput buildChunk(
            String filename,
            String docId,
            int index,
            String content
    ) {
        String key = docId + ":" + index;
        String label = filename + " #" + (index + 1);
        return new DocumentChunkRepository.GenericChunkInput(key, key, label, content);
    }

    private static String scopeOrDefault(String scope) {
        return scope == null || scope.isBlank() ? "enterprise" : scope.strip();
    }

    public record IngestResult(
            boolean ok,
            String message,
            String filename,
            String corpusCode,
            int chunkCount,
            int vectorCount
    ) {
        static IngestResult ok(String filename, String corpusCode, int chunkCount, int vectorCount) {
            return new IngestResult(true, "imported", filename, corpusCode, chunkCount, vectorCount);
        }

        static IngestResult failed(String message) {
            return new IngestResult(false, message, "", "", 0, 0);
        }
    }
}
