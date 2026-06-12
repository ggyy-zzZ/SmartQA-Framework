package com.qa.demo.qa.admin;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.store.EntitySnapshotRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.Socket;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 4 卡状态聚合（服务探活 / Neo4j 计数 / Qdrant 集合 / 最近日志）。
 * 并行 CompletableFuture，1.8s 超时降级为 {ok:false, reason:"timeout"}。
 */
@Service
public class AdminStateService {

    private static final Logger log = LoggerFactory.getLogger(AdminStateService.class);
    private static final long AGGREGATE_BUDGET_MS = 4500L;
    private static final int PING_TIMEOUT_MS = 3000;
    private static final List<String> KNOWN_COLLECTIONS = List.of(
            "enterprise_knowledge_v1", "enterprise_knowledge_v2",
            "enterprise_active_learning_v1", "enterprise_active_learning_v2"
    );

    private final Driver neo4jDriver;
    private final RestClient qdrantClient;
    private final QaAssistantProperties properties;
    private final EntitySnapshotRepository entitySnapshotRepository;
    private final AdminActionLog actionLog;
    private final String appName;

    public AdminStateService(
            Driver neo4jDriver,
            QaAssistantProperties properties,
            EntitySnapshotRepository entitySnapshotRepository,
            AdminActionLog actionLog,
            org.springframework.core.env.Environment env
    ) {
        this.neo4jDriver = neo4jDriver;
        this.properties = properties;
        this.entitySnapshotRepository = entitySnapshotRepository;
        this.actionLog = actionLog;
        this.qdrantClient = RestClient.builder().baseUrl(properties.getQdrantUrl()).build();
        this.appName = env == null ? "demo" : env.getProperty("spring.application.name", "demo");
    }

