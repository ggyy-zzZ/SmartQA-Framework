package com.qa.demo.qa.config.store;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class ConfigBundleRepository extends AssistantStoreSupport {

    private static final Logger log = LoggerFactory.getLogger(ConfigBundleRepository.class);

    public ConfigBundleRepository(QaAssistantProperties properties) {
        super(properties);
    }

    public boolean hasActiveBundle(String scope, String configKey) {
        return loadActiveContent(scope, configKey).isPresent();
    }

    public Optional<String> loadActiveContent(String scope, String configKey) {
        String sql = """
                SELECT content_json
                FROM qa_config_bundle
                WHERE scope = ? AND config_key = ? AND is_active = 1
                ORDER BY version DESC
                LIMIT 1
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scopeOrDefault(scope));
            ps.setString(2, configKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("content_json"));
                }
            }
        } catch (SQLException e) {
            log.debug("[ConfigBundle] load failed scope={} key={}: {}", scope, configKey, e.getMessage());
        }
        return Optional.empty();
    }

    public void upsertActive(String scope, String configKey, String contentJson, String contentHash) {
        String scoped = scopeOrDefault(scope);
        int nextVersion = 1;
        String maxSql = "SELECT COALESCE(MAX(version), 0) FROM qa_config_bundle WHERE scope = ? AND config_key = ?";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(maxSql)) {
            ps.setString(1, scoped);
            ps.setString(2, configKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    nextVersion = rs.getInt(1) + 1;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read config version", e);
        }
        deactivate(scoped, configKey);
        String insert = """
                INSERT INTO qa_config_bundle
                (scope, config_key, version, content_json, content_hash, is_active, published_at, created_by)
                VALUES (?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, 'seed')
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, scoped);
            ps.setString(2, configKey);
            ps.setInt(3, nextVersion);
            ps.setString(4, contentJson);
            ps.setString(5, contentHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert config bundle " + scoped + "/" + configKey, e);
        }
    }

    private void deactivate(String scope, String configKey) {
        String sql = "UPDATE qa_config_bundle SET is_active = 0 WHERE scope = ? AND config_key = ? AND is_active = 1";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope);
            ps.setString(2, configKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[ConfigBundle] deactivate failed: {}", e.getMessage());
        }
    }
}
