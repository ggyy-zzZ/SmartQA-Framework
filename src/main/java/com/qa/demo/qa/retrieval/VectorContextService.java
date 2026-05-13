package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class VectorContextService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;

    public VectorContextService(ObjectMapper objectMapper, QaAssistantProperties properties) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<ContextChunk> retrieveTopChunks(String question) {
        if (!properties.isVectorEnabled()) {
            return List.of();
        }
        try {
            List<Double> vector = hashEmbed(question, properties.getVectorEmbeddingDim());
            Map<String, Object> body = Map.of(
                    "vector", vector,
                    "limit", Math.max(1, Math.min(properties.getVectorTopK(), 20)),
                    "with_payload", true,
                    "with_vector", false
            );

            String response = restClient.post()
                    .uri(properties.getQdrantUrl()
                            + "/collections/" + properties.getQdrantCollection() + "/points/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode points = objectMapper.readTree(response).path("result");
            if (!points.isArray() || points.isEmpty()) {
                return List.of();
            }
            List<ContextChunk> chunks = new ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                String companyId = payload.path("company_id").asText("");
                String companyName = payload.path("company_name").asText("");
                String status = payload.path("status").asText("");
                String text = payload.path("text").asText("");
                double score = point.path("score").asDouble(0.0) * 20.0;
                chunks.add(new ContextChunk(
                        companyId,
                        companyName,
                        "向量检索",
                        "状态=" + status + "; 摘要=" + truncate(text, 220),
                        score,
                        "qdrant-vector"
                ));
            }
            return chunks;
        } catch (Exception e) {
            return List.of();
        }
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

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }
}
