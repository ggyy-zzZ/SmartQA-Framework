package com.qa.demo.qa.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 jobType 的运行状态（per-jobType 全局唯一）。环形日志保留 200 行。
 */
public record AdminJob(
        String jobType,
        String jobId,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<String> tail,
        String error
) {
    public static final int MAX_TAIL = 200;
    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    public static AdminJob idle(String jobType) {
        return new AdminJob(jobType, null, STATUS_IDLE, null, null, List.of(), null);
    }

    public static AdminJob started(String jobType, String jobId) {
        return new AdminJob(jobType, jobId, STATUS_RUNNING, OffsetDateTime.now(), null, new ArrayList<>(), null);
    }

    public AdminJob appendLine(String line) {
        List<String> next = new ArrayList<>(tail);
        next.add(line);
        while (next.size() > MAX_TAIL) {
            next.removeFirst();
        }
        return new AdminJob(jobType, jobId, status, startedAt, finishedAt, List.copyOf(next), error);
    }

    public AdminJob succeeded() {
        return new AdminJob(jobType, jobId, STATUS_SUCCEEDED, startedAt, OffsetDateTime.now(), tail, null);
    }

    public AdminJob failed(String message) {
        return new AdminJob(jobType, jobId, STATUS_FAILED, startedAt, OffsetDateTime.now(), tail, message);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobType", jobType);
        m.put("jobId", jobId);
        m.put("status", status);
        m.put("startedAt", startedAt != null ? startedAt.toString() : null);
        m.put("finishedAt", finishedAt != null ? finishedAt.toString() : null);
        m.put("tail", tail);
        m.put("error", error);
        m.put("running", STATUS_RUNNING.equals(status));
        return m;
    }
}
