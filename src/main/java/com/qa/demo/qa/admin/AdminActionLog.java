package com.qa.demo.qa.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * data/qa_logs/admin_actions.jsonl 的 append + tail 读取。
 * 复用 QaLogService 的 Jackson 模式（writeValueAsString + Files.writeString APPEND）。
 */
@Component
public class AdminActionLog {

    private static final Path LOG = Paths.get("data/qa_logs/admin_actions.jsonl");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper mapper;

    public AdminActionLog() {
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public synchronized void append(String action, String jobType, String jobId, String status,
                                    Long durationMs, Integer exitCode, String error,
                                    List<String> scriptArgs, Map<String, Object> extra) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", OffsetDateTime.now().toString());
        row.put("action", action);
        row.put("jobType", jobType);
        row.put("jobId", jobId);
        row.put("user", "local-admin");
        row.put("host", System.getenv().getOrDefault("COMPUTERNAME", "unknown"));
        row.put("status", status);
        if (durationMs != null) {
            row.put("durationMs", durationMs);
        }
        if (exitCode != null) {
            row.put("exitCode", exitCode);
        }
        if (error != null) {
            row.put("error", error);
        }
        if (scriptArgs != null && !scriptArgs.isEmpty()) {
            row.put("script", extractScriptName(scriptArgs));
            row.put("scriptArgs", scriptArgs);
        }
        if (extra != null && !extra.isEmpty()) {
            row.put("extra", extra);
        }
        try {
            Files.createDirectories(LOG.getParent());
            String json = mapper.writeValueAsString(row) + System.lineSeparator();
            Files.writeString(
                    LOG,
                    json,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // 日志失败不能影响主流程
        }
    }

    public synchronized List<Map<String, Object>> readRecent(int n) {
        if (!Files.exists(LOG)) {
            return List.of();
        }
        try {
            List<String> all = Files.readAllLines(LOG, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - n);
            List<Map<String, Object>> out = new ArrayList<>(all.size() - from);
            for (int i = from; i < all.size(); i++) {
                String line = all.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> parsed = mapper.readValue(line, MAP_TYPE);
                    out.add(parsed);
                } catch (Exception ignored) {
                    // skip malformed
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String extractScriptName(List<String> args) {
        for (int i = args.size() - 1; i >= 0; i--) {
            String a = args.get(i);
            if (a != null && (a.endsWith(".py") || a.endsWith(".sh"))) {
                int slash = Math.max(a.lastIndexOf('/'), a.lastIndexOf('\\'));
                return slash >= 0 ? a.substring(slash + 1) : a;
            }
        }
        return null;
    }
}
