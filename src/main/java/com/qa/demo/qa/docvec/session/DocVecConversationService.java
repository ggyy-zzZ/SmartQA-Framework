package com.qa.demo.qa.docvec.session;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.docvec.routing.DocVecQueryType;
import com.qa.demo.qa.docvec.routing.DocVecRouteDecision;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DocVec 多轮会话（内存，与主链路 QaConversationService 隔离）。
 */
@Service
public class DocVecConversationService {

    private static final int MAX_TURNS = 10;
    private static final long TTL_MS = 30L * 60L * 1000L;
    private static final int MAX_CONVERSATIONS = 500;
    private static final int MAX_COMPANY_NAMES = 120;
    private static final int ANSWER_SUMMARY_CHARS = 400;

    private final ConcurrentHashMap<String, Conversation> store = new ConcurrentHashMap<>();

    public String resolveConversationId(String clientId) {
        evictStale();
        if (clientId != null && !clientId.isBlank()) {
            String id = clientId.trim();
            store.computeIfAbsent(id, k -> new Conversation());
            touch(id);
            return id;
        }
        String id = UUID.randomUUID().toString();
        store.put(id, new Conversation());
        return id;
    }

    public List<DocVecConversationTurn> recentTurns(String conversationId, int max) {
        Conversation conversation = store.get(conversationId);
        if (conversation == null || conversation.turns.isEmpty()) {
            return List.of();
        }
        int n = Math.max(1, Math.min(max, conversation.turns.size()));
        return List.copyOf(conversation.turns.subList(Math.max(0, conversation.turns.size() - n), conversation.turns.size()));
    }

    public boolean resolveFollowUp(Boolean clientFlag, String question, List<DocVecConversationTurn> prior) {
        if (clientFlag != null) {
            return clientFlag;
        }
        if (prior == null || prior.isEmpty()) {
            return false;
        }
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            return false;
        }
        if (q.contains("换个话题") || q.contains("新问题") || q.contains("不相关")) {
            return false;
        }
        return looksLikeFollowUp(q) || prior.size() >= 1;
    }

    public DocVecSessionSnapshot buildSnapshot(List<DocVecConversationTurn> prior, boolean followUp) {
        if (!followUp || prior == null || prior.isEmpty()) {
            return DocVecSessionSnapshot.empty();
        }
        DocVecConversationTurn last = prior.get(prior.size() - 1);
        return new DocVecSessionSnapshot(
                true,
                last.question(),
                summarize(last.answer()),
                last.queryType(),
                last.personName(),
                last.roleLabel(),
                last.certificateTypeName(),
                last.regionKeyword(),
                last.companyNames(),
                last.companyIds()
        );
    }

    public void appendTurn(
            String conversationId,
            String question,
            String answer,
            DocVecRouteDecision route,
            List<ContextChunk> evidence
    ) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        Conversation conversation = store.computeIfAbsent(conversationId, k -> new Conversation());
        List<String> names = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (evidence != null) {
            for (ContextChunk chunk : evidence) {
                if (chunk.displayLabel() != null && !chunk.displayLabel().isBlank()
                        && !names.contains(chunk.displayLabel())) {
                    names.add(chunk.displayLabel());
                }
                if (chunk.anchorId() != null && !chunk.anchorId().isBlank()
                        && !chunk.anchorId().startsWith("row-")
                        && !ids.contains(chunk.anchorId())) {
                    ids.add(chunk.anchorId());
                }
                if (names.size() >= MAX_COMPANY_NAMES) {
                    break;
                }
            }
        }
        conversation.turns.add(new DocVecConversationTurn(
                UUID.randomUUID().toString(),
                question == null ? "" : question.trim(),
                answer == null ? "" : answer,
                route == null ? "" : route.mode().name().toLowerCase(),
                route == null ? DocVecQueryType.SEMANTIC : route.queryType(),
                route == null ? "" : route.personName(),
                route == null ? "" : route.roleLabel(),
                route == null ? "" : route.regionKeyword(),
                route == null ? "" : route.certificateTypeName(),
                List.copyOf(names),
                List.copyOf(ids)
        ));
        while (conversation.turns.size() > MAX_TURNS) {
            conversation.turns.remove(0);
        }
        touch(conversationId);
        evictOverflow();
    }

    private static boolean looksLikeFollowUp(String q) {
        return q.contains("其中")
                || q.contains("这些")
                || q.contains("那些")
                || q.contains("上面")
                || q.contains("刚才")
                || q.contains("上一轮")
                || q.contains("它们")
                || q.contains("他们")
                || q.startsWith("那")
                || q.contains("这家")
                || q.contains("该公司")
                || q.contains("还是");
    }

    private static String summarize(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        String text = answer.replaceAll("\\s+", " ").trim();
        if (text.length() <= ANSWER_SUMMARY_CHARS) {
            return text;
        }
        return text.substring(0, ANSWER_SUMMARY_CHARS) + "…";
    }

    private void touch(String id) {
        Conversation conversation = store.get(id);
        if (conversation != null) {
            conversation.lastAccessMs = System.currentTimeMillis();
        }
    }

    private void evictStale() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now - e.getValue().lastAccessMs > TTL_MS);
    }

    private void evictOverflow() {
        if (store.size() <= MAX_CONVERSATIONS) {
            return;
        }
        String oldest = store.entrySet().stream()
                .min((a, b) -> Long.compare(a.getValue().lastAccessMs, b.getValue().lastAccessMs))
                .map(e -> e.getKey())
                .orElse(null);
        if (oldest != null) {
            store.remove(oldest);
        }
    }

    private static final class Conversation {
        private final List<DocVecConversationTurn> turns = new ArrayList<>();
        private long lastAccessMs = System.currentTimeMillis();
    }
}
