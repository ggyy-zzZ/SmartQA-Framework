package com.qa.demo.qa.admin;

import com.qa.demo.qa.cdc.CdcWriteGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.qa.demo.qa.admin.AdminOpsServiceScripts.*;

/**
 * 7 个长任务编排：Neo4j 同步（含/不含 wipe）、Neo4j wipe-only、Qdrant 知识库/主动学习/wipe+sync、清空会话日志。
 * 每 jobType 全局唯一 ReentrantLock；AdminJob 状态可查询；SSE 流式推送。
 */
@Service
public class AdminOpsService {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsService.class);
    private static final long SSE_TIMEOUT_MS = 600_000L;
    private static final int LOG_TAIL_SNAPSHOT = 5;

    public static final String JOB_NEO4J_SYNC = "neo4j_sync";
    public static final String JOB_NEO4J_SYNC_WIPE = "neo4j_sync_with_wipe";
    public static final String JOB_NEO4J_WIPE = "neo4j_wipe_only";
    public static final String JOB_QDRANT_SYNC = "qdrant_sync";
    public static final String JOB_QDRANT_ACTIVE = "qdrant_active";
    public static final String JOB_QDRANT_WIPE_SYNC = "qdrant_wipe_sync";
    public static final String JOB_TRUNCATE_LOGS = "truncate_session_logs";

    private static final Set<String> KNOWN_JOBS = Set.of(
            JOB_NEO4J_SYNC, JOB_NEO4J_SYNC_WIPE, JOB_NEO4J_WIPE,
            JOB_QDRANT_SYNC, JOB_QDRANT_ACTIVE, JOB_QDRANT_WIPE_SYNC,
            JOB_TRUNCATE_LOGS
    );

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AdminJob> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final PythonRunner runner;
    private final CdcWriteGate cdcWriteGate;
    private final AdminActionLog actionLog;

    public AdminOpsService(PythonRunner runner, CdcWriteGate cdcWriteGate, AdminActionLog actionLog) {
        this.runner = runner;
        this.cdcWriteGate = cdcWriteGate;
        this.actionLog = actionLog;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "admin-ops");
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(4), tf);
        for (String t : KNOWN_JOBS) {
            jobs.put(t, AdminJob.idle(t));
        }
    }

    public SseEmitter startNeo4jSyncNoWipe() {
        return startWithLock(JOB_NEO4J_SYNC, neo4jSyncArgs(false, true, 0), "neo4j-sync");
    }

    public SseEmitter startNeo4jSyncWithWipe() {
        return startWithLock(JOB_NEO4J_SYNC_WIPE, neo4jSyncArgs(true, true, 0), "neo4j.sync-with-wipe");
    }

    public SseEmitter startNeo4jWipeOnly() {
        return startWithLock(JOB_NEO4J_WIPE, neo4jWipeOnlyArgs(), "neo4j.wipe");
    }

    public SseEmitter startQdrantSyncKnowledge() {
        return startWithLock(JOB_QDRANT_SYNC, qdrantSyncKnowledgeArgs(false), "qdrant-sync");
    }

    public SseEmitter startQdrantSyncActive() {
        return startWithLock(JOB_QDRANT_ACTIVE, qdrantSyncActiveLearningArgs(false), "qdrant-sync-active");
    }

    public SseEmitter startQdrantWipeAndSync() {
        return startWithLock(JOB_QDRANT_WIPE_SYNC, qdrantSyncKnowledgeArgs(true), "qdrant.sync-with-wipe");
    }

    public SseEmitter startTruncateSessionLogs() {
        return startWithLock(JOB_TRUNCATE_LOGS, List.of(), "session-logs-truncate");
    }

    public boolean isRunning(String jobType) {
        AdminJob j = jobs.get(jobType);
        return j != null && AdminJob.STATUS_RUNNING.equals(j.status());
    }

    public Map<String, Map<String, Object>> snapshotAllJobs() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String t : KNOWN_JOBS) {
            AdminJob j = jobs.get(t);
            if (j != null) {
                out.put(t, j.toMap());
            }
        }
        return out;
    }

    private SseEmitter startWithLock(String jobType, List<String> scriptArgs, String action) {
        ReentrantLock lock = locks.computeIfAbsent(jobType, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IllegalStateException("job " + jobType + " already running");
        }
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        jobs.put(jobType, AdminJob.started(jobType, jobId));
        actionLog.append(action, jobType, jobId, "started", null, null, null,
                scriptArgs.isEmpty() ? null : scriptArgs, null);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        executor.submit(() -> {
            long t0 = System.currentTimeMillis();
            Consumer<String> sink = line -> {
                if (line == null) {
                    return;
                }
                jobs.computeIfPresent(jobType, (k, v) -> v.appendLine(line));
                safeSend(emitter, SseEmitter.event().name("thinking").data(
                        Map.of("phase", "stdout", "line", line, "ts", System.currentTimeMillis())));
            };
            boolean cdcPaused = false;
            // 区分"脚本真实失败"和"客户端提前断开"：只有前者写 failed 日志
            boolean scriptFailed = false;
            String scriptError = null;
            Integer scriptExitCode = null;
            try {
                cdcWriteGate.pause();
                cdcPaused = true;
                sendMeta(emitter, jobType, jobId, action, scriptArgs);
                if (JOB_TRUNCATE_LOGS.equals(jobType)) {
                    truncateSessionLogs();
                } else {
                    runner.runScript(scriptArgs, sink);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                scriptFailed = true;
                scriptError = "interrupted: " + ie.getMessage();
            } catch (java.io.IOException ioe) {
                // 脚本退出码非 0 / Python 启动失败
                scriptFailed = true;
                scriptError = ioe.getMessage() == null ? ioe.toString() : ioe.getMessage();
                scriptExitCode = 1;
            } catch (RuntimeException re) {
                scriptFailed = true;
                scriptError = re.getMessage() == null ? re.toString() : re.getMessage();
            }
            long dur = System.currentTimeMillis() - t0;
            if (scriptFailed) {
                AdminJob done = jobs.get(jobType).failed(scriptError);
                jobs.put(jobType, done);
                actionLog.append(action, jobType, jobId, "failed", dur, scriptExitCode, scriptError, scriptArgs, null);
                List<String> tail = done.tail();
                List<String> lastN = tail.size() > LOG_TAIL_SNAPSHOT
                        ? tail.subList(tail.size() - LOG_TAIL_SNAPSHOT, tail.size()) : tail;
                safeSend(emitter, SseEmitter.event().name("error").data(
                        Map.of("ok", false, "exitCode", scriptExitCode, "error", scriptError, "tail", lastN)));
            } else {
                AdminJob done = jobs.get(jobType).succeeded();
                jobs.put(jobType, done);
                actionLog.append(action, jobType, jobId, "succeeded", dur, 0, null, scriptArgs, null);
                safeSend(emitter, SseEmitter.event().name("final").data(
                        Map.of("ok", true, "exitCode", 0, "durationMs", dur, "logLineCount", done.tail().size())));
            }
            if (cdcPaused) {
                cdcWriteGate.resume();
            }
            lock.unlock();
            safeSend(emitter, SseEmitter.event().name("done").data(Map.of("ok", !scriptFailed)));
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 客户端已断，completeWithError 之类
            }
        });
        return emitter;
    }

    private static void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder ev) {
        try {
            emitter.send(ev);
        } catch (IOException | IllegalStateException ignored) {
            // 客户端断开 / emitter 已结束 —— 静默忽略，避免污染日志
        }
    }

    private void sendMeta(SseEmitter emitter, String jobType, String jobId, String action, List<String> args) {
        safeSend(emitter, SseEmitter.event().name("meta").data(
                Map.of("jobId", jobId, "jobType", jobType, "action", action,
                        "args", args, "startedAt", System.currentTimeMillis())));
    }

    private void truncateSessionLogs() throws IOException {
        // 文件 truncate（保 admin_actions.jsonl）
        List<Path> targets = new ArrayList<>();
        targets.add(Paths.get("data/qa_logs/ask_events.jsonl"));
        targets.add(Paths.get("data/qa_logs/feedback_events.jsonl"));
        targets.add(Paths.get("data/qa_logs/knowledge_candidates.jsonl"));
        int totalCleared = 0;
        for (Path p : targets) {
            if (Files.exists(p)) {
                long before = Files.size(p);
                Files.writeString(p, "", StandardOpenOption.TRUNCATE_EXISTING);
                totalCleared += 1;
                log.info("[admin] truncated {} (was {} bytes)", p, before);
            }
        }
        if (totalCleared == 0) {
            log.info("[admin] no session log files found to truncate");
        }
    }
}
