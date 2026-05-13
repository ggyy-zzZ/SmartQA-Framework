package com.qa.demo.qa.response;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QaLogService {

    private static final Path LOG_DIR = Path.of("data", "qa_logs");
    private static final Path ASK_LOG = LOG_DIR.resolve("ask_events.jsonl");
    private static final Path FEEDBACK_LOG = LOG_DIR.resolve("feedback_events.jsonl");
    private static final Path CANDIDATE_LOG = LOG_DIR.resolve("knowledge_candidates.jsonl");

    private final ObjectMapper objectMapper;

    public QaLogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String nextTurnId() {
        return UUID.randomUUID().toString();
    }

    public synchronized void appendAskEvent(Map<String, Object> event) {
        appendJsonLine(ASK_LOG, event);
    }

    public synchronized void appendFeedbackEvent(Map<String, Object> event) {
        appendJsonLine(FEEDBACK_LOG, event);
    }

    public synchronized void appendKnowledgeCandidate(Map<String, Object> event) {
        appendJsonLine(CANDIDATE_LOG, event);
    }

    public synchronized List<Map<String, Object>> readAskHistory(int limit) {
        int top = Math.max(1, Math.min(limit, 200));
        if (!Files.exists(ASK_LOG)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(ASK_LOG, StandardCharsets.UTF_8);
            List<Map<String, Object>> all = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = objectMapper.readValue(
                        line, new TypeReference<Map<String, Object>>() {
                        });
                all.add(row);
            }
            int from = Math.max(0, all.size() - top);
            return all.subList(from, all.size());
        } catch (Exception e) {
            return List.of(
                    Map.of(
                            "error", "failed_to_read_history",
                            "message", e.getMessage(),
                            "timestamp", OffsetDateTime.now().toString()
                    )
            );
        }
    }

    public Map<String, Object> buildFeedbackEvent(String turnId, boolean useful, String comment) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("turnId", turnId);
        event.put("useful", useful);
        event.put("comment", comment == null ? "" : comment);
        event.put("timestamp", OffsetDateTime.now().toString());
        return event;
    }

    public Map<String, Object> buildKnowledgeCandidateEvent(
            String turnId,
            String question,
            String intent,
            String retrievalSource,
            String reason,
            List<?> evidence
    ) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("turnId", turnId);
        event.put("question", question);
        event.put("intent", intent);
        event.put("retrievalSource", retrievalSource);
        event.put("reason", reason);
        event.put("evidenceSize", evidence == null ? 0 : evidence.size());
        event.put("evidence", evidence == null ? List.of() : evidence);
        event.put("status", "pending_review");
        event.put("timestamp", OffsetDateTime.now().toString());
        return event;
    }

    private void appendJsonLine(Path path, Map<String, Object> payload) {
        try {
            ensureDir();
            String json = objectMapper.writeValueAsString(payload) + System.lineSeparator();
            Files.writeString(
                    path,
                    json,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Do not fail main QA flow because of logging.
        }
    }

    private void ensureDir() throws IOException {
        if (!Files.exists(LOG_DIR)) {
            Files.createDirectories(LOG_DIR);
        }
    }
}
