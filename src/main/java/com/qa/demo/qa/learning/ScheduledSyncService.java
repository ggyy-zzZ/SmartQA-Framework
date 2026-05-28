package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时检测业务库水位变化，有变更时触发 EKSP 增量同步。
 */
@Service
public class ScheduledSyncService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSyncService.class);

    private final QaAssistantProperties properties;
    private final EnterpriseKnowledgeSyncService knowledgeSyncService;
    private final KnowledgeSyncChangeDetector changeDetector;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ScheduledSyncService(
            QaAssistantProperties properties,
            EnterpriseKnowledgeSyncService knowledgeSyncService,
            KnowledgeSyncChangeDetector changeDetector
    ) {
        this.properties = properties;
        this.knowledgeSyncService = knowledgeSyncService;
        this.changeDetector = changeDetector;
    }

    /**
     * 轮询业务库 company.updated_at；仅当 {@code knowledge-sync-incremental-scheduled-enabled=true} 时生效。
     */
    @Scheduled(fixedDelayString = "${qa.assistant.knowledge-sync-poll-interval-ms:60000}")
    public void scheduledSync() {
        // CDC 模式启用时，禁用 Polling 方式的定时同步
        if (properties.isCdcEnabled()) {
            log.debug("[ScheduledSync] CDC 模式已启用，跳过 Polling 定时同步");
            return;
        }
        if (!properties.getEnableScheduledSync()) {
            return;
        }
        if (!properties.isKnowledgeSyncIncrementalScheduledEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("[ScheduledSync] 增量同步仍在执行，跳过本轮");
            return;
        }
        try {
            if (properties.isKnowledgeSyncWatermarkWatchOnly()) {
                KnowledgeSyncChangeDetector.ChangeProbe probe = changeDetector.probe();
                if (!probe.available()) {
                    log.warn("[ScheduledSync] 水位探测不可用: {}", probe.message());
                    return;
                }
                if (!probe.changed()) {
                    log.debug("[ScheduledSync] 无水位变化 since={} anchor={}", probe.since(), probe.anchorTable());
                    return;
                }
                log.info(
                        "[ScheduledSync] 检测到 {} 条变更 (maxUpdatedAt={}, since={})，触发增量同步",
                        probe.changedCount(),
                        probe.maxUpdatedAt(),
                        probe.since()
                );
            } else {
                log.info("[ScheduledSync] 水位监测已关闭，按轮询无条件触发增量同步");
            }

            log.info("[ScheduledSync] ========== 开始 EKSP 增量同步 ==========");
            Map<String, Object> result = knowledgeSyncService.runIncrementalFromConfiguration(false);
            log.info("[ScheduledSync] 增量同步结果: ok={} batchId={} entitiesRecorded={}",
                    result.get("ok"), result.get("batchId"), result.get("entitiesRecorded"));
        } catch (Exception e) {
            log.error("[ScheduledSync] 增量同步异常: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 手动触发（供运维或测试）：先探测，无变更时返回 skipped。
     */
    public Map<String, Object> triggerIncrementalIfChanged() {
        KnowledgeSyncChangeDetector.ChangeProbe probe = changeDetector.probe();
        Map<String, Object> body = new java.util.LinkedHashMap<>(probe.toMap());
        if (!probe.available()) {
            body.put("ok", false);
            body.put("skipped", true);
            body.put("message", probe.message());
            return body;
        }
        if (!probe.changed()) {
            body.put("ok", true);
            body.put("skipped", true);
            body.put("message", "无水位变化，未执行同步");
            return body;
        }
        Map<String, Object> syncResult = knowledgeSyncService.runIncrementalFromConfiguration(false);
        body.putAll(syncResult);
        body.put("skipped", false);
        return body;
    }

    /**
     * 手动触发增量同步（不探测，强制执行）。
     */
    public Map<String, Object> triggerIncrementalNow() {
        return knowledgeSyncService.runIncrementalFromConfiguration(false);
    }
}
