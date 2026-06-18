package com.qa.demo.qa.config.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置 JSON 统一加载：优先 MySQL {@code qa_config_bundle}，失败或未启用时回退 classpath。
 */
@Service
public class AssistantConfigJsonLoader {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfigJsonLoader.class);

    private static final Map<String, String> CLASSPATH_BY_KEY = Map.ofEntries(
            Map.entry("business-rules", "qa/business-rules.json"),
            Map.entry("retrieval-catalog", "qa/retrieval-catalog.json"),
            Map.entry("cdc-graph-sync", "qa/cdc-graph-sync.json"),
            Map.entry("evidence-schemas", "qa/evidence-schemas.json"),
            Map.entry("answer-output-contracts", "qa/answer-output-contracts.json"),
            Map.entry("enterprise-lexicon", "qa/enterprise-lexicon.json"),
            Map.entry("semantic-schema", "qa/semantic-schema.json"),
            Map.entry("enterprise-canonical-facts", "qa/enterprise-canonical-facts.json")
    );

    private final QaAssistantProperties properties;
    private final ConfigBundleRepository configBundleRepository;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, JsonNode> cache = new ConcurrentHashMap<>();

    public AssistantConfigJsonLoader(
            QaAssistantProperties properties,
            ConfigBundleRepository configBundleRepository,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.configBundleRepository = configBundleRepository;
        this.objectMapper = objectMapper;
    }

    public void clearCache() {
        cache.clear();
    }

    public JsonNode readTree(String configKey) throws IOException {
        return readTree(properties.getConfigScope(), configKey);
    }

    public JsonNode readTree(String scope, String configKey) throws IOException {
        String cacheKey = scopeOrDefault(scope) + "|" + configKey;
        JsonNode cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        JsonNode node = loadFresh(scope, configKey);
        cache.put(cacheKey, node);
        return node;
    }

    public InputStream openStream(String configKey) throws IOException {
        String json = loadFresh(properties.getConfigScope(), configKey).toString();
        return new java.io.ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode loadFresh(String scope, String configKey) throws IOException {
        String mode = properties.getConfigSource() == null ? "mysql_fallback" : properties.getConfigSource().trim();
        if (!"classpath".equalsIgnoreCase(mode)) {
            try {
                var fromDb = configBundleRepository.loadActiveContent(scope, configKey);
                if (fromDb.isPresent() && !fromDb.get().isBlank()) {
                    return objectMapper.readTree(fromDb.get());
                }
            } catch (Exception e) {
                log.warn("[ConfigLoader] MySQL load failed for {}: {}", configKey, e.getMessage());
                if ("mysql".equalsIgnoreCase(mode)) {
                    throw new IOException("Config not in MySQL: " + configKey, e);
                }
            }
        }
        return loadClasspath(configKey);
    }

    private JsonNode loadClasspath(String configKey) throws IOException {
        String path = CLASSPATH_BY_KEY.get(configKey);
        if (path == null) {
            throw new IOException("Unknown config key: " + configKey);
        }
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(in);
        }
    }

    private String scopeOrDefault(String scope) {
        if (scope == null || scope.isBlank()) {
            return properties.getConfigScope();
        }
        return scope.trim();
    }
}
