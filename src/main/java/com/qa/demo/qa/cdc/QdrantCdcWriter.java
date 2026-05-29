package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.cdc.graph.CdcVectorRoleDocumentEnricher;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * CDC 事件写入 Qdrant 向量库。
 *
 * 使用 HTTP REST API（复用项目的 VectorContextService 方式）。
 * - stable_point_id() 生成确定性 ID
 * - build_document() 构建可向量化的文本
 */
@Component
public class QdrantCdcWriter {

    private static final Logger log = LoggerFactory.getLogger(QdrantCdcWriter.class);

    private static final String DEFAULT_DOMAIN = "org_master";
    private final QaAssistantProperties props;
    private final CdcSyncAuditLogger auditLogger;
    private final CdcVectorRoleDocumentEnricher roleDocumentEnricher;
    private final RestClient restClient;
    private volatile boolean collectionEnsured;

    public QdrantCdcWriter(
            QaAssistantProperties props,
            CdcSyncAuditLogger auditLogger,
            CdcVectorRoleDocumentEnricher roleDocumentEnricher
    ) {
        this.props = props;
        this.auditLogger = auditLogger;
        this.roleDocumentEnricher = roleDocumentEnricher;
        this.restClient = RestClient.builder()
                .baseUrl(props.getQdrantUrl())
                .build();
    }

    private void ensureCollection() {
        if (collectionEnsured) {
            return;
        }
        synchronized (this) {
            if (collectionEnsured) {
                return;
            }
            createCollectionIfMissing(props.getQdrantCollection());
            collectionEnsured = true;
        }
    }

