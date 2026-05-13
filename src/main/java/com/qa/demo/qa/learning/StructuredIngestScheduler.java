package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 可选：按 Cron 读取 {@link QaAssistantProperties#getStructuredIngestManifestPath()} 执行行数门禁并写日志。
 */
@Component
@ConditionalOnProperty(prefix = "qa.assistant", name = "structured-ingest-schedule-enabled", havingValue = "true")
public class StructuredIngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(StructuredIngestScheduler.class);

    private final QaAssistantProperties properties;
    private final StructuredIngestJobService ingestJobService;

    public StructuredIngestScheduler(QaAssistantProperties properties, StructuredIngestJobService ingestJobService) {
        this.properties = properties;
        this.ingestJobService = ingestJobService;
    }

    @Scheduled(cron = "${qa.assistant.structured-ingest-schedule-cron:0 0 2 * * ?}")
    public void runFromConfiguredManifest() {
        try {
            StructuredIngestJobService.RunOutcome outcome = ingestJobService.runConfiguredManifestWithAppendLog("schedule");
            log.info("Structured ingest schedule: job={} allowed={}", outcome.manifest().jobName(), outcome.gate().allowedToProceed());
        } catch (IllegalStateException e) {
            log.debug("Structured ingest schedule skipped: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Structured ingest schedule failed: {}", e.toString());
        }
    }
}
