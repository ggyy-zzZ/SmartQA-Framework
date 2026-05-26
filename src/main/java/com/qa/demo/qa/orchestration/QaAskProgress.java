package com.qa.demo.qa.orchestration;

import java.util.Map;

/**
 * 问答流程进度回调（SSE 流式实现；同步路径用 NOOP）。
 */
@FunctionalInterface
public interface QaAskProgress {

    QaAskProgress NOOP = (phase, message, details) -> { };

    void onThinking(String phase, String message, Map<String, Object> details);
}