    private void createCollectionIfMissing(String collection) {
        if (collectionExists(collection)) {
            return;
        }
        int dim = Math.max(64, props.getVectorEmbeddingDim());
        Map<String, Object> createBody = Map.of(
                "vectors", Map.of("size", dim, "distance", "Cosine")
        );
        restClient.put()
                .uri("/collections/{collection}", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(createBody)
                .retrieve()
                .toBodilessEntity();
        log.info("[Qdrant CDC] Created collection {} (dim={})", collection, dim);
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

    private void invalidateCollectionCache() {
        collectionEnsured = false;
    }

    private void upsertWithCollectionRetry(String collection, Map<String, Object> requestBody) {
        try {
            putPoints(collection, requestBody);
        } catch (HttpClientErrorException.NotFound e) {
            invalidateCollectionCache();
            ensureCollection();
            putPoints(collection, requestBody);
        }
    }

    private void putPoints(String collection, Map<String, Object> requestBody) {
        restClient.put()
                .uri("/collections/{collection}/points?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);
    }

    /**
     * 将 CDC 事件写入 Qdrant。
     *
     * @param table  表名
     * @param op     操作类型（c=create, u=update, d=delete, r=snapshot）
     * @param after  变更后的数据
     * @param before 变更前的数据（删除时用于取主键）
     */
    public void write(String table, String op, JsonNode after, JsonNode before) {
        JsonNode row = CdcEntityIdResolver.dataRow(after, before, op);
        if (!CdcEntityIdResolver.hasRowData(row)) {
            log.warn("[Qdrant CDC] No row data for op={}, table={}", op, table);
            return;
        }

        String entityId = extractEntityId(table, row);
        if (entityId == null || entityId.isBlank()) {
            log.warn("[Qdrant CDC] Cannot extract entity id, table={}", table);
            return;
        }

        try {
            if ("d".equals(op)) {
                deletePoint(table, entityId);
                auditLogger.storeWriteSuccess("qdrant", table, op, entityId, "delete point ok");
            } else {
                String pointId = upsertPoint(table, row, entityId);
                auditLogger.storeWriteSuccess("qdrant", table, op, entityId, "upsert pointId=" + pointId);
            }
        } catch (Exception e) {
            auditLogger.storeWriteFailed("qdrant", table, op, entityId, e.getMessage());
            log.error("[Qdrant CDC] Failed to write table={}, entityId={}", table, entityId, e);
            throw new RuntimeException("Qdrant CDC write failed", e);
        }
    }

    private String buildDocumentText(String table, JsonNode row) {
        String docText = CdcTdcompFields.buildDocument(table, row);
        if ("company".equalsIgnoreCase(table)) {
            StringBuilder sb = new StringBuilder(docText);
            roleDocumentEnricher.appendCompanyRoleSections(sb, row);
            return sb.toString();
        }
        return docText;
    }

    private String upsertPoint(String table, JsonNode row, String entityId) {
        ensureCollection();
        String collection = props.getQdrantCollection();
        String entityType = CdcTdcompFields.qdrantEntityType(table);

        String docText = buildDocumentText(table, row);
        List<Float> vector = hashEmbedText(docText, props.getVectorEmbeddingDim());
        String pointId = stablePointId(DEFAULT_DOMAIN, entityType, entityId);
        Map<String, Object> payload = buildPayload(table, row, entityId, entityType, docText);

        // 构建请求
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", pointId);
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("points", Collections.singletonList(point));

        upsertWithCollectionRetry(collection, requestBody);

        log.debug("[Qdrant CDC] Upserted point id={}, table={}", pointId, table);
        return pointId;
    }

    private void deletePoint(String table, String entityId) {
        ensureCollection();
        String collection = props.getQdrantCollection();
        String entityType = CdcTdcompFields.qdrantEntityType(table);
        String pointId = stablePointId(DEFAULT_DOMAIN, entityType, entityId);

        Map<String, Object> requestBody = Map.of(
                "points", Collections.singletonList(pointId)
        );

        restClient.post()
                .uri("/collections/{collection}/points/delete?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        log.info("[Qdrant CDC] Deleted point id={}", pointId);
    }

    private Map<String, Object> buildPayload(
            String table, JsonNode row, String entityId, String entityType, String docText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("domain", DEFAULT_DOMAIN);
        payload.put("entity_type_key", entityType);
        payload.put("entity_id", entityId);
        payload.put("table", table);
        payload.put("text", docText);
        if ("company".equals(table)) {
            payload.put("company_id", entityId);
            payload.put("company_name", CdcTdcompFields.firstText(row, "company_name", "name"));
            payload.put("status", CdcTdcompFields.operatingStatus(row));
            payload.put("entity_type", CdcTdcompFields.entityType(row));
            payload.put("entity_category", CdcTdcompFields.entityCategory(row));
            payload.put("registered_area", CdcTdcompFields.registeredArea(row));
        } else if ("employee".equals(table)) {
            payload.put("person_id", entityId);
            payload.put("name", CdcTdcompFields.employeeName(row));
            payload.put("company_id", CdcTdcompFields.employeeCompanyId(row));
        }
        return payload;
    }

    // ==================== Hash Embedding ====================

    private List<Float> hashEmbedText(String text, int dim) {
        float[] vec = new float[dim];
        List<String> tokens = tokenize(text);

        for (String token : tokens) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                int idx = Math.abs(java.nio.ByteBuffer.wrap(hash, 0, 4).getInt()) % dim;
                float sign = (hash[4] & 1) == 0 ? 1.0f : -1.0f;
                float weight = 1.0f + (hash[5] & 0xFF) / 255.0f;
                vec[idx] += sign * weight;
            } catch (Exception e) {
                // 忽略
            }
        }

        // 归一化
        float norm = 0f;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        }

        // float[] 转 List<Float>
        List<Float> result = new ArrayList<>(dim);
        for (float v : vec) result.add(v);
        return result;
    }

    private List<String> tokenize(String text) {
        List<String> pieces = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (char ch : text.toCharArray()) {
            if (Character.isWhitespace(ch) || ",.;:|()[]{}<>!?\"'，。；：、（）".indexOf(ch) >= 0) {
                if (buffer.length() > 0) {
                    pieces.add(buffer.toString());
                    buffer.setLength(0);
                }
                continue;
            }
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                if (buffer.length() > 0) {
                    pieces.add(buffer.toString());
                    buffer.setLength(0);
                }
                pieces.add(String.valueOf(ch));
            } else {
                buffer.append(Character.toLowerCase(ch));
            }
        }
        if (buffer.length() > 0) {
            pieces.add(buffer.toString());
        }
        return pieces;
    }

    // ==================== Stable Point ID ====================

    private String stablePointId(String domain, String entityType, String entityId) {
        String key = domain + ":" + entityType + ":" + entityId;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 取前16字节生成 UUID
            return new UUID(
                    java.nio.ByteBuffer.wrap(hash, 0, 8).getLong(),
                    java.nio.ByteBuffer.wrap(hash, 8, 8).getLong()
            ).toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    // ==================== Helpers ====================

    private String extractEntityId(String table, JsonNode row) {
        return CdcEntityIdResolver.resolveEntityId(table, row);
    }

    private String getText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() ? null : n.asText(null);
    }

}