package com.qa.demo.qa.ops;

import com.qa.demo.qa.cdc.CdcWriteGate;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.store.EntitySnapshotRepository;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 调用仓库内 Python 运维脚本：清空三库、从 JSONL 重建 Neo4j + Qdrant。
 * 供本地 playground 页面触发；长时间任务异步执行。
 */
@Service
public class LocalKnowledgeOpsService {

    private static final String DEFAULT_JSONL = "data/knowledge/enterprise_mysql_clean.jsonl";
    private static final int MAX_LOG_LINES = 500;

    private final QaAssistantProperties properties;
    private final CdcWriteGate cdcWriteGate;
    private final EntitySnapshotRepository entitySnapshotRepository;
    private final Path projectRoot;
    private final String pythonCommand;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "local-knowledge-ops");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<OpsJob> currentJob = new AtomicReference<>(OpsJob.idle());

    public LocalKnowledgeOpsService(
            QaAssistantProperties properties,
            CdcWriteGate cdcWriteGate,
            EntitySnapshotRepository entitySnapshotRepository
    ) {
        this.properties = properties;
        this.cdcWriteGate = cdcWriteGate;
        this.entitySnapshotRepository = entitySnapshotRepository;
        this.projectRoot = resolveProjectRoot();
        this.pythonCommand = detectPythonCommand();
    }

    public Map<String, Object> statusSnapshot() {
        Path jsonl = resolveJsonlPath();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectRoot", projectRoot.toString());
        body.put("pythonCommand", pythonCommand);
        body.put("jsonlPath", jsonl.toString());
        body.put("jsonlExists", Files.exists(jsonl));
        body.put("jsonlLineCount", Files.exists(jsonl) ? countLines(jsonl) : 0);
        body.put("entitySnapshotInMysql", entitySnapshotRepository.hasAny(properties.getConfigScope()));
        body.put("qdrantCollection", properties.getQdrantCollection());
        body.put("embeddingProvider", properties.getEmbeddingProvider());
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("job", currentJob.get().toMap());
        body.put("businessMysql", businessMysqlDefaults());
        return body;
    }

    private Map<String, Object> businessMysqlDefaults() {
        ParsedJdbc parsed = parseMysqlJdbcUrl(properties.getBusinessMysqlUrl());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", parsed.host());
        m.put("port", parsed.port());
        m.put("database", parsed.database());
        m.put("username", properties.getBusinessMysqlUsername());
        m.put("passwordConfigured", properties.getBusinessMysqlPassword() != null
                && !properties.getBusinessMysqlPassword().isBlank());
        return m;
    }

    /**
     * 清空 Qdrant / Neo4j / MySQL(assistant)，可选清空 qa 日志。
     */
    public Map<String, Object> startFullReset(boolean clearLogs) {
        List<String> args = new ArrayList<>();
        args.add(scriptPath("scripts/ops/reset_local_stores.py"));
        if (clearLogs) {
            args.add("--clear-logs");
        }
        return startJob("reset", () -> runPythonScriptUnchecked(args));
    }

    public Map<String, Object> startRebuild(boolean clearLogs, boolean skipReset, int limit) {
        Path jsonlPath = resolveJsonlPath();
        if (!Files.exists(jsonlPath)) {
            return Map.of(
                    "ok", false,
                    "message", "缺少 " + DEFAULT_JSONL + "，请先执行 build_knowledge_from_mysql.py 生成清洗数据。",
                    "jsonlPath", jsonlPath.toString()
            );
        }
        List<String> args = new ArrayList<>();
        args.add(scriptPath("scripts/ops/rebuild_local_knowledge.py"));
        if (skipReset) {
            args.add("--skip-reset");
        }
        if (clearLogs) {
            args.add("--clear-logs");
        }
        if (limit > 0) {
            args.add("--limit");
            args.add(String.valueOf(limit));
        }
        return startJob("rebuild", () -> runPythonScriptUnchecked(args));
    }

    /**
     * 清空三库 → 从业务 MySQL（如 tdcomp）导出 JSONL → 同步 Neo4j + Qdrant（百炼向量）。
     */
    public Map<String, Object> startClearAndLearnFromMysql(
            String host,
            int port,
            String schema,
            String username,
            String password,
            boolean clearLogs,
            int limit) {
        if (host == null || host.isBlank()) {
            return Map.of("ok", false, "message", "请填写 MySQL 主机。");
        }
        if (schema == null || schema.isBlank()) {
            return Map.of("ok", false, "message", "请填写数据库名（如 tdcomp）。");
        }
        if (username == null || username.isBlank()) {
            return Map.of("ok", false, "message", "请填写 MySQL 用户名。");
        }
        String effectivePassword = password;
        if (effectivePassword == null || effectivePassword.isBlank()) {
            effectivePassword = properties.getBusinessMysqlPassword();
        }
        final String pwd = effectivePassword != null ? effectivePassword : "";
        final int effectivePort = port > 0 ? port : 3306;
        final int effectiveLimit = Math.max(0, limit);

        return startJob("mysql_full_learn", () -> {
            List<String> resetArgs = new ArrayList<>();
            resetArgs.add(scriptPath("scripts/ops/reset_local_stores.py"));
            if (clearLogs) {
                resetArgs.add("--clear-logs");
            }
            runPythonScriptUnchecked(resetArgs);

            List<String> buildArgs = new ArrayList<>();
            buildArgs.add(scriptPath("scripts/enterprise_pipeline/build_knowledge_from_mysql.py"));
            buildArgs.add("--host");
            buildArgs.add(host.trim());
            buildArgs.add("--port");
            buildArgs.add(String.valueOf(effectivePort));
            buildArgs.add("--username");
            buildArgs.add(username.trim());
            buildArgs.add("--password");
            buildArgs.add(pwd);
            buildArgs.add("--schema");
            buildArgs.add(schema.trim());
            if (effectiveLimit > 0) {
                buildArgs.add("--limit");
                buildArgs.add(String.valueOf(effectiveLimit));
            }
            runPythonScriptUnchecked(buildArgs);

            Path jsonl = resolveJsonlPath();
            if (!Files.exists(jsonl)) {
                throw new IllegalStateException("导出后未找到 " + DEFAULT_JSONL);
            }

            List<String> neo4jArgs = new ArrayList<>();
            neo4jArgs.add(scriptPath("scripts/enterprise_pipeline/sync_neo4j.py"));
            neo4jArgs.add("--input");
            neo4jArgs.add(DEFAULT_JSONL);
            neo4jArgs.add("--wipe");
            neo4jArgs.add("--slim");
            if (effectiveLimit > 0) {
                neo4jArgs.add("--limit");
                neo4jArgs.add(String.valueOf(effectiveLimit));
            }
            runPythonScriptUnchecked(neo4jArgs);

            List<String> vecArgs = new ArrayList<>();
            vecArgs.add(scriptPath("scripts/enterprise_pipeline/sync_vectors_qdrant.py"));
            vecArgs.add("--input");
            vecArgs.add(DEFAULT_JSONL);
            vecArgs.add("--collection");
            vecArgs.add(properties.getQdrantCollection());
            vecArgs.add("--embedding-provider");
            vecArgs.add(resolveEmbeddingProviderForScript());
            vecArgs.add("--embedding-model");
            vecArgs.add(properties.getEmbeddingModel());
            vecArgs.add("--embedding-dim");
            vecArgs.add(String.valueOf(properties.getVectorEmbeddingDim()));
            vecArgs.add("--recreate");
            String dashKey = properties.getDashscopeApiKey();
            if ("dashscope".equalsIgnoreCase(properties.getEmbeddingProvider())
                    && dashKey != null
                    && !dashKey.isBlank()) {
                vecArgs.add("--embedding-api-key");
                vecArgs.add(dashKey);
            }
            if (effectiveLimit > 0) {
                vecArgs.add("--limit");
                vecArgs.add(String.valueOf(effectiveLimit));
            }
            runPythonScriptUnchecked(vecArgs);
        });
    }

    private String resolveEmbeddingProviderForScript() {
        String provider = properties.getEmbeddingProvider();
        if (!"dashscope".equalsIgnoreCase(provider)) {
            return "hash";
        }
        String key = properties.getDashscopeApiKey();
        return key != null && !key.isBlank() ? "dashscope" : "hash";
    }

    private Map<String, Object> startJob(String type, Runnable task) {
        OpsJob running = currentJob.get();
        if ("running".equals(running.status())) {
            return Map.of(
                    "ok", false,
                    "message", "已有任务在执行中，请等待完成后再试。",
                    "job", running.toMap()
            );
        }
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        OpsJob job = OpsJob.started(jobId, type);
        currentJob.set(job);
        CompletableFuture.runAsync(() -> {
            cdcWriteGate.pause();
            try {
                task.run();
                OpsJob finished = currentJob.get();
                currentJob.set(finished.succeeded());
            } catch (Exception e) {
                OpsJob finished = currentJob.get();
                currentJob.set(finished.failed(e.getMessage()));
            } finally {
                cdcWriteGate.resume();
            }
        }, executor);
        return Map.of("ok", true, "message", "任务已启动", "job", job.toMap());
    }

    private void runPythonScriptUnchecked(List<String> scriptArgs) {
        try {
            runPythonScript(scriptArgs);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
    }

    private void runPythonScript(List<String> scriptArgs) throws Exception {
        runPythonScriptWithLogSink(scriptArgs, null);
    }

    private void runPythonScriptWithLogSink(List<String> scriptArgs, List<String> logSink) throws Exception {
        if (pythonCommand == null) {
            throw new IllegalStateException("未找到 Python，请安装 Python 3 并加入 PATH。");
        }
        List<String> command = new ArrayList<>();
        command.add(pythonCommand);
        command.addAll(scriptArgs);

        appendLogOrSink("执行: " + String.join(" ", command), logSink);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        applyProcessEnv(pb.environment());
        Charset charset = Charset.defaultCharset();
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLogOrSink(line, logSink);
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("脚本退出码 " + code + "，详见任务日志。");
        }
        appendLogOrSink("脚本执行成功。", logSink);
    }

    private void appendLogOrSink(String line, List<String> logSink) {
        if (line == null) {
            return;
        }
        if (logSink != null) {
            logSink.add(line);
            return;
        }
        appendLog(line);
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        currentJob.updateAndGet(j -> j.appendLog(line));
    }

    private String scriptPath(String relative) {
        return projectRoot.resolve(relative).toString();
    }

    private static Path resolveProjectRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("scripts/ops"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("scripts/ops"))) {
            return parent;
        }
        return cwd;
    }

    private static String detectPythonCommand() {
        for (String cmd : List.of("python", "python3", "py")) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                if (p.waitFor() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private void applyProcessEnv(Map<String, String> env) {
        String dashKey = properties.getDashscopeApiKey();
        if (dashKey != null && !dashKey.isBlank()) {
            env.put("DASHSCOPE_API_KEY", dashKey);
        }
    }

    private static ParsedJdbc parseMysqlJdbcUrl(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) {
            return new ParsedJdbc("localhost", 3306, "tdcomp");
        }
        String rest = url.substring("jdbc:mysql://".length());
        int slash = rest.indexOf('/');
        String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
        String dbPart = slash >= 0 ? rest.substring(slash + 1) : "";
        int q = dbPart.indexOf('?');
        String database = q >= 0 ? dbPart.substring(0, q) : dbPart;
        if (database.isBlank()) {
            database = "tdcomp";
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        int port = 3306;
        if (colon > 0) {
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                port = 3306;
            }
        }
        return new ParsedJdbc(host, port, database);
    }

    private record ParsedJdbc(String host, int port, String database) {
    }

    private Path resolveJsonlPath() {
        Path file = projectRoot.resolve(DEFAULT_JSONL);
        String mode = properties.getEntitySnapshotSource() == null ? "mysql_fallback" : properties.getEntitySnapshotSource();
        if (!"jsonl".equalsIgnoreCase(mode)
                && entitySnapshotRepository.hasAny(properties.getConfigScope())) {
            try {
                entitySnapshotRepository.exportToJsonl(file, properties.getConfigScope(), properties.getKnowledgeSyncDomain());
            } catch (Exception ignored) {
                // fall back to on-disk jsonl
            }
        }
        return file;
    }

    private static long countLines(Path path) {
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 增量同步：build（--since）→ Neo4j upsert → Qdrant upsert（无 wipe/recreate）。
     * 返回脚本日志行，供 {@link com.qa.demo.qa.learning.EnterpriseKnowledgeSyncService} 记录状态。
     */
    public List<String> runIncrementalSyncScript(IncrementalSyncParams params) throws Exception {
        List<String> logs = new ArrayList<>();
        runPythonScriptWithLogSink(buildIncrementalBuildArgs(params), logs);
        Path jsonl = resolveJsonlPath();
        if (!Files.exists(jsonl)) {
            logs.add("无增量数据，跳过 Neo4j/Qdrant upsert");
            return logs;
        }
        runPythonScriptWithLogSink(buildIncrementalNeo4jArgs(), logs);
        runPythonScriptWithLogSink(buildIncrementalVectorArgs(params), logs);
        return logs;
    }

    public Map<String, Object> startIncrementalSync(IncrementalSyncParams params) {
        return startJob("incremental_sync", () -> {
            try {
                runIncrementalSyncScript(params);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
            }
        });
    }

    public Path projectRootPath() {
        return projectRoot;
    }

    public record IncrementalSyncParams(
            String host,
            int port,
            String schema,
            String username,
            String password,
            String since,
            String companyIds,
            String qdrantCollection,
            String embeddingProvider,
            String embeddingModel,
            int embeddingDim,
            String embeddingApiKey
    ) {
    }

    private List<String> buildIncrementalBuildArgs(IncrementalSyncParams params) {
        List<String> args = new ArrayList<>();
        args.add(scriptPath("scripts/enterprise_pipeline/build_knowledge_from_mysql.py"));
        args.add("--host");
        args.add(params.host());
        args.add("--port");
        args.add(String.valueOf(params.port()));
        args.add("--username");
        args.add(params.username());
        args.add("--password");
        args.add(params.password());
        args.add("--schema");
        args.add(params.schema());
        if (params.since() != null && !params.since().isBlank()) {
            args.add("--since");
            args.add(params.since());
        }
        if (params.companyIds() != null && !params.companyIds().isBlank()) {
            args.add("--company-ids");
            args.add(params.companyIds());
        }
        return args;
    }

    private List<String> buildIncrementalNeo4jArgs() {
        List<String> args = new ArrayList<>();
        args.add(scriptPath("scripts/enterprise_pipeline/sync_neo4j.py"));
        args.add("--input");
        args.add(DEFAULT_JSONL);
        args.add("--slim");
        return args;
    }

    private List<String> buildIncrementalVectorArgs(IncrementalSyncParams params) {
        List<String> args = new ArrayList<>();
        args.add(scriptPath("scripts/enterprise_pipeline/sync_vectors_qdrant.py"));
        args.add("--input");
        args.add(DEFAULT_JSONL);
        args.add("--collection");
        args.add(params.qdrantCollection());
        args.add("--embedding-provider");
        args.add(params.embeddingProvider());
        args.add("--embedding-model");
        args.add(params.embeddingModel());
        args.add("--embedding-dim");
        args.add(String.valueOf(params.embeddingDim()));
        if ("dashscope".equalsIgnoreCase(params.embeddingProvider())
                && params.embeddingApiKey() != null
                && !params.embeddingApiKey().isBlank()) {
            args.add("--embedding-api-key");
            args.add(params.embeddingApiKey());
        }
        return args;
    }

    private record OpsJob(
            String jobId,
            String type,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            List<String> logs,
            String error
    ) {
        static OpsJob idle() {
            return new OpsJob(null, null, "idle", null, null, List.of(), null);
        }

        static OpsJob started(String jobId, String type) {
            return new OpsJob(jobId, type, "running", OffsetDateTime.now(), null, new ArrayList<>(), null);
        }

        OpsJob appendLog(String line) {
            List<String> next = new ArrayList<>(logs);
            next.add(line);
            while (next.size() > MAX_LOG_LINES) {
                next.removeFirst();
            }
            return new OpsJob(jobId, type, status, startedAt, finishedAt, List.copyOf(next), error);
        }

        OpsJob succeeded() {
            return new OpsJob(jobId, type, "success", startedAt, OffsetDateTime.now(), logs, null);
        }

        OpsJob failed(String message) {
            return new OpsJob(jobId, type, "failed", startedAt, OffsetDateTime.now(), logs, message);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", jobId);
            m.put("type", type);
            m.put("status", status);
            m.put("startedAt", startedAt != null ? startedAt.toString() : null);
            m.put("finishedAt", finishedAt != null ? finishedAt.toString() : null);
            m.put("logs", logs);
            m.put("error", error);
            return m;
        }
    }
}