    public Map<String, Object> aggregate() {
        CompletableFuture<Map<String, Object>> fNeo4j = supplyAsync(this::pingNeo4j);
        CompletableFuture<Map<String, Object>> fQdrant = supplyAsync(this::pingQdrant);
        CompletableFuture<Map<String, Object>> fMysql = supplyAsync(this::pingMysql);
        CompletableFuture<Map<String, Object>> fKafka = supplyAsync(this::pingKafka);
        CompletableFuture<Map<String, Object>> fDashscope = supplyAsync(this::pingDashscope);
        CompletableFuture<Map<String, Object>> fQaApp = CompletableFuture.completedFuture(pingQaApp());
        CompletableFuture<Map<String, Object>> fCounts = supplyAsync(this::neo4jCounts);
        CompletableFuture<Map<String, Object>> fQdCols = supplyAsync(this::qdrantCollections);
        CompletableFuture<List<Map<String, Object>>> fRecent = supplyAsync(() -> actionLog.readRecent(5));

        try {
            CompletableFuture.allOf(fNeo4j, fQdrant, fMysql, fKafka, fDashscope, fCounts, fQdCols, fRecent)
                    .get(AGGREGATE_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            // 降级：未完成的 future 会 join() 抛 CancellationException / null
        }

        Map<String, Object> services = new LinkedHashMap<>();
        services.put("neo4j", safeJoin(fNeo4j));
        services.put("qdrant", safeJoin(fQdrant));
        services.put("mysql", safeJoin(fMysql));
        services.put("kafka", safeJoin(fKafka));
        services.put("dashscope", safeJoin(fDashscope));
        services.put("qaApp", safeJoin(fQaApp));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", OffsetDateTime.now().toString());
        out.put("services", services);
        out.put("neo4jCounts", safeJoin(fCounts));
        out.put("qdrantCollections", safeJoin(fQdCols));
        out.put("recentAdminActions", safeJoin(fRecent));
        return out;
    }

    private <T> CompletableFuture<T> supplyAsync(java.util.function.Supplier<T> s) {
        return CompletableFuture.supplyAsync(s)
                .orTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(t -> null);
    }

    private <T> T safeJoin(CompletableFuture<T> f) {
        try {
            return f == null ? null : f.getNow(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> pingNeo4j() {
        long t0 = System.currentTimeMillis();
        try (Session s = neo4jDriver.session()) {
            // 轻量探活：仅查 Company / Person 计数（用 count store 命中索引）
            Record r = s.run(
                    "MATCH (c:Company) WITH count(c) AS cc " +
                    "MATCH (p:Person) WITH cc, count(p) AS pp " +
                    "RETURN cc AS companies, pp AS persons"
            ).single();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("uri", "bolt://localhost:7687");
            m.put("companies", r.get("companies").asLong());
            m.put("persons", r.get("persons").asLong());
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("error", truncate(e.getMessage(), 200));
            return m;
        }
    }

    private Map<String, Object> pingQdrant() {
        long t0 = System.currentTimeMillis();
        try {
            String body = qdrantClient.get()
                    .uri("/")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("url", properties.getQdrantUrl());
            m.put("version", body == null ? "" : truncate(body, 200));
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("error", truncate(e.getMessage(), 200));
            return m;
        }
    }

    private Map<String, Object> pingMysql() {
        long t0 = System.currentTimeMillis();
        try {
            boolean ok = entitySnapshotRepository.hasAny(properties.getConfigScope());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("scope", properties.getConfigScope());
            m.put("hasSnapshot", ok);
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("error", truncate(e.getMessage(), 200));
            return m;
        }
    }

    private Map<String, Object> pingKafka() {
        // Kafka 9092 是 TCP 协议，HTTP 探不活；这里用 socket connect 探活
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("localhost", 9092), PING_TIMEOUT_MS);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("endpoint", "localhost:9092");
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("endpoint", "localhost:9092");
            m.put("error", truncate(e.getMessage(), 200));
            return m;
        }
    }

    private Map<String, Object> pingDashscope() {
        Map<String, Object> m = new LinkedHashMap<>();
        String key = properties.getDashscopeApiKey();
        m.put("ok", key != null && !key.isBlank());
        m.put("keyConfigured", key != null && !key.isBlank());
        m.put("provider", properties.getEmbeddingProvider());
        m.put("model", properties.getEmbeddingModel());
        return m;
    }

    private Map<String, Object> pingQaApp() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("application", appName);
        m.put("port", 8080);
        return m;
    }

    private Map<String, Object> neo4jCounts() {
        // 仅对白名单 Label 做 count store 命中（避免 MATCH (n) 全图扫描）
        List<String> knownLabels = List.of("Company", "Person", "Certificate", "Branch", "Partner", "Industry", "Region");
        try (Session s = neo4jDriver.session()) {
            Map<String, Long> byLabel = new LinkedHashMap<>();
            for (String lbl : knownLabels) {
                try {
                    long n = s.run("MATCH (n:" + lbl + ") RETURN count(n) AS n").single().get("n").asLong();
                    byLabel.put(lbl, n);
                } catch (Exception perLabel) {
                    // 标签不存在时跳过（count store 命中 0 也算成功）
                    byLabel.put(lbl, 0L);
                }
            }
            long rels = 0L;
            try {
                rels = s.run("MATCH ()-[r]->() RETURN count(r) AS n").single().get("n").asLong();
            } catch (Exception ignored) {
                // 图为空 / 索引未建时也算 0
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("byLabel", byLabel);
            m.put("relationshipCount", rels);
            return m;
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", truncate(e.getMessage(), 200));
            return m;
        }
    }

    private Map<String, Object> qdrantCollections() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("knownNames", KNOWN_COLLECTIONS);
        List<Map<String, Object>> cols = new ArrayList<>();
        try {
            String body = qdrantClient.get()
                    .uri("/collections")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            // 简化解析：只提取 result.collections[].name → points_count
            // 避免引 Jackson 解析全部字段
            int from = body == null ? -1 : body.indexOf("\"collections\"");
            if (from >= 0) {
                int arr = body.indexOf('[', from);
                int end = body.indexOf(']', arr);
                if (arr > 0 && end > arr) {
                    String inner = body.substring(arr + 1, end);
                    for (String seg : inner.split("\\},\\{")) {
                        String name = extractJsonString(seg, "name");
                        if (name != null && KNOWN_COLLECTIONS.contains(name)) {
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("name", name);
                            // 调 GET /collections/{name} 拿详细信息
                            try {
                                String detail = qdrantClient.get()
                                        .uri("/collections/{name}", name)
                                        .retrieve()
                                        .body(String.class);
                                info.put("pointsCount", extractJsonLong(detail, "points_count"));
                                info.put("vectorSize", extractJsonLong(detail, "size"));
                                info.put("status", extractJsonString(detail, "status"));
                            } catch (RestClientException e) {
                                info.put("error", truncate(e.getMessage(), 100));
                            }
                            cols.add(info);
                        }
                    }
                }
            }
        } catch (Exception e) {
            out.put("error", truncate(e.getMessage(), 200));
        }
        out.put("collections", cols);
        return out;
    }

    private static String extractJsonString(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String token = "\"" + key + "\":\"";
        int i = json.indexOf(token);
        if (i < 0) {
            return null;
        }
        int start = i + token.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }

    private static long extractJsonLong(String json, String key) {
        if (json == null || key == null) {
            return -1L;
        }
        String token = "\"" + key + "\":";
        int i = json.indexOf(token);
        if (i < 0) {
            return -1L;
        }
        int start = i + token.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            return -1L;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
