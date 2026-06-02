package com.qa.demo.qa.config.store;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Repository
public class EntitySnapshotRepository extends AssistantStoreSupport {

    private static final Logger log = LoggerFactory.getLogger(EntitySnapshotRepository.class);

    public EntitySnapshotRepository(QaAssistantProperties properties) {
        super(properties);
    }

    public boolean hasAny(String scope) {
        String sql = "SELECT 1 FROM qa_entity_snapshot WHERE scope = ? LIMIT 1";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public int importFromJsonl(Path jsonl, String scope, String domain, String entityType) {
        if (jsonl == null || !Files.isRegularFile(jsonl)) {
            return 0;
        }
        String scoped = scopeOrDefault(scope);
        String dom = domain == null || domain.isBlank() ? "org_master" : domain.trim();
        String type = entityType == null || entityType.isBlank() ? "company" : entityType.trim();
        String upsert = """
                INSERT INTO qa_entity_snapshot
                (scope, domain, entity_type, entity_id, payload_json, content_hash, source_db, batch_id)
                VALUES (?, ?, ?, ?, ?, ?, 'tdcomp', 'import-jsonl')
                ON DUPLICATE KEY UPDATE
                  payload_json = VALUES(payload_json),
                  content_hash = VALUES(content_hash),
                  batch_id = VALUES(batch_id),
                  updated_at = CURRENT_TIMESTAMP
                """;
        int count = 0;
        try (Connection conn = openConnection();
             BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8);
             PreparedStatement ps = conn.prepareStatement(upsert)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isBlank()) {
                    continue;
                }
                String entityId = extractEntityId(line);
                if (entityId.isBlank()) {
                    continue;
                }
                String hash = sha256Hex(line);
                ps.setString(1, scoped);
                ps.setString(2, dom);
                ps.setString(3, type);
                ps.setString(4, entityId);
                ps.setString(5, line);
                ps.setString(6, hash);
                ps.addBatch();
                count++;
                if (count % 200 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to import snapshots from " + jsonl, e);
        }
        log.info("[EntitySnapshot] imported {} rows from {}", count, jsonl);
        return count;
    }

    public void exportToJsonl(Path jsonl, String scope, String domain) {
        String sql = """
                SELECT payload_json FROM qa_entity_snapshot
                WHERE scope = ? AND domain = ?
                ORDER BY entity_id
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             var writer = Files.newBufferedWriter(jsonl, StandardCharsets.UTF_8)) {
            ps.setString(1, scopeOrDefault(scope));
            ps.setString(2, domain);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    writer.write(rs.getString("payload_json"));
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to export snapshots to " + jsonl, e);
        }
    }

    public void forEachPayload(String scope, String domain, Consumer<String> consumer) {
        String sql = """
                SELECT payload_json FROM qa_entity_snapshot
                WHERE scope = ? AND domain = ?
                ORDER BY entity_id
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            ps.setString(2, domain);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    consumer.accept(rs.getString("payload_json"));
                }
            }
        } catch (SQLException e) {
            log.warn("[EntitySnapshot] stream failed: {}", e.getMessage());
        }
    }

    private static String extractEntityId(String jsonLine) {
        int idx = jsonLine.indexOf("\"company_id\"");
        if (idx < 0) {
            idx = jsonLine.indexOf("company_id");
        }
        if (idx < 0) {
            return "";
        }
        int colon = jsonLine.indexOf(':', idx);
        if (colon < 0) {
            return "";
        }
        int start = colon + 1;
        while (start < jsonLine.length() && (jsonLine.charAt(start) == ' ' || jsonLine.charAt(start) == '"')) {
            start++;
        }
        int end = start;
        while (end < jsonLine.length() && jsonLine.charAt(end) != '"' && jsonLine.charAt(end) != ',' && jsonLine.charAt(end) != '}') {
            end++;
        }
        return jsonLine.substring(start, end).trim();
    }

    private static String sha256Hex(String text) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
