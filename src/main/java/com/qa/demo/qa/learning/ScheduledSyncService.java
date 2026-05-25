package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时同步服务：定期检查已学习的数据源，自动触发增量同步
 */
@Service
public class ScheduledSyncService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSyncService.class);

    private final SyncTrackingService syncTrackingService;
    private final ActiveLearningService activeLearningService;

    // 记录正在进行的同步任务，避免重复
    private final ConcurrentHashMap<String, AtomicBoolean> runningTasks = new ConcurrentHashMap<>();

    // 是否启用定时同步（可通过配置关闭）
    private final boolean syncEnabled;

    public ScheduledSyncService(
            SyncTrackingService syncTrackingService,
            ActiveLearningService activeLearningService,
            QaAssistantProperties properties
    ) {
        this.syncTrackingService = syncTrackingService;
        this.activeLearningService = activeLearningService;
        this.syncEnabled = properties.getEnableScheduledSync();
    }

    /**
     * 定时同步：每30分钟检查一次
     */
    @Scheduled(fixedRate = 30 * 60 * 1000) // 30分钟
    public void scheduledSync() {
        if (!syncEnabled) {
            log.debug("[ScheduledSync] 定时同步已禁用");
            return;
        }

        log.info("[ScheduledSync] ========== 开始定时同步检查 ==========");
        List<SyncTrackingService.SyncSource> sources = syncTrackingService.getAllSyncSources();

        if (sources.isEmpty()) {
            log.info("[ScheduledSync] 没有已记录的数据源，跳过");
            return;
        }

        for (SyncTrackingService.SyncSource source : sources) {
            checkAndSync(source);
        }

        log.info("[ScheduledSync] ========== 定时同步检查完成 ==========");
    }

    /**
     * 检查并同步单个数据源
     */
    private void checkAndSync(SyncTrackingService.SyncSource source) {
        String taskKey = source.host() + ":" + source.port() + "/" + source.database();

        // 检查是否有正在运行的同步任务
        AtomicBoolean running = runningTasks.computeIfAbsent(taskKey, k -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            log.info("[ScheduledSync] 数据源 {} 正在同步中，跳过", taskKey);
            return;
        }

        try {
            log.info("[ScheduledSync] 检查数据源: {}/{}", source.host(), source.database());
            List<SyncTrackingService.SyncTableInfo> tables = syncTrackingService.getSyncTables(
                    source.host(), source.port(), source.database());

            for (SyncTrackingService.SyncTableInfo table : tables) {
                checkTableIncrementalSync(source, table);
            }
        } catch (Exception e) {
            log.error("[ScheduledSync] 检查数据源 {} 失败: {}", taskKey, e.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * 检查表的增量同步
     */
    private void checkTableIncrementalSync(SyncTrackingService.SyncSource source, SyncTrackingService.SyncTableInfo table) {
        try {
            // 构建连接
            MysqlSchemaCatalogService.DynamicConnection conn =
                    new MysqlSchemaCatalogService.DynamicConnection(
                            source.host(),
                            source.port(),
                            source.database(),
                            "root",
                            ""
                    );

            // 获取当前行数
            long currentRowCount = getTableRowCount(conn, table.tableName());
            if (currentRowCount < 0) return;

            // 计算增量
            long delta = currentRowCount - table.lastRowCount();

            if (delta == 0) {
                log.debug("[ScheduledSync] 表 {}.{} 无变化", source.database(), table.tableName());
                return;
            }

            log.info("[ScheduledSync] 检测到变化: {}.{} ({} -> {}, 变化 {} 行)",
                    source.database(), table.tableName(), table.lastRowCount(), currentRowCount, delta);

            // 执行增量学习
            executeIncrementalLearn(source, table.tableName(), currentRowCount);

        } catch (Exception e) {
            log.error("[ScheduledSync] 检查表 {}.{} 失败: {}",
                    source.database(), table.tableName(), e.getMessage());
        }
    }

    /**
     * 获取表的当前行数
     */
    private long getTableRowCount(MysqlSchemaCatalogService.DynamicConnection conn, String tableName) {
        String sql = "SELECT COUNT(*) FROM `" + conn.database() + "`.`" + tableName + "`";
        try (Connection connection = DriverManager.getConnection(conn.toJdbcUrl(), conn.username(), conn.password());
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception e) {
            log.error("[ScheduledSync] 获取行数失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 执行增量学习
     */
    private void executeIncrementalLearn(SyncTrackingService.SyncSource source, String tableName, long currentRowCount) {
        String payload = String.format("""
                # 增量同步学习

                ## 数据源信息
                - 主机：%s
                - 端口：%d
                - 数据库：%s
                - 表名：%s

                ## 同步信息
                - 同步时间：%s
                - 当前行数：%d

                ## 说明
                这是增量数据同步，系统已学习过该表的基础结构，本次为增量更新。
                """,
                source.host(),
                source.port(),
                source.database(),
                tableName,
                LocalDateTime.now(),
                currentRowCount
        );

        try {
            // 执行学习（使用 MySQL only 策略，因为是增量数据）
            activeLearningService.learn(
                    payload,
                    "incremental_sync",
                    source.database() + "." + tableName,
                    "scheduled_sync",
                    "enterprise"
            );

            // 更新同步记录
            syncTrackingService.recordSync(
                    source.host(),
                    source.port(),
                    source.database(),
                    tableName,
                    currentRowCount
            );

            log.info("[ScheduledSync] 增量同步完成: {}.{}", source.database(), tableName);

        } catch (Exception e) {
            log.error("[ScheduledSync] 增量同步失败: {}.{}: {}",
                    source.database(), tableName, e.getMessage());
        }
    }

    /**
     * 手动触发数据源的全量重新同步
     */
    public void triggerFullResync(String host, int port, String database) {
        log.info("[ScheduledSync] 手动触发全量重新同步: {}:{}/{}", host, port, database);
        checkAndSync(new SyncTrackingService.SyncSource(host, port, database));
    }
}