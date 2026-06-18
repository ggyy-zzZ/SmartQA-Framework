package com.qa.demo.qa.config.store;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public class DocumentChunkRepository extends AssistantStoreSupport {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkRepository.class);
    private static final int MAX_RECORD_SCAN = 2000;

    public DocumentChunkRepository(QaAssistantProperties properties) {
        super(properties);
    }

    public boolean hasChunks(String scope, String corpusCode) {
        String sql = """
                SELECT 1 FROM qa_document_chunk c
                JOIN qa_document_corpus d ON d.id = c.corpus_id
                WHERE d.scope = ? AND d.corpus_code = ? AND d.is_active = 1
                LIMIT 1
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            ps.setString(2, corpusCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public int importFromCompiledFile(Path file, String scope, String corpusCode, String title) {
        if (file == null || !Files.isRegularFile(file)) {
            return 0;
        }
        String content = readText(file);
        if (content == null || content.isBlank()) {
            return 0;
        }
        try {
            long corpusId = ensureCorpus(scopeOrDefault(scope), corpusCode, title);
            clearChunks(corpusId);
            List<ChunkRow> rows = parseCompanyBlocks(content);
            String insert = """
                    INSERT INTO qa_document_chunk
                    (corpus_id, chunk_key, anchor_id, display_label, content_text, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            int order = 0;
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(insert)) {
                for (ChunkRow row : rows) {
                    ps.setLong(1, corpusId);
                    ps.setString(2, row.chunkKey());
                    ps.setString(3, row.anchorId());
                    ps.setString(4, row.displayLabel());
                    ps.setString(5, row.contentText());
                    ps.setInt(6, order++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.info("[DocumentChunk] imported {} chunks from {}", rows.size(), file);
            return rows.size();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to import document chunks from " + file, e);
        }
    }

    public List<ChunkRow> loadAll(String scope, String corpusCode) {
        String sql = """
                SELECT c.chunk_key, c.anchor_id, c.display_label, c.content_text
                FROM qa_document_chunk c
                JOIN qa_document_corpus d ON d.id = c.corpus_id
                WHERE d.scope = ? AND d.corpus_code = ? AND d.is_active = 1
                ORDER BY c.sort_order, c.id
                """;
        List<ChunkRow> rows = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            ps.setString(2, corpusCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ChunkRow(
                            rs.getString("chunk_key"),
                            rs.getString("anchor_id"),
                            rs.getString("display_label"),
                            rs.getString("content_text")
                    ));
                }
            }
        } catch (SQLException e) {
            log.debug("[DocumentChunk] load failed: {}", e.getMessage());
        }
        return rows;
    }

    /** 加载 scope 下所有 active 语料的 chunk（含用户上传语料）。 */
    public List<ChunkRow> loadAllActiveForScope(String scope) {
        String sql = """
                SELECT c.chunk_key, c.anchor_id, c.display_label, c.content_text
                FROM qa_document_chunk c
                JOIN qa_document_corpus d ON d.id = c.corpus_id
                WHERE d.scope = ? AND d.is_active = 1
                ORDER BY d.corpus_code, c.sort_order, c.id
                """;
        List<ChunkRow> rows = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ChunkRow(
                            rs.getString("chunk_key"),
                            rs.getString("anchor_id"),
                            rs.getString("display_label"),
                            rs.getString("content_text")
                    ));
                }
            }
        } catch (SQLException e) {
            log.debug("[DocumentChunk] loadAllActiveForScope failed: {}", e.getMessage());
        }
        return rows;
    }

    /**
     * 导入通用文本 chunk（Markdown/TXT 切块）；replace=true 时覆盖同 corpus 已有 chunk。
     */
    public int importGenericChunks(
            String scope,
            String corpusCode,
            String title,
            List<GenericChunkInput> chunks,
            boolean replaceExisting
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        try {
            long corpusId = ensureCorpus(scopeOrDefault(scope), corpusCode, title);
            if (replaceExisting) {
                clearChunks(corpusId);
            }
            int startOrder = nextSortOrder(corpusId);
            String insert = """
                    INSERT INTO qa_document_chunk
                    (corpus_id, chunk_key, anchor_id, display_label, content_text, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(insert)) {
                int order = startOrder;
                for (GenericChunkInput chunk : chunks) {
                    ps.setLong(1, corpusId);
                    ps.setString(2, chunk.chunkKey());
                    ps.setString(3, chunk.anchorId());
                    ps.setString(4, chunk.displayLabel());
                    ps.setString(5, chunk.contentText());
                    ps.setInt(6, order++);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.info("[DocumentChunk] imported {} generic chunks into corpus={}", chunks.size(), corpusCode);
            return chunks.size();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to import generic document chunks: " + corpusCode, e);
        }
    }

    private int nextSortOrder(long corpusId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM qa_document_chunk WHERE corpus_id = ?")) {
            ps.setLong(1, corpusId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    public record GenericChunkInput(String chunkKey, String anchorId, String displayLabel, String contentText) {
    }

    private long ensureCorpus(String scope, String corpusCode, String title) throws SQLException {
        String upsert = """
                INSERT INTO qa_document_corpus (scope, corpus_code, title, is_active)
                VALUES (?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE title = VALUES(title), is_active = 1
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(upsert)) {
            ps.setString(1, scope);
            ps.setString(2, corpusCode);
            ps.setString(3, title != null ? title : corpusCode);
            ps.executeUpdate();
        }
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id FROM qa_document_corpus WHERE scope = ? AND corpus_code = ?")) {
            ps.setString(1, scope);
            ps.setString(2, corpusCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        throw new SQLException("corpus missing: " + corpusCode);
    }

    private void clearChunks(long corpusId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM qa_document_chunk WHERE corpus_id = ?")) {
            ps.setLong(1, corpusId);
            ps.executeUpdate();
        }
    }

    private static List<ChunkRow> parseCompanyBlocks(String content) {
        String normalized = content.replace("\r\n", "\n");
        String prepared = normalized.replace("}; {companyId=", "};\n{companyId=");
        String[] chunks = prepared.split("\n\\{companyId=");
        List<ChunkRow> rows = new ArrayList<>();
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
            rows.add(new ChunkRow(
                    companyId.trim(),
                    companyId.trim(),
                    companyName.trim(),
                    trimmed
            ));
            if (rows.size() >= MAX_RECORD_SCAN) {
                break;
            }
        }
        return rows;
    }

    private static String extractBetween(String text, String start, String end) {
        int s = text.indexOf(start);
        if (s < 0) {
            return null;
        }
        s += start.length();
        int e = text.indexOf(end, s);
        if (e < 0) {
            return text.substring(s).trim();
        }
        return text.substring(s, e).trim();
    }

    private static String readText(Path file) {
        List<Charset> charsets = List.of(StandardCharsets.UTF_8, Charset.forName("GB18030"), Charset.forName("GBK"));
        for (Charset cs : charsets) {
            try {
                return Files.readString(file, cs);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public record ChunkRow(String chunkKey, String anchorId, String displayLabel, String contentText) {
    }
}
