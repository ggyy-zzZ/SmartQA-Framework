package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 实体级同步状态：读写 {@code sync_entity_state}（EKSP 增量水位与 content hash）。
 */
@Service
public class SyncEntityStateService {

    private static final Logger log = LoggerFactory.getLogger(SyncEntityStateService.class);

    private final QaAssistantProperties properties;

    public SyncEntityStateService(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public record EntitySyncState(
            String domain,
            String entityType,
            String entityId,
            String contentHash,
            String lastSyncBatchId,
            LocalDateTime lastSyncedAt,
            String syncStatus
    ) {
    }

    public Optional<EntitySyncState> find(String domain, String entityType, String entityId) {
        String sql = """
                SELECT domain, entity_type, entity_id, content_hash, last_sync_batch_id,
                       last_synced_at, sync_status
                FROM sync_entity_state
                WHERE domain = ? AND entity_type = ? AND entity_id = ?
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, domain);
            ps.setString(2, entityType);
            ps.setString(3, entityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            log.warn("[SyncEntityState] find failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<LocalDateTime> latestSyncTime(String domain) {
        String sql = """
                SELECT MAX(last_synced_at) AS max_ts
                FROM sync_entity_state
                WHERE domain = ? AND sync_status = 'active'
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, domain);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp("max_ts");
                    if (ts != null) {
                        return Optional.of(ts.toLocalDateTime());
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("[SyncEntityState] latestSyncTime failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public void upsert(
            String domain,
            String entityType,
            String entityId,
            String contentHash,
            String batchId
    ) {
        String sql = """
                INSERT INTO sync_entity_state
                    (domain, entity_type, entity_id, content_hash, last_sync_batch_id, last_synced_at, sync_status)
                VALUES (?, ?, ?, ?, ?, ?, 'active')
                ON DUPLICATE KEY UPDATE
                    content_hash = VALUES(content_hash),
                    last_sync_batch_id = VALUES(last_sync_batch_id),
                    last_synced_at = VALUES(last_synced_at),
                    sync_status = 'active',
                    updated_at = CURRENT_TIMESTAMP
                """;
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, domain);
            ps.setString(2, entityType);
            ps.setString(3, entityId);
            ps.setString(4, contentHash);
            ps.setString(5, batchId);
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("[SyncEntityState] upsert failed {}/{}:{}: {}", domain, entityType, entityId, e.getMessage());
            throw new IllegalStateException("sync_entity_state upsert failed: " + e.getMessage(), e);
        }
    }

    public int countByDomain(String domain) {
        String sql = "SELECT COUNT(*) FROM sync_entity_state WHERE domain = ? AND sync_status = 'active'";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, domain);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("[SyncEntityState] count failed: {}", e.getMessage());
            return 0;
        }
    }

    private EntitySyncState mapRow(ResultSet rs) throws SQLException {
        Timestamp synced = rs.getTimestamp("last_synced_at");
        return new EntitySyncState(
                rs.getString("domain"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("content_hash"),
                rs.getString("last_sync_batch_id"),
                synced != null ? synced.toLocalDateTime() : null,
                rs.getString("sync_status")
        );
    }

    private Connection openConnection() throws SQLException {
        if (!properties.isMysqlEnabled()) {
            throw new IllegalStateException("MySQL disabled");
        }
        return DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword()
        );
    }
}
