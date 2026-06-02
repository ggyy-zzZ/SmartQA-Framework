package com.qa.demo.qa.config.store;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class EnumCatalogRepository extends AssistantStoreSupport {

    private static final Logger log = LoggerFactory.getLogger(EnumCatalogRepository.class);

    public EnumCatalogRepository(QaAssistantProperties properties) {
        super(properties);
    }

    public boolean hasDict(String scope, String dictCode) {
        String sql = "SELECT 1 FROM qa_enum_dict WHERE scope = ? AND dict_code = ? AND is_active = 1 LIMIT 1";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            ps.setString(2, dictCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public Map<String, String> entryMap(String scope, String dictCode) {
        Map<String, String> entries = new LinkedHashMap<>();
        String sql = """
                SELECT e.entry_key, e.entry_label
                FROM qa_enum_entry e
                JOIN qa_enum_dict d ON d.id = e.dict_id
                WHERE d.scope = ? AND d.dict_code = ? AND d.is_active = 1
                ORDER BY e.sort_order, e.id
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            ps.setString(2, dictCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.put(rs.getString("entry_key"), rs.getString("entry_label"));
                }
            }
        } catch (SQLException e) {
            log.debug("[EnumCatalog] entryMap failed dict={}: {}", dictCode, e.getMessage());
        }
        return entries;
    }

    public LinkedHashSet<String> uniqueLabels(String scope, String dictCode) {
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        String sql = """
                SELECT e.entry_label
                FROM qa_enum_entry e
                JOIN qa_enum_dict d ON d.id = e.dict_id
                WHERE d.scope = ? AND d.dict_code = ? AND d.is_active = 1
                ORDER BY e.sort_order, e.id
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            ps.setString(2, dictCode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String label = rs.getString("entry_label");
                    if (label != null && !label.isBlank()) {
                        labels.add(label.trim());
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("[EnumCatalog] load failed dict={}: {}", dictCode, e.getMessage());
        }
        return labels;
    }

    public void replaceDict(String scope, String dictCode, String dictName, Map<String, String> entries) {
        String scoped = scopeOrDefault(scope);
        long dictId;
        try {
            dictId = ensureDict(scoped, dictCode, dictName);
            clearEntries(dictId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to prepare enum dict " + dictCode, e);
        }
        String insert = """
                INSERT INTO qa_enum_entry (dict_id, entry_key, entry_label, sort_order)
                VALUES (?, ?, ?, ?)
                """;
        int order = 0;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(insert)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                ps.setLong(1, dictId);
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue());
                ps.setInt(4, order++);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to seed enum dict " + dictCode, ex);
        }
    }

    private long ensureDict(String scope, String dictCode, String dictName) throws SQLException {
        String upsert = """
                INSERT INTO qa_enum_dict (scope, dict_code, dict_name, is_active)
                VALUES (?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name), is_active = 1
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(upsert, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scope);
            ps.setString(2, dictCode);
            ps.setString(3, dictName != null ? dictName : dictCode);
            ps.executeUpdate();
        }
        String find = "SELECT id FROM qa_enum_dict WHERE scope = ? AND dict_code = ?";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(find)) {
            ps.setString(1, scope);
            ps.setString(2, dictCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        throw new SQLException("dict not found after upsert: " + dictCode);
    }

    private void clearEntries(long dictId) throws SQLException {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM qa_enum_entry WHERE dict_id = ?")) {
            ps.setLong(1, dictId);
            ps.executeUpdate();
        }
    }
}
