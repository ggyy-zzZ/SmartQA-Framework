package com.qa.demo.qa.learning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 同步追踪服务：记录每个数据源的同步状态，支持增量同步
 */
@Service
public class SyncTrackingService {

    private static final Logger log = LoggerFactory.getLogger(SyncTrackingService.class);
    private static final String WORKSPACE_SCHEMA = "assistant";

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public SyncTrackingService() {
        this.jdbcUrl = "jdbc:mysql://localhost:3306/" + WORKSPACE_SCHEMA + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        this.username = "root";
        this.password = "root";
    }

    /**
     * 记录同步完成
     */
    public void recordSync(String host, int port, String db, String tableName, long rowCount) {
        String sql = """
            INSERT INTO sync_tracking (source_host, source_port, source_db, table_name, last_sync_time, last_row_count)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                last_sync_time = VALUES(last_sync_time),
                last_row_count = VALUES(last_row_count),
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, host);
            ps.setInt(2, port);
            ps.setString(3, db);
            ps.setString(4, tableName);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            ps.setLong(6, rowCount);
            ps.executeUpdate();
            log.info("[SyncTracking] 记录同步: {}/{}/{} -> {}, {} 行", host, db, tableName, rowCount);
        } catch (SQLException e) {
            log.error("[SyncTracking] 记录同步失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取上次同步时间
     */
    public LocalDateTime getLastSyncTime(String host, int port, String db, String tableName) {
        String sql = """
            SELECT last_sync_time FROM sync_tracking
            WHERE source_host = ? AND source_port = ? AND source_db = ? AND table_name = ?
            """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, host);
            ps.setInt(2, port);
            ps.setString(3, db);
            ps.setString(4, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp ts = rs.getTimestamp("last_sync_time");
                    return ts != null ? ts.toLocalDateTime() : null;
                }
            }
        } catch (SQLException e) {
            log.error("[SyncTracking] 获取上次同步时间失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取所有需要同步的数据源配置
     */
    public List<SyncSource> getAllSyncSources() {
        List<SyncSource> sources = new ArrayList<>();
        String sql = """
            SELECT DISTINCT source_host, source_port, source_db FROM sync_tracking
            """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sources.add(new SyncSource(
                        rs.getString("source_host"),
                        rs.getInt("source_port"),
                        rs.getString("source_db")
                ));
            }
        } catch (SQLException e) {
            log.error("[SyncTracking] 获取同步源失败: {}", e.getMessage());
        }
        return sources;
    }

    /**
     * 获取某个数据源的所有同步表
     */
    public List<SyncTableInfo> getSyncTables(String host, int port, String db) {
        List<SyncTableInfo> tables = new ArrayList<>();
        String sql = """
            SELECT table_name, last_sync_time, last_row_count FROM sync_tracking
            WHERE source_host = ? AND source_port = ? AND source_db = ?
            """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, host);
            ps.setInt(2, port);
            ps.setString(3, db);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(new SyncTableInfo(
                            rs.getString("table_name"),
                            rs.getTimestamp("last_sync_time") != null
                                    ? rs.getTimestamp("last_sync_time").toLocalDateTime()
                                    : null,
                            rs.getLong("last_row_count")
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("[SyncTracking] 获取同步表失败: {}", e.getMessage());
        }
        return tables;
    }

    /**
     * 删除同步记录
     */
    public void deleteSyncRecord(String host, int port, String db, String tableName) {
        String sql = """
            DELETE FROM sync_tracking WHERE source_host = ? AND source_port = ? AND source_db = ? AND table_name = ?
            """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, host);
            ps.setInt(2, port);
            ps.setString(3, db);
            ps.setString(4, tableName);
            ps.executeUpdate();
            log.info("[SyncTracking] 删除同步记录: {}/{}/{}", host, db, tableName);
        } catch (SQLException e) {
            log.error("[SyncTracking] 删除同步记录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取当前行数与上次同步行数的差异
     */
    public long getRowCountDelta(String host, int port, String db, String tableName, long currentRowCount) {
        String sql = """
            SELECT last_row_count FROM sync_tracking
            WHERE source_host = ? AND source_port = ? AND source_db = ? AND table_name = ?
            """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, host);
            ps.setInt(2, port);
            ps.setString(3, db);
            ps.setString(4, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long lastRowCount = rs.getLong("last_row_count");
                    return currentRowCount - lastRowCount;
                }
            }
        } catch (SQLException e) {
            log.error("[SyncTracking] 获取行数差异失败: {}", e.getMessage());
        }
        return currentRowCount; // 首次同步
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    // ========== 数据类 ==========

    public record SyncSource(String host, int port, String database) {}

    public record SyncTableInfo(String tableName, LocalDateTime lastSyncTime, long lastRowCount) {}
}