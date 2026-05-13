package com.qa.demo.qa.answer;

import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MiniMaxClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;
    private final HttpClient httpClient;

    public MiniMaxClient(ObjectMapper objectMapper, QaAssistantProperties properties) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().build();
    }

    @FunctionalInterface
    public interface StreamListener {
        void onEvent(String type, String content);
    }

    public String askWithEvidence(String question, List<ContextChunk> chunks) {
        return askWithEvidence("", question, chunks);
    }

    /**
     * @param contextBlock 多轮对话上文（可为空）；question 为用户本轮原话。
     */
    public String askWithEvidence(String contextBlock, String question, List<ContextChunk> chunks) {
        String systemPrompt = KnowledgeAssistantPrompts.minimaxEvidenceSystemPrompt(properties.getAssistantName());
        String evidence = chunks.isEmpty()
                ? "无可用证据。"
                : chunks.stream()
                .map(chunk -> String.format(
                        "- 公司ID:%s, 公司名:%s, 字段:%s, 分数:%.2f, 片段:%s",
                        chunk.companyId(),
                        chunk.companyName(),
                        chunk.field(),
                        chunk.score(),
                        chunk.snippet()
                ))
                .collect(Collectors.joining("\n"));
        String prefix = contextBlock == null || contextBlock.isBlank() ? "" : contextBlock.trim() + "\n\n";
        String userContent = prefix + "[Evidence Start]\n" + evidence + "\n[Evidence End]\n\n用户本轮提问: " + question;

        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "name", "MiniMax AI", "content", systemPrompt),
                        Map.of("role", "user", "name", "User", "content", userContent)
                )
        );

        String response = restClient.post()
                .uri(properties.getApiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        return parseNonStreamAssistantContent(response);
    }

    /**
     * 非流式单轮对话（system + user），供 schema 评估等场景复用。
     */
    public String completeChat(String systemPrompt, String userMessage) {
        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "name", "MiniMax AI", "content", systemPrompt),
                        Map.of("role", "user", "name", "User", "content", userMessage)
                )
        );
        String response = restClient.post()
                .uri(properties.getApiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parseNonStreamAssistantContent(response);
    }

    private String parseNonStreamAssistantContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode statusMsgNode = root.path("base_resp").path("status_msg");
            if (statusMsgNode.isTextual() && !statusMsgNode.asText().isBlank()
                    && !"success".equalsIgnoreCase(statusMsgNode.asText())) {
                throw new IllegalStateException("MiniMax API error: " + statusMsgNode.asText());
            }
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isTextual() && !contentNode.asText().isBlank()) {
                return contentNode.asText();
            }
            JsonNode replyNode = root.path("reply");
            if (replyNode.isTextual() && !replyNode.asText().isBlank()) {
                return replyNode.asText();
            }
            JsonNode outputTextNode = root.path("output_text");
            if (outputTextNode.isTextual() && !outputTextNode.asText().isBlank()) {
                return outputTextNode.asText();
            }
            throw new IllegalStateException("Model returned empty content: " + response);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse model response: " + e.getMessage(), e);
        }
    }

    public String askWithEvidenceStream(String question, List<ContextChunk> chunks, StreamListener listener) {
        return askWithEvidenceStream("", question, chunks, listener);
    }

    public String askWithEvidenceStream(String contextBlock, String question, List<ContextChunk> chunks, StreamListener listener) {
        String systemPrompt = KnowledgeAssistantPrompts.minimaxEvidenceSystemPrompt(properties.getAssistantName());
        String evidence = chunks.isEmpty()
                ? "无可用证据。"
                : chunks.stream()
                .map(chunk -> String.format(
                        "- 公司ID:%s, 公司名:%s, 字段:%s, 分数:%.2f, 片段:%s",
                        chunk.companyId(),
                        chunk.companyName(),
                        chunk.field(),
                        chunk.score(),
                        chunk.snippet()
                ))
                .collect(Collectors.joining("\n"));
        String prefix = contextBlock == null || contextBlock.isBlank() ? "" : contextBlock.trim() + "\n\n";
        String userContent = prefix + "[Evidence Start]\n" + evidence + "\n[Evidence End]\n\n用户本轮提问: " + question;

        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "stream", true,
                "stream_options", Map.of("include_usage", true),
                "messages", List.of(
                        Map.of("role", "system", "name", "MiniMax AI", "content", systemPrompt),
                        Map.of("role", "user", "name", "User", "content", userContent)
                )
        );

        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getApiUrl()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MiniMax stream failed, status=" + response.statusCode());
            }
            return parseStreamBody(response.body(), listener);
        } catch (Exception ex) {
            throw new IllegalStateException("MiniMax stream call failed: " + ex.getMessage(), ex);
        }
    }

    private String parseStreamBody(InputStream bodyStream, StreamListener listener) throws Exception {
        StringBuilder answer = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || !trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring(5).trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            JsonNode chunk = objectMapper.readTree(data);
            JsonNode statusMsg = chunk.path("base_resp").path("status_msg");
            if (statusMsg.isTextual() && !statusMsg.asText().isBlank()
                    && !"success".equalsIgnoreCase(statusMsg.asText())) {
                throw new IllegalStateException("MiniMax stream error: " + statusMsg.asText());
            }
            JsonNode delta = chunk.path("choices").path(0).path("delta");
            if (!delta.isMissingNode()) {
                String reasoning = firstNonBlank(
                        delta.path("reasoning_content").asText(""),
                        delta.path("thinking").asText("")
                );
                if (!reasoning.isBlank() && listener != null) {
                    listener.onEvent("thinking", reasoning);
                }
                String content = firstNonBlank(
                        delta.path("content").asText(""),
                        delta.path("text").asText("")
                );
                if (!content.isBlank()) {
                    String normalized = normalizeStreamDelta(answer, content);
                    if (!normalized.isBlank()) {
                        answer.append(normalized);
                        if (listener != null) {
                            listener.onEvent("delta", normalized);
                        }
                    }
                }
                continue;
            }

            JsonNode message = chunk.path("choices").path(0).path("message");
            String content = firstNonBlank(
                    message.path("content").asText(""),
                    chunk.path("reply").asText(""),
                    chunk.path("output_text").asText("")
            );
            if (!content.isBlank()) {
                String normalized = normalizeStreamDelta(answer, content);
                if (!normalized.isBlank()) {
                    answer.append(normalized);
                    if (listener != null) {
                        listener.onEvent("delta", normalized);
                    }
                }
            }
        }
        if (answer.isEmpty()) {
            throw new IllegalStateException("Model returned empty stream content");
        }
        return answer.toString();
    }

    private String firstNonBlank(String... candidates) {
        for (String item : candidates) {
            if (item != null && !item.isBlank()) {
                return item;
            }
        }
        return "";
    }

    private String normalizeStreamDelta(StringBuilder accumulated, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return "";
        }
        String piece = incoming;
        if (accumulated.isEmpty()) {
            return piece;
        }
        String current = accumulated.toString();
        if (current.endsWith(piece)) {
            return "";
        }
        if (piece.startsWith(current)) {
            String suffix = piece.substring(current.length());
            return suffix.isBlank() ? "" : suffix;
        }
        int maxOverlap = Math.min(current.length(), piece.length());
        for (int overlap = maxOverlap; overlap > 0; overlap--) {
            String prefix = piece.substring(0, overlap);
            if (current.endsWith(prefix)) {
                String suffix = piece.substring(overlap);
                return suffix.isBlank() ? "" : suffix;
            }
        }
        return piece;
    }
}
