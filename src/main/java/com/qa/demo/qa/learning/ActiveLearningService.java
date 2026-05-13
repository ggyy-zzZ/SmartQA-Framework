package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ActiveLearningService {

    private static final String MYSQL_TABLE = "qa_active_knowledge";
    private static final String QDRANT_COLLECTION = "enterprise_active_learning_v1";
    private static final String SCOPE_ENTERPRISE = "enterprise";
    private static final String SCOPE_PERSONAL = "personal";
    private static final Pattern CJK_TOKEN = Pattern.compile("[\\u4e00-\\u9fa5]{2,8}");
    private static final Pattern EN_TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{2,24}");
    /** 从学习文本中抽取「别名→实名」：老布是李晓峰 */
    private static final Pattern ALIAS_IS_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4})\\s*(?:是|即为|就是|叫做|叫)\\s*([\\u4e00-\\u9fa5]{2,8})"
    );

    private final QaAssistantProperties properties;
    private final Driver neo4jDriver;
    private final RestClient restClient;

    /**
     * Cached result of whether {@code qa_active_knowledge} has a {@code scope} column.
     * Legacy tables created before scope support would otherwise make every retrieve query fail.
     */
    private volatile Boolean scopeColumnPresent;

    public ActiveLearningService(QaAssistantProperties properties, Driver neo4jDriver) {
        this.properties = properties;
        this.neo4jDriver = neo4jDriver;
        this.restClient = RestClient.builder().build();
    }

    public LearningResult learn(String rawContent, String sourceType, String sourceName, String triggerType) {
        return learn(rawContent, sourceType, sourceName, triggerType, SCOPE_ENTERPRISE);
    }

    public LearningResult learn(String rawContent, String sourceType, String sourceName, String triggerType, String scope) {
        String content = normalizeContent(rawContent);
        if (content.isBlank()) {
            return LearningResult.failed("学习内容为空，请提供可学习的文本。");
        }
        String knowledgeId = UUID.randomUUID().toString();
        String title = extractTitle(content, sourceName);
        List<String> keywords = extractKeywords(content, 12);
        String normalizedScope = normalizeScope(scope);

        SinkStatus mysql = persistToMysql(knowledgeId, title, content, sourceType, sourceName, triggerType, normalizedScope);
        SinkStatus vector = persistToQdrant(knowledgeId, title, content, sourceType, sourceName, normalizedScope);
        SinkStatus graph = persistToGraph(knowledgeId, title, content, sourceType, sourceName, keywords, normalizedScope);
        boolean success = mysql.ok() || vector.ok() || graph.ok();
        return new LearningResult(success, knowledgeId, title, mysql, vector, graph, success ? "" : "三路持久化都失败");
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK) {
        return retrieveTopChunks(question, topK, "");
    }

    public List<ContextChunk> retrieveTopChunks(String question, int topK, String scope) {
        List<String> keywords = extractQueryKeywords(question);
        int limit = Math.max(1, topK);
        if (keywords.isEmpty()) {
            return List.of();
        }
        String normalizedScope = normalizeScope(scope);
        String schema = properties.getMysqlSchema();
        String qualifiedTable = "`" + schema + "`.`" + MYSQL_TABLE + "`";
        List<ScoredChunk> scored = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword())) {
            boolean hasScope = resolveScopeColumnPresent(connection, schema);
            StringBuilder where = new StringBuilder();
            if (hasScope && !normalizedScope.isBlank()) {
                if (SCOPE_PERSONAL.equals(normalizedScope)) {
                    where.append("scope = ? AND (");
                } else {
                    where.append("(scope = ? OR scope IS NULL OR scope = '') AND (");
                }
            }
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) {
                    where.append(" OR ");
                }
                where.append("(title LIKE ? OR content LIKE ?)");
            }
            if (hasScope && !normalizedScope.isBlank()) {
                where.append(")");
            }
            String scopeSelect = hasScope ? ", scope" : "";
            String sql = String.format(
                    "SELECT knowledge_id, title, content, source_type, source_name%s, created_at FROM %s WHERE %s ORDER BY created_at DESC LIMIT 120",
                    scopeSelect,
                    qualifiedTable,
                    where
            );
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int idx = 1;
                if (hasScope && !normalizedScope.isBlank()) {
                    ps.setString(idx++, normalizedScope);
                }
                for (String keyword : keywords) {
                    String like = "%" + keyword + "%";
                    ps.setString(idx++, like);
                    ps.setString(idx++, like);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String knowledgeId = rs.getString("knowledge_id");
                        String title = rs.getString("title");
                        String content = rs.getString("content");
                        String sourceType = rs.getString("source_type");
                        String rowScope = hasScope
                                ? nullToEmpty(rs.getString("scope"))
                                : SCOPE_ENTERPRISE;
                        double score = computeMatchScore(question, keywords, title, content);
                        if (score <= 0.0) {
                            continue;
                        }
                        ContextChunk chunk = new ContextChunk(
                                knowledgeId == null ? "" : knowledgeId,
                                title == null || title.isBlank() ? "主动学习知识" : title,
                                "scope=" + normalizeScope(rowScope) + ";type=" + (sourceType == null ? "active_learning" : sourceType),
                                buildSnippet(content, keywords),
                                score,
                                "active_learning"
                        );
                        scored.add(new ScoredChunk(chunk, score));
                    }
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        List<ContextChunk> result = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();
        for (ScoredChunk item : scored) {
            ContextChunk chunk = item.chunk();
            String key = chunk.companyId() + "|" + chunk.snippet();
            if (dedupe.contains(key)) {
                continue;
            }
            dedupe.add(key);
            result.add(chunk);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /**
     * 将用户问题改写为更适合 MySQL/图谱 的结构化检索用语：若主动学习里已有「别名→实名」类事实，
     * 且当前问题看起来像人员与组织/任职相关的结构化查询，则把问句中的别名替换为实名（仅用于检索，不改变对用户展示的原句）。
     */
    public String augmentQuestionForStructuredRetrieval(String question, List<ContextChunk> learned) {
        if (question == null || question.isBlank() || learned == null || learned.isEmpty()) {
            return question == null ? "" : question;
        }
        if (!looksLikePersonStructureQuery(question)) {
            return question;
        }
        Map<String, String> aliases = extractAliasPairsFromActiveLearning(learned);
        if (aliases.isEmpty()) {
            return question;
        }
        String q = question.trim();
        List<String> keys = new ArrayList<>(aliases.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String from : keys) {
            String to = aliases.get(from);
            if (to == null || to.isBlank() || from.equals(to)) {
                continue;
            }
            if (q.contains(from) && !q.contains(to)) {
                return q.replace(from, to);
            }
        }
        return question;
    }

    private boolean looksLikePersonStructureQuery(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        return q.contains("担任")
                || q.contains("职位")
                || q.contains("职务")
                || q.contains("负责人")
                || q.contains("经理")
                || q.contains("角色")
                || q.contains("员工")
                || q.contains("人员")
                || q.contains("任职")
                || q.contains("关联");
    }

    private Map<String, String> extractAliasPairsFromActiveLearning(List<ContextChunk> learned) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ContextChunk c : learned) {
            if (c == null || !"active_learning".equals(c.source())) {
                continue;
            }
            String blob = joinNonBlank(c.snippet(), c.companyName());
            if (blob.isBlank()) {
                continue;
            }
            Matcher m = ALIAS_IS_PATTERN.matcher(blob);
            while (m.find()) {
                String from = m.group(1);
                String to = m.group(2);
                if (from.length() >= 2 && to.length() >= 2 && !from.equals(to)) {
                    map.putIfAbsent(from, to);
                }
            }
        }
        return map;
    }

    private static String joinNonBlank(String snippet, String title) {
        StringBuilder sb = new StringBuilder();
        if (snippet != null && !snippet.isBlank()) {
            sb.append(snippet);
        }
        if (title != null && !title.isBlank()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(title);
        }
        return sb.toString();
    }

    private SinkStatus persistToMysql(
            String knowledgeId,
            String title,
            String content,
            String sourceType,
            String sourceName,
            String triggerType,
            String scope
    ) {
        String schema = properties.getMysqlSchema();
        String qualifiedTable = "`" + schema + "`.`" + MYSQL_TABLE + "`";
        String createSql = """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                    knowledge_id VARCHAR(64) NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    content LONGTEXT NOT NULL,
                    source_type VARCHAR(64) NOT NULL,
                    source_name VARCHAR(255) NOT NULL,
                    trigger_type VARCHAR(64) NOT NULL,
                    scope VARCHAR(32) NOT NULL DEFAULT 'enterprise',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_knowledge_id (knowledge_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """.formatted(qualifiedTable);
        String alterSql = "ALTER TABLE " + qualifiedTable + " ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'enterprise'";
        String insertSqlWithScope = """
                INSERT INTO %s (knowledge_id, title, content, source_type, source_name, trigger_type, scope)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.formatted(qualifiedTable);
        String legacyInsertSql = """
                INSERT INTO %s (knowledge_id, title, content, source_type, source_name, trigger_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """.formatted(qualifiedTable);
        try (Connection connection = DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(createSql);
            try {
                statement.execute(alterSql);
            } catch (Exception ignored) {
                // Old MySQL versions may not support IF NOT EXISTS; best effort only.
            }
            try (PreparedStatement ps = connection.prepareStatement(insertSqlWithScope)) {
                ps.setString(1, knowledgeId);
                ps.setString(2, truncate(title, 255));
                ps.setString(3, content);
                ps.setString(4, truncate(sourceType, 64));
                ps.setString(5, truncate(sourceName, 255));
                ps.setString(6, truncate(triggerType, 64));
                ps.setString(7, truncate(normalizeScope(scope), 32));
                int rows = ps.executeUpdate();
                return SinkStatus.ok("mysql", "写入成功，行数=" + rows);
            } catch (Exception primaryEx) {
                try (PreparedStatement ps = connection.prepareStatement(legacyInsertSql)) {
                    ps.setString(1, knowledgeId);
                    ps.setString(2, truncate(title, 255));
                    ps.setString(3, content);
                    ps.setString(4, truncate(sourceType, 64));
                    ps.setString(5, truncate(sourceName, 255));
                    ps.setString(6, truncate(triggerType, 64));
                    int rows = ps.executeUpdate();
                    return SinkStatus.ok("mysql", "写入成功(legacy schema)，行数=" + rows);
                } catch (Exception legacyEx) {
                    return SinkStatus.fail("mysql", legacyEx.getMessage());
                }
            }
        } catch (Exception ex) {
            return SinkStatus.fail("mysql", ex.getMessage());
        }
    }

    private SinkStatus persistToQdrant(
            String knowledgeId,
            String title,
            String content,
            String sourceType,
            String sourceName,
            String scope
    ) {
        try {
            Map<String, Object> createBody = Map.of(
                    "vectors", Map.of(
                            "size", Math.max(64, properties.getVectorEmbeddingDim()),
                            "distance", "Cosine"
                    )
            );
            try {
                restClient.put()
                        .uri(properties.getQdrantUrl() + "/collections/" + QDRANT_COLLECTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(createBody)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception ignored) {
                // Collection already exists or server returns conflict; continue upsert.
            }

            List<Double> vector = hashEmbed(title + "\n" + content, Math.max(64, properties.getVectorEmbeddingDim()));
            long pointId = toPositiveLong(knowledgeId);
            Map<String, Object> payload = Map.of(
                    "knowledge_id", knowledgeId,
                    "title", title,
                    "text", truncate(content, 4000),
                    "source_type", sourceType,
                    "source_name", sourceName,
                    "scope", normalizeScope(scope),
                    "created_at", OffsetDateTime.now().toString()
            );
            Map<String, Object> upsertBody = Map.of(
                    "points", List.of(Map.of(
                            "id", pointId,
                            "vector", vector,
                            "payload", payload
                    ))
            );
            restClient.put()
                    .uri(properties.getQdrantUrl() + "/collections/" + QDRANT_COLLECTION + "/points?wait=true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(upsertBody)
                    .retrieve()
                    .toBodilessEntity();
            return SinkStatus.ok("vector", "写入Qdrant成功");
        } catch (Exception ex) {
            return SinkStatus.fail("vector", ex.getMessage());
        }
    }

    private SinkStatus persistToGraph(
            String knowledgeId,
            String title,
            String content,
            String sourceType,
            String sourceName,
            List<String> keywords,
            String scope
    ) {
        try (Session session = neo4jDriver.session()) {
            session.run(
                    """
                    MERGE (d:LearnedKnowledge {knowledgeId: $knowledgeId})
                    SET d.title = $title,
                        d.content = $content,
                        d.sourceType = $sourceType,
                        d.sourceName = $sourceName,
                        d.scope = $scope,
                        d.updatedAt = $updatedAt,
                        d.createdAt = coalesce(d.createdAt, $updatedAt)
                    """,
                    org.neo4j.driver.Values.parameters(
                            "knowledgeId", knowledgeId,
                            "title", title,
                            "content", truncate(content, 6000),
                            "sourceType", sourceType,
                            "sourceName", sourceName,
                            "scope", normalizeScope(scope),
                            "updatedAt", OffsetDateTime.now().toString()
                    )
            );
            if (!keywords.isEmpty()) {
                session.run(
                        """
                        MATCH (d:LearnedKnowledge {knowledgeId: $knowledgeId})
                        UNWIND $keywords AS kw
                        MERGE (k:LearnedKeyword {name: kw})
                        MERGE (d)-[:HAS_KEYWORD]->(k)
                        """,
                        org.neo4j.driver.Values.parameters(
                                "knowledgeId", knowledgeId,
                                "keywords", keywords
                        )
                );
            }
            return SinkStatus.ok("graph", "写入Neo4j成功");
        } catch (Exception ex) {
            return SinkStatus.fail("graph", ex.getMessage());
        }
    }

    private String normalizeContent(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").trim();
        return text.length() > 100_000 ? text.substring(0, 100_000) : text;
    }

    private String extractTitle(String markdown, String sourceName) {
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("#")) {
                String title = l.replaceFirst("^#+\\s*", "").trim();
                if (!title.isBlank()) {
                    return truncate(title, 255);
                }
            }
        }
        if (sourceName != null && !sourceName.isBlank()) {
            return truncate(sourceName, 255);
        }
        return "主动学习知识片段";
    }

    private List<String> extractKeywords(String text, int limit) {
        Set<String> set = new LinkedHashSet<>();
        Matcher zh = CJK_TOKEN.matcher(text);
        while (zh.find() && set.size() < limit) {
            set.add(zh.group());
        }
        Matcher en = EN_TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (en.find() && set.size() < limit) {
            set.add(en.group());
        }
        return new ArrayList<>(set);
    }

    private List<String> extractQueryKeywords(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        String text = question.trim();
        Set<String> out = new LinkedHashSet<>();
        if (text.contains("总部")) {
            out.add("总部");
        }
        if (text.contains("总公司")) {
            out.add("总公司");
        }
        Matcher zh = CJK_TOKEN.matcher(text);
        while (zh.find() && out.size() < 8) {
            String token = zh.group();
            if (!isQuestionNoise(token)) {
                out.add(token);
            }
            if (token.length() >= 4) {
                out.add(token.substring(0, 2));
                out.add(token.substring(token.length() - 2));
            }
        }
        Matcher en = EN_TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (en.find() && out.size() < 10) {
            out.add(en.group());
        }
        addCjkBigrams(text, out, 12);
        return out.stream().filter(x -> !x.isBlank()).limit(14).toList();
    }

    private void addCjkBigrams(String text, Set<String> out, int maxAdd) {
        if (text == null || text.isBlank()) {
            return;
        }
        int added = 0;
        for (int i = 0; i < text.length() - 1 && added < maxAdd; i++) {
            char a = text.charAt(i);
            char b = text.charAt(i + 1);
            if (isCjk(a) && isCjk(b)) {
                String bi = "" + a + b;
                if (!isQuestionNoise(bi) && out.add(bi)) {
                    added++;
                }
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private boolean resolveScopeColumnPresent(Connection connection, String schema) {
        Boolean cached = scopeColumnPresent;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = scopeColumnPresent;
            if (cached != null) {
                return cached;
            }
            boolean present = false;
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = ?
                      AND column_name = 'scope'
                    LIMIT 1
                    """)) {
                ps.setString(1, schema);
                ps.setString(2, MYSQL_TABLE);
                try (ResultSet rs = ps.executeQuery()) {
                    present = rs.next();
                }
            } catch (Exception ignored) {
                present = false;
            }
            scopeColumnPresent = present;
            return present;
        }
    }

    private boolean isQuestionNoise(String token) {
        return token.equals("是什么")
                || token.equals("是啥")
                || token.equals("什么")
                || token.equals("请问")
                || token.equals("一下")
                || token.equals("这个");
    }

    private double computeMatchScore(String question, List<String> keywords, String title, String content) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        String c = content == null ? "" : content.toLowerCase(Locale.ROOT);
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        double score = 0.0;
        if (!q.isBlank()) {
            if (!t.isBlank() && t.contains(q)) {
                score += 2.5;
            }
            if (!c.isBlank() && c.contains(q)) {
                score += 2.0;
            }
        }
        for (String keyword : keywords) {
            String kw = keyword.toLowerCase(Locale.ROOT);
            if (kw.length() < 2) {
                continue;
            }
            if (t.contains(kw)) {
                score += 2.0;
            }
            if (c.contains(kw)) {
                score += 1.0;
            }
        }
        return score;
    }

    private String buildSnippet(String content, List<String> keywords) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").trim();
        int start = 0;
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            int idx = lower.indexOf(keyword.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                start = Math.max(0, idx - 28);
                break;
            }
        }
        int end = Math.min(normalized.length(), start + 240);
        return normalized.substring(start, end);
    }

    private List<Double> hashEmbed(String text, int dim) throws Exception {
        double[] vec = new double[dim];
        for (String token : tokenize(text)) {
            byte[] digest = sha256(token);
            int idx = toInt(digest[0], digest[1], digest[2], digest[3]) % dim;
            if (idx < 0) {
                idx += dim;
            }
            double sign = (digest[4] & 0x01) == 0 ? 1.0 : -1.0;
            double weight = 1.0 + ((digest[5] & 0xff) / 255.0);
            vec[idx] += sign * weight;
        }
        double norm = 0.0;
        for (double v : vec) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        List<Double> out = new ArrayList<>(dim);
        for (double v : vec) {
            out.add(norm > 0 ? v / norm : 0.0);
        }
        return out;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (char ch : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(ch) || ",.;:|()[]{}<>!?\"'，。；：、（）".indexOf(ch) >= 0) {
                flushBuffer(tokens, buffer);
                continue;
            }
            if (isCjk(ch)) {
                flushBuffer(tokens, buffer);
                tokens.add(String.valueOf(ch));
            } else {
                buffer.append(ch);
            }
        }
        flushBuffer(tokens, buffer);
        return tokens;
    }

    private void flushBuffer(List<String> tokens, StringBuilder buffer) {
        if (buffer.length() > 0) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    private boolean isCjk(char ch) {
        return ch >= '\u4e00' && ch <= '\u9fff';
    }

    private byte[] sha256(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private int toInt(byte b0, byte b1, byte b2, byte b3) {
        return (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
    }

    private long toPositiveLong(String value) {
        long h = 1125899906842597L;
        for (char c : value.toCharArray()) {
            h = 31 * h + c;
        }
        return h < 0 ? -h : h;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "";
        }
        String raw = scope.trim().toLowerCase(Locale.ROOT);
        if (raw.contains("个人") || raw.equals("personal") || raw.equals("me")) {
            return SCOPE_PERSONAL;
        }
        if (raw.contains("企业") || raw.equals("enterprise") || raw.equals("company")) {
            return SCOPE_ENTERPRISE;
        }
        return raw;
    }

    public record LearningResult(
            boolean success,
            String knowledgeId,
            String title,
            SinkStatus mysql,
            SinkStatus vector,
            SinkStatus graph,
            String message
    ) {
        public static LearningResult failed(String message) {
            SinkStatus failed = SinkStatus.fail("none", message);
            return new LearningResult(false, "", "", failed, failed, failed, message);
        }
    }

    public record SinkStatus(String sink, boolean ok, String detail) {
        public static SinkStatus ok(String sink, String detail) {
            return new SinkStatus(sink, true, detail);
        }

        public static SinkStatus fail(String sink, String detail) {
            return new SinkStatus(sink, false, detail == null ? "" : detail);
        }
    }

    private record ScoredChunk(ContextChunk chunk, double score) {
    }
}
