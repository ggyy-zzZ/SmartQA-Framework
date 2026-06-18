package com.qa.demo.qa.learning;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.embedding.TextEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P5：用户上传文档切块 → embedding → Qdrant，与结构化知识共用 collection。
 */
@Service
public class DocumentVectorIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorIngestService.class);
    private static final String ENTITY_TYPE = "user_document";

    private final QaAssistantProperties properties;
    private final TextEmbeddingService textEmbeddingService;
    private final RestClient restClient;
    private volatile boolean collectionEnsured;

    public DocumentVectorIngestService(
            QaAssistantProperties properties,
            TextEmbeddingService textEmbeddingService
    ) {
        this.properties = properties;
        this.textEmbeddingService = textEmbeddingService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getQdrantTimeoutMs());
        factory.setReadTimeout(properties.getQdrantTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getQdrantUrl())
                .requestFactory(factory)
                .build();
    }

    public int ingestChunks(String scope, String corpusCode, String docTitle, List<ChunkPayload> chunks) {
        if (!properties.isDocumentVectorIngestEnabled() || !properties.isVectorEnabled()) {
            return 0;
        }
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        try {
            ensureCollection();
            List<String> texts = chunks.stream().map(ChunkPayload::content).toList();
            List<List<Double>> vectors = textEmbeddingService.embedBatch(texts);
            List<Map<String, Object>> points = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                ChunkPayload chunk = chunks.get(i);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("domain", "user_upload");
                payload.put("entity_type_key", ENTITY_TYPE);
                payload.put("entity_id", chunk.chunkKey());
                payload.put("corpus_code", corpusCode);
                payload.put("scope", scope);
                payload.put("title", docTitle == null ? "" : docTitle);
                payload.put("chunk_key", chunk.chunkKey());
                payload.put("text", chunk.content());

                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", stablePointId(scope, corpusCode, chunk.chunkKey()));
                point.put("vector", vectors.get(i));
                point.put("payload", payload);
                points.add(point);
            }
            Map<String, Object> body = Map.of("points", points);
            restClient.put()
                    .uri("/collections/{collection}/points?wait=true", properties.getQdrantCollection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[DocumentVector] upserted {} points corpus={}", points.size(), corpusCode);
            return points.size();
        } catch (Exception e) {
            log.warn("[DocumentVector] ingest failed (non-fatal): {}", e.getMessage());
            return 0;
        }
    }

    public int deleteCorpusPoints(String scope, String corpusCode, List<String> chunkKeys) {
        if (!properties.isDocumentVectorIngestEnabled() || chunkKeys == null || chunkKeys.isEmpty()) {
            return 0;
        }
        try {
            ensureCollection();
            List<String> pointIds = chunkKeys.stream()
                    .map(key -> stablePointId(scope, corpusCode, key))
                    .toList();
            restClient.post()
                    .uri("/collections/{collection}/points/delete?wait=true", properties.getQdrantCollection())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("points", pointIds))
                    .retrieve()
                    .toBodilessEntity();
            return pointIds.size();
        } catch (Exception e) {
            log.warn("[DocumentVector] delete failed: {}", e.getMessage());
            return 0;
        }
    }

    private void ensureCollection() {
        if (collectionEnsured) {
            return;
        }
        synchronized (this) {
            if (collectionEnsured) {
                return;
            }
            createCollectionIfMissing(properties.getQdrantCollection());
            collectionEnsured = true;
        }
    }

    private void createCollectionIfMissing(String collection) {
        if (collectionExists(collection)) {
            return;
        }
        int dim = Math.max(64, properties.getVectorEmbeddingDim());
        Map<String, Object> createBody = Map.of(
                "vectors", Map.of("size", dim, "distance", "Cosine")
        );
        restClient.put()
                .uri("/collections/{collection}", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createBody)
                .retrieve()
                .toBodilessEntity();
    }

    private boolean collectionExists(String collection) {
        try {
            restClient.get()
                    .uri("/collections/{collection}", collection)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    static String stablePointId(String scope, String corpusCode, String chunkKey) {
        String raw = nullToEmpty(scope) + "|" + nullToEmpty(corpusCode) + "|" + nullToEmpty(chunkKey);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xff);
            }
            return new UUID(msb, lsb).toString();
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ChunkPayload(String chunkKey, String content) {
    }
}
