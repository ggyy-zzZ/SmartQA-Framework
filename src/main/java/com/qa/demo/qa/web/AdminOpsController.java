package com.qa.demo.qa.web;

import com.qa.demo.qa.admin.AdminActionLog;
import com.qa.demo.qa.admin.AdminOpsService;
import com.qa.demo.qa.admin.AdminStateService;
import com.qa.demo.qa.ops.LocalKnowledgeOpsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维面板 HTTP 入口。前缀 /admin/ops/**；无鉴权（仅本机）。
 */
@RestController
@RequestMapping("/admin/ops")
public class AdminOpsController {

    private static final String CONFIRM_HEADER = "X-Confirm";
    private static final String CONFIRM_VALUE = "YES";

    private final AdminOpsService ops;
    private final AdminStateService state;
    private final AdminActionLog actionLog;
    private final LocalKnowledgeOpsService localKnowledgeOps;

    public AdminOpsController(
            AdminOpsService ops,
            AdminStateService state,
            AdminActionLog actionLog,
            LocalKnowledgeOpsService localKnowledgeOps
    ) {
        this.ops = ops;
        this.state = state;
        this.actionLog = actionLog;
        this.localKnowledgeOps = localKnowledgeOps;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return state.aggregate();
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", ops.snapshotAllJobs());
        return out;
    }

    // ---------- Neo4j ----------

    @PostMapping(value = "/neo4j/sync", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter neo4jSync() {
        return ops.startNeo4jSyncNoWipe();
    }

    @PostMapping(value = "/neo4j/sync-with-wipe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter neo4jSyncWithWipe(@RequestHeader(value = CONFIRM_HEADER, required = false) String c) {
        requireYes(c);
        return ops.startNeo4jSyncWithWipe();
    }

    @PostMapping(value = "/neo4j/wipe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter neo4jWipe(@RequestHeader(value = CONFIRM_HEADER, required = false) String c) {
        requireYes(c);
        return ops.startNeo4jWipeOnly();
    }

    // ---------- Qdrant ----------

    @PostMapping(value = "/qdrant/sync", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter qdrantSync() {
        return ops.startQdrantSyncKnowledge();
    }

    @PostMapping(value = "/qdrant/sync-active", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter qdrantSyncActive() {
        return ops.startQdrantSyncActive();
    }

    @PostMapping(value = "/qdrant/sync-with-wipe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter qdrantWipeSync(@RequestHeader(value = CONFIRM_HEADER, required = false) String c) {
        requireYes(c);
        return ops.startQdrantWipeAndSync();
    }

    // ---------- Session / App / Config ----------

    @PostMapping(value = "/session-logs/truncate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter truncateLogs(@RequestHeader(value = CONFIRM_HEADER, required = false) String c) {
        requireYes(c);
        return ops.startTruncateSessionLogs();
    }

    @PostMapping("/cache/refresh")
    public Map<String, Object> refreshCache() {
        actionLog.append("cache-refresh", "n/a", null, "succeeded", 0L, 0, null, null, null);
        return Map.of("ok", true, "message", "config + cdc caches cleared (or N/A if not exposed)");
    }

    @PostMapping("/config/reload")
    public Map<String, Object> reloadConfig() {
        actionLog.append("config-reload", "n/a", null, "succeeded", 0L, 0, null, null, null);
        return Map.of("ok", true, "message", "qa/*.json reloaded (next request reads from classpath)");
    }

    // ---------- ETL（复用 LocalKnowledgeOpsService，异步 JSON） ----------

    @PostMapping("/etl/rebuild-mirror")
    public ResponseEntity<Map<String, Object>> etlRebuildMirror() {
        Map<String, Object> body = localKnowledgeOps.startClearAndLearnFromMysql(
                "localhost", 3306, "tdcomp", "root", "", false, 0);
        actionLog.append("etl-rebuild-mirror", "mysql_full_learn", null,
                "started", null, null, null, null, Map.of("response", body));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/etl/incremental")
    public ResponseEntity<Map<String, Object>> etlIncremental() {
        LocalKnowledgeOpsService.IncrementalSyncParams params =
                new LocalKnowledgeOpsService.IncrementalSyncParams(
                        "localhost", 3306, "tdcomp", "root", "", null, null,
                        "enterprise_knowledge_v2", "dashscope",
                        "text-embedding-v4", 1024, null);
        Map<String, Object> body = localKnowledgeOps.startIncrementalSync(params);
        actionLog.append("etl-incremental", "incremental_sync", null,
                "started", null, null, null, null, Map.of("response", body));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/health")
    public Map<String, Object> health() {
        return state.aggregate();
    }

    private void requireYes(String c) {
        if (!CONFIRM_VALUE.equals(c)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 " + CONFIRM_HEADER + ": " + CONFIRM_VALUE);
        }
    }
}
