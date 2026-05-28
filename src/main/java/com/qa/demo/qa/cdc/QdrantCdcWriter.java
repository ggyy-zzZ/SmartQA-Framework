package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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
    private static final String DEFAULT_ENTITY_TYPE = "Company";

    private final QaAssistantProperties props;
    private final RestClient restClient;

    public QdrantCdcWriter(QaAssistantProperties props) {
        this.props = props;
        this.restClient = RestClient.builder()
                .baseUrl(props.getQdrantUrl())
                .build();
    }

    /**
     * 将 CDC 事件写入 Qdrant。
     *
     * @param table  表名
     * @param op     操作类型（c=create, u=update, d=delete, r=snapshot）
     * @param after  变更后的数据
     */
    public void write(String table, String op, JsonNode after) {
        if (after == null) {
            log.warn("[Qdrant CDC] No after data for op={}, table={}", op, table);
            return;
        }

        String entityId = extractEntityId(table, after);
        if (entityId == null || entityId.isBlank()) {
            log.warn("[Qdrant CDC] Cannot extract entity id, table={}", table);
            return;
        }

        try {
            if ("d".equals(op)) {
                deletePoint(entityId);
            } else {
                upsertPoint(table, after, entityId);
            }
        } catch (Exception e) {
            log.error("[Qdrant CDC] Failed to write table={}, entityId={}", table, entityId, e);
            throw new RuntimeException("Qdrant CDC write failed", e);
        }
    }

    private void upsertPoint(String table, JsonNode row, String entityId) {
        String collection = props.getQdrantCollection();

        // 构建文档文本
        String docText = buildDocument(row);

        // 生成向量（使用 hash 向量，复用 Python 的 hash_embed_text 逻辑）
        List<Float> vector = hashEmbedText(docText, props.getVectorEmbeddingDim());

        // 生成稳定的 point ID
        String pointId = stablePointId(DEFAULT_DOMAIN, DEFAULT_ENTITY_TYPE, entityId);

        // 构建 payload
        Map<String, Object> payload = buildPayload(row, docText);

        // 构建请求
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", pointId);
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("points", Collections.singletonList(point));

        // 发送到 Qdrant
        restClient.post()
                .uri("/collections/{collection}/points?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        log.debug("[Qdrant CDC] Upserted point id={}, table={}", pointId, table);
    }

    private void deletePoint(String entityId) {
        String collection = props.getQdrantCollection();
        String pointId = stablePointId(DEFAULT_DOMAIN, DEFAULT_ENTITY_TYPE, entityId);

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

    // ==================== Document Building ====================

    private String buildDocument(JsonNode row) {
        StringBuilder sb = new StringBuilder();

        sb.append("公司ID: ").append(nullOrEmpty(row, "company_id")).append("\n");
        sb.append("公司名: ").append(nullOrEmpty(row, "company_name")).append("\n");
        sb.append("简称: ").append(nullOrEmpty(row, "company_short_name")).append("\n");
        sb.append("统一社会信用代码: ").append(nullOrEmpty(row, "credit_code")).append("\n");
        sb.append("经营状态: ").append(nullOrEmpty(row, "status")).append("\n");
        sb.append("主体类型: ").append(nullOrEmpty(row, "entity_type")).append("\n");
        sb.append("主体分类: ").append(nullOrEmpty(row, "entity_category")).append("\n");
        sb.append("成立日期: ").append(nullOrEmpty(row, "established_date")).append("\n");
        sb.append("注册地区: ").append(nullOrEmpty(row, "registered_area")).append("\n");
        sb.append("母公司: ").append(nullOrEmpty(row, "parent_company")).append("\n");
        sb.append("注册地址: ").append(nullOrEmpty(row, "registered_address")).append("\n");
        sb.append("办公地址: ").append(nullOrEmpty(row, "office_address")).append("\n");
        sb.append("经营范围: ").append(nullOrEmpty(row, "business_scope")).append("\n");

        return sb.toString();
    }

    private Map<String, Object> buildPayload(JsonNode row, String docText) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("domain", DEFAULT_DOMAIN);
        payload.put("entity_type_key", DEFAULT_ENTITY_TYPE);
        payload.put("entity_id", nullOrEmpty(row, "company_id"));
        payload.put("company_id", getText(row, "company_id"));
        payload.put("company_name", getText(row, "company_name"));
        payload.put("status", getText(row, "status"));
        payload.put("entity_type", getText(row, "entity_type"));
        payload.put("entity_category", getText(row, "entity_category"));
        payload.put("registered_area", getText(row, "registered_area"));
        payload.put("text", docText);
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
        return switch (table) {
            case "company" -> getText(row, "company_id");
            case "employee" -> getText(row, "employee_id");
            case "branch" -> getText(row, "branch_id");
            case "partner" -> getText(row, "partner_id");
            default -> null;
        };
    }

    private String getText(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() ? null : n.asText(null);
    }

    private String nullOrEmpty(JsonNode node, String field) {
        String v = getText(node, field);
        return v != null ? v : "";
    }
}