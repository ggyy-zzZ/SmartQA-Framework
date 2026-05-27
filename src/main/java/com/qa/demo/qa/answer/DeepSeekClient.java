package com.qa.demo.qa.answer;

import com.qa.demo.knowledge.AnswerOutputContractRegistry;
import com.qa.demo.knowledge.EvidencePromptFormatter;
import com.qa.demo.knowledge.KnowledgeAssistantPrompts;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
@Component
public class DeepSeekClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final QaAssistantProperties properties;
    private final AnswerOutputContractRegistry outputContracts;
    private final HttpClient httpClient;

    public DeepSeekClient(
            ObjectMapper objectMapper,
            QaAssistantProperties properties,
            AnswerOutputContractRegistry outputContracts
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getDeepseekTimeoutMs());
        factory.setReadTimeout(properties.getDeepseekTimeoutMs());
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.outputContracts = outputContracts;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getDeepseekTimeoutMs()))
                .build();
    }

    @FunctionalInterface
    public interface StreamListener {
        void onEvent(String type, String content);
    }

    public String askWithEvidence(String question, List<ContextChunk> chunks) {
        return askWithEvidence("", question, chunks, null);
    }

    public String askWithEvidence(String contextBlock, String question, List<ContextChunk> chunks) {
        return askWithEvidence(contextBlock, question, chunks, null);
    }

    public String askWithEvidence(
            String contextBlock,
            String question,
            List<ContextChunk> chunks,
            IntentDecision intent
    ) {
        String systemPrompt = buildEvidenceSystemPrompt(intent, chunks, question);
        String userContent = buildEvidenceUserContent(contextBlock, question, chunks);

        Map<String, Object> body = Map.of(
                "model", properties.getDeepseekModel(),
                "messages", List.of(
                        Map.of("role", "system", "name", "DeepSeek AI", "content", systemPrompt),
                        Map.of("role", "user", "name", "User", "content", userContent)
                )
        );

        String response = restClient.post()
                .uri(properties.getDeepseekApiUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDeepseekApiKey())
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
                "model", properties.getDeepseekModel(),
                "messages", List.of(
                        Map.of("role", "system", "name", "DeepSeek AI", "content", systemPrompt),
                        Map.of("role", "user", "name", "User", "content", userMessage)
                )
        );
        try {
            String response = restClient.post()
                    .uri(properties.getDeepseekApiUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDeepseekApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseNonStreamAssistantContent(response);
        } catch (RestClientResponseException e) {
            String bodySnippet = abbreviateForError(e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "DeepSeek HTTP " + e.getStatusCode().value() + (bodySnippet.isBlank() ? "" : ": " + bodySnippet),
                    e
            );
        }
    }

    private String parseNonStreamAssistantContent(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode errorNode = root.path("error");
            if (errorNode.has("message")) {
                throw new IllegalStateException("DeepSeek API error: " + errorNode.path("message").asText());
            }
            JsonNode messageNode = root.path("choices").path(0).path("message");
            JsonNode contentNode = messageNode.path("content");
            if (contentNode.isTextual() && !contentNode.asText().isBlank()) {
                return contentNode.asText();
            }
            // DeepSeek reasoning content
            String reasoning = messageNode.path("reasoning_content").asText("").trim();
            if (!reasoning.isBlank()) {
                String contentFallback = contentNode.isTextual() ? contentNode.asText().trim() : "";
                if (!contentFallback.isBlank()) {
                    return reasoning + "\n\n" + contentFallback;
                }
                return reasoning;
            }
            throw new IllegalStateException("Model returned empty content: " + abbreviateForError(response));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse model response: " + e.getMessage(), e);
        }
    }

    private String buildEvidenceSystemPrompt(IntentDecision intent, List<ContextChunk> chunks, String question) {
        String base = KnowledgeAssistantPrompts.minimaxEvidenceSystemPrompt(properties.getAssistantName());
        return outputContracts.composeSystemPrompt(base, intent, chunks, question);
    }

    private static String buildEvidenceUserContent(String contextBlock, String question, List<ContextChunk> chunks) {
        String evidence = EvidencePromptFormatter.format(chunks);
        String prefix = contextBlock == null || contextBlock.isBlank() ? "" : contextBlock.trim() + "\n\n";
        return prefix + "[Evidence Start]\n" + evidence + "\n[Evidence End]\n\n用户本轮提问: " + question;
    }

    private static String abbreviateForError(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\n', ' ').trim();
        int max = 800;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public String askWithEvidenceStream(String question, List<ContextChunk> chunks, StreamListener listener) {
        return askWithEvidenceStream("", question, chunks, null, listener);
    }

    public String askWithEvidenceStream(String contextBlock, String question, List<ContextChunk> chunks, StreamListener listener) {
        return askWithEvidenceStream(contextBlock, question, chunks, null, listener);
    }

    public String askWithEvidenceStream(
            String contextBlock,
            String question,
            List<ContextChunk> chunks,
            IntentDecision intent,
            StreamListener listener
    ) {
        String systemPrompt = buildEvidenceSystemPrompt(intent, chunks, question);
        String userContent = buildEvidenceUserContent(contextBlock, question, chunks);

        Map<String, Object> body = Map.of(
                "model", properties.getDeepseekModel(),
                "stream", true,
                "messages", List.of(
                        Map.of("role", "system", "name", "DeepSeek AI", "content", systemPrompt),
                        Map.of("role", "user", "name", "User", "content", userContent)
                )
        );

        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getDeepseekApiUrl()))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getDeepseekApiKey())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("DeepSeek stream failed, status=" + response.statusCode());
            }
            return parseStreamBody(response.body(), listener);
        } catch (Exception ex) {
            throw new IllegalStateException("DeepSeek stream call failed: " + ex.getMessage(), ex);
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
            JsonNode errorNode = chunk.path("error");
            if (errorNode.has("message")) {
                throw new IllegalStateException("DeepSeek stream error: " + errorNode.path("message").asText());
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