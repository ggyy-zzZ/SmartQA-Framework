package com.qa.demo.qa.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.ops.LocalKnowledgeOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 企业结构化知识增量同步：编排 Python incremental job 并更新 {@code sync_entity_state}。
 */
@Service
public class EnterpriseKnowledgeSyncService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseKnowledgeSyncService.class);
    private static final String DEFAULT_ENTITY_TYPE = "Company";
    private static final String JSONL_REL = "data/knowledge/enterprise_mysql_clean.jsonl";
    private static final DateTimeFormatter SINCE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final QaAssistantProperties properties;
    private final LocalKnowledgeOpsService localKnowledgeOpsService;
    private final SyncEntityStateService syncEntityStateService;
    private final ObjectMapper objectMapper;

    public EnterpriseKnowledgeSyncService(
            QaAssistantProperties properties,
            LocalKnowledgeOpsService localKnowledgeOpsService,
            SyncEntityStateService syncEntityStateService
    ) {
        this.properties = properties;
        this.localKnowledgeOpsService = localKnowledgeOpsService;
        this.syncEntityStateService = syncEntityStateService;
        this.objectMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public record IncrementalSyncRequest(
            String host,
            int port,
            String database,
            String username,
            String password,
            String since,
            String companyIds,
            String domain,
            boolean async
    ) {
    }

    public Map<String, Object> runIncrementalFromConfiguration(boolean async) {
        ParsedJdbc jdbc = parseJdbcUrl(properties.getBusinessMysqlUrl());
        return runIncremental(new IncrementalSyncRequest(
                jdbc.host(),
                jdbc.port(),
                jdbc.database(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword(),
                "",
                "",
                properties.getKnowledgeSyncDomain(),
                async
        ));
    }

    public Map<String, Object> runIncremental(IncrementalSyncRequest request) {
        LocalKnowledgeOpsService.IncrementalSyncParams params = buildOpsParams(request);
        if (request.async()) {
            return localKnowledgeOpsService.startIncrementalSync(params);
        }
        return runIncrementalBlocking(request, params);
    }

    private Map<String, Object> runIncrementalBlocking(
            IncrementalSyncRequest request,
            LocalKnowledgeOpsService.IncrementalSyncParams params
    ) {
        String batchId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String domain = blankToDefault(request.domain(), properties.getKnowledgeSyncDomain());
        String since = resolveSince(request.since(), domain);
        LocalKnowledgeOpsService.IncrementalSyncParams effective = new LocalKnowledgeOpsService.IncrementalSyncParams(
                params.host(),
                params.port(),
                params.schema(),
                params.username(),
                params.password(),
                since,
                params.companyIds(),
                params.qdrantCollection(),
                params.embeddingProvider(),
                params.embeddingModel(),
                params.embeddingDim(),
                params.embeddingApiKey()
        );
        List<String> logs = new ArrayList<>();

        try {
            logs.addAll(localKnowledgeOpsService.runIncrementalSyncScript(effective));
            int recorded = recordEntityStatesFromJsonl(domain, batchId);
            logs.add("recorded sync_entity_state rows=" + recorded);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("batchId", batchId);
            body.put("domain", domain);
            body.put("since", since);
            body.put("entitiesRecorded", recorded);
            body.put("entityStateTotal", syncEntityStateService.countByDomain(domain));
            body.put("logs", logs);
            body.put("message", "增量同步完成");
            return body;
        } catch (Exception e) {
            log.error("[KnowledgeSync] incremental failed: {}", e.getMessage(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("batchId", batchId);
            body.put("message", e.getMessage() != null ? e.getMessage() : e.toString());
            body.put("logs", logs);
            return body;
        }
    }

    /**
     * 与 {@link #runIncrementalFromConfiguration} 使用的 since 一致（空请求 = 水位 / 默认回溯小时）。
     */
    public String currentIncrementalSince() {
        return resolveSince("", properties.getKnowledgeSyncDomain());
    }

    private String resolveSince(String requestedSince, String domain) {
        if (requestedSince != null && !requestedSince.isBlank()) {
            return requestedSince.trim();
        }
        Optional<LocalDateTime> latest = syncEntityStateService.latestSyncTime(domain);
        if (latest.isPresent()) {
            return latest.get().format(SINCE_FMT);
        }
        int hours = Math.max(1, properties.getKnowledgeSyncDefaultSinceHours());
        return LocalDateTime.now().minusHours(hours).format(SINCE_FMT);
    }

    private int recordEntityStatesFromJsonl(String domain, String batchId) throws Exception {
        Path jsonl = localKnowledgeOpsService.projectRootPath().resolve(JSONL_REL);
        if (!Files.exists(jsonl)) {
            return 0;
        }
        int count = 0;
        for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(line);
            String entityId = node.path("company_id").asText("").trim();
            if (entityId.isEmpty()) {
                continue;
            }
            String hash = contentHash(node);
            Optional<SyncEntityStateService.EntitySyncState> existing =
                    syncEntityStateService.find(domain, DEFAULT_ENTITY_TYPE, entityId);
            if (existing.isPresent() && hash.equals(existing.get().contentHash())) {
                continue;
            }
            syncEntityStateService.upsert(domain, DEFAULT_ENTITY_TYPE, entityId, hash, batchId);
            count++;
        }
        return count;
    }

    private String contentHash(JsonNode node) throws Exception {
        TreeMap<String, Object> sorted = new TreeMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            sorted.put(entry.getKey(), objectMapper.treeToValue(entry.getValue(), Object.class));
        }
        byte[] json = objectMapper.writeValueAsBytes(sorted);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(json);
        StringBuilder sb = new StringBuilder(hashed.length * 2);
        for (byte b : hashed) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private LocalKnowledgeOpsService.IncrementalSyncParams buildOpsParams(IncrementalSyncRequest request) {
        ParsedJdbc jdbc = parseJdbcUrl(properties.getBusinessMysqlUrl());
        String host = blankToDefault(request.host(), jdbc.host());
        int port = request.port() > 0 ? request.port() : jdbc.port();
        String schema = blankToDefault(request.database(), jdbc.database());
        String username = blankToDefault(request.username(), properties.getBusinessMysqlUsername());
        String password = request.password() != null && !request.password().isBlank()
                ? request.password()
                : properties.getBusinessMysqlPassword();
        String dashKey = properties.getDashscopeApiKey();
        String embeddingProvider = "dashscope".equalsIgnoreCase(properties.getEmbeddingProvider())
                && dashKey != null && !dashKey.isBlank()
                ? "dashscope"
                : "hash";
        return new LocalKnowledgeOpsService.IncrementalSyncParams(
                host,
                port,
                schema,
                username,
                password != null ? password : "",
                request.since() != null ? request.since() : "",
                request.companyIds() != null ? request.companyIds() : "",
                properties.getQdrantCollection(),
                embeddingProvider,
                properties.getEmbeddingModel(),
                properties.getVectorEmbeddingDim(),
                dashKey != null ? dashKey : ""
        );
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static ParsedJdbc parseJdbcUrl(String url) {
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
}
