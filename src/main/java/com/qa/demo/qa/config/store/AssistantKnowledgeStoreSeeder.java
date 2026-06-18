package com.qa.demo.qa.config.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1：将 classpath 配置与枚举种子写入 assistant 库（仅缺失时写入，不覆盖已发布版本）。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AssistantKnowledgeStoreSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AssistantKnowledgeStoreSeeder.class);

    private final QaAssistantProperties properties;
    private final ConfigBundleRepository configBundleRepository;
    private final EnumCatalogRepository enumCatalogRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EntitySnapshotRepository entitySnapshotRepository;
    private final ObjectMapper objectMapper;

    public AssistantKnowledgeStoreSeeder(
            QaAssistantProperties properties,
            ConfigBundleRepository configBundleRepository,
            EnumCatalogRepository enumCatalogRepository,
            DocumentChunkRepository documentChunkRepository,
            EntitySnapshotRepository entitySnapshotRepository,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.configBundleRepository = configBundleRepository;
        this.enumCatalogRepository = enumCatalogRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.entitySnapshotRepository = entitySnapshotRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isMysqlEnabled() || !properties.isConfigSeedOnStartup()) {
            return;
        }
        String scope = properties.getConfigScope();
        try {
            seedConfigBundles(scope);
            seedEnums(scope);
            try {
                seedDocumentCorpus(scope);
            } catch (Exception e) {
                log.warn("[KnowledgeStoreSeeder] document corpus seed skipped: {}", e.getMessage());
            }
            try {
                seedEntitySnapshots(scope);
            } catch (Exception e) {
                log.warn("[KnowledgeStoreSeeder] entity snapshot seed skipped: {}", e.getMessage());
            }
            log.info("[KnowledgeStoreSeeder] seed completed for scope={}", scope);
        } catch (Exception e) {
            log.warn("[KnowledgeStoreSeeder] seed skipped: {}", e.getMessage());
        }
    }

    private void seedConfigBundles(String scope) throws Exception {
        for (Map.Entry<String, String> entry : configKeys().entrySet()) {
            String key = entry.getKey();
            String classpath = entry.getValue();
            if (configBundleRepository.hasActiveBundle(scope, key)) {
                continue;
            }
            try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                configBundleRepository.upsertActive(scope, key, json, sha256(json));
                log.info("[KnowledgeStoreSeeder] seeded config {}", key);
            }
        }
    }

    private static Map<String, String> configKeys() {
        return Map.ofEntries(
                Map.entry("business-rules", "qa/business-rules.json"),
                Map.entry("retrieval-catalog", "qa/retrieval-catalog.json"),
                Map.entry("cdc-graph-sync", "qa/cdc-graph-sync.json"),
                Map.entry("evidence-schemas", "qa/evidence-schemas.json"),
                Map.entry("answer-output-contracts", "qa/answer-output-contracts.json"),
                Map.entry("enterprise-lexicon", "qa/enterprise-lexicon.json"),
                Map.entry("semantic-schema", "qa/semantic-schema.json"),
                Map.entry("enterprise-canonical-facts", "qa/enterprise-canonical-facts.json")
        );
    }

    private void seedEnums(String scope) throws Exception {
        try (InputStream in = new ClassPathResource("qa/enterprise-enums.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String dictCode = field.getKey();
                if (!field.getValue().isObject() || enumCatalogRepository.hasDict(scope, dictCode)) {
                    continue;
                }
                Map<String, String> entries = new LinkedHashMap<>();
                field.getValue().fields().forEachRemaining(e ->
                        entries.put(e.getKey(), e.getValue().asText(""))
                );
                enumCatalogRepository.replaceDict(scope, dictCode, dictCode, entries);
                log.info("[KnowledgeStoreSeeder] seeded enum dict {}", dictCode);
            }
        }
    }

    private void seedDocumentCorpus(String scope) {
        String corpus = properties.getDocumentCorpusCode();
        if (documentChunkRepository.hasChunks(scope, corpus)) {
            return;
        }
        Path docs = Path.of(properties.getDocsDir());
        if (Files.exists(docs)) {
            documentChunkRepository.importFromCompiledFile(docs, scope, corpus, corpus);
        }
    }

    private void seedEntitySnapshots(String scope) {
        if (entitySnapshotRepository.hasAny(scope)) {
            return;
        }
        Path jsonl = Path.of("data/knowledge/enterprise_mysql_clean.jsonl");
        if (java.nio.file.Files.exists(jsonl)) {
            entitySnapshotRepository.importFromJsonl(jsonl, scope, properties.getKnowledgeSyncDomain(), "company");
        }
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
