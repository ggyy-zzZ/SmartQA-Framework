package com.qa.demo.qa.response;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.retrieval.GraphContextService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话上下文：内存会话 + 追问时拼接检索问句与模型上文。
 */
@Service
public class QaConversationService {

    private static final int MAX_TURNS = 12;
    private static final long TTL_MS = 30L * 60L * 1000L;
    private static final int MAX_CONVERSATIONS = 2_000;

    private final ConcurrentHashMap<String, Conversation> store = new ConcurrentHashMap<>();
    private final GraphContextService graphContextService;

    public QaConversationService(GraphContextService graphContextService) {
        this.graphContextService = graphContextService;
    }

    public record ConversationTurn(
            String turnId,
            String question,
            String answer,
            List<String> focusCompanyNames
    ) {
    }

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

    public List<ConversationTurn> recentTurns(String conversationId, int max) {
        Conversation c = store.get(conversationId);
        if (c == null || c.turns.isEmpty()) {
            return List.of();
        }
        int n = Math.max(1, Math.min(max, c.turns.size()));
        return List.copyOf(c.turns.subList(Math.max(0, c.turns.size() - n), c.turns.size()));
    }

    /**
     * 是否把当前句当作接续上一轮的追问（可由客户端显式指定覆盖）。
     */
    public boolean resolveFollowUp(Boolean clientFlag, String question, List<ConversationTurn> prior) {
        if (clientFlag != null) {
            return clientFlag;
        }
        return guessFollowUp(question, prior);
    }

    public String buildRetrievalQuestion(String rawQuestion, List<ConversationTurn> prior, boolean followUp) {
        if (rawQuestion == null || rawQuestion.isBlank() || !followUp || prior.isEmpty()) {
            return rawQuestion == null ? "" : rawQuestion.trim();
        }
        ConversationTurn last = prior.get(prior.size() - 1);
        if (last.focusCompanyNames() != null && !last.focusCompanyNames().isEmpty()) {
            StringBuilder sb = new StringBuilder(rawQuestion.trim());
            for (String name : last.focusCompanyNames()) {
                if (name != null && !name.isBlank()) {
                    sb.append(' ').append(name.trim());
                }
            }
            return sb.toString();
        }
        return "[上文] 用户："
                + truncateOneLine(last.question(), 220)
                + "\n助手："
                + truncateOneLine(last.answer(), 400)
                + "\n[追问] "
                + rawQuestion.trim();
    }

    public String buildModelContextBlock(List<ConversationTurn> prior, boolean followUp) {
        if (!followUp || prior.isEmpty()) {
            return "";
        }
        ConversationTurn last = prior.get(prior.size() - 1);
        StringBuilder sb = new StringBuilder();
        sb.append("[对话上下文]\n");
        sb.append("上一轮用户问：").append(truncateOneLine(last.question(), 300)).append('\n');
        sb.append("上一轮助手答：").append(truncateOneLine(last.answer(), 500)).append('\n');
        if (last.focusCompanyNames() != null && !last.focusCompanyNames().isEmpty()) {
            sb.append("上一轮涉及对象（名称）：").append(String.join("、", last.focusCompanyNames())).append('\n');
        }
        sb.append("说明：当前为接续追问，请结合上文理解指代（如「它」「这家」「上面」等），仍只依据本轮证据作答。\n");
        return sb.toString();
    }

    public void appendTurn(
            String conversationId,
            String scope,
            String turnId,
            String question,
            String answer,
            List<ContextChunk> evidence
    ) {
        Conversation c = store.get(conversationId);
        if (c == null) {
            return;
        }
        List<String> names = extractFocusCompanyNames(evidence);
        while (c.turns.size() >= MAX_TURNS) {
            c.turns.remove(0);
        }
        c.turns.add(new ConversationTurn(turnId, question, answer == null ? "" : answer, names));
        touch(conversationId);
        trimConversationsIfNeeded();
    }

    public boolean priorHasCompanyFocus(String conversationId) {
        List<ConversationTurn> prior = recentTurns(conversationId, 1);
        if (prior.isEmpty()) {
            return false;
        }
        List<String> n = prior.get(prior.size() - 1).focusCompanyNames();
        return n != null && !n.isEmpty();
    }

    private boolean guessFollowUp(String question, List<ConversationTurn> prior) {
        if (prior.isEmpty()) {
            return false;
        }
        String t = question == null ? "" : question.strip();
        if (t.isEmpty()) {
            return false;
        }
        if (graphContextService.hasExplicitCompanyHint(t)) {
            return false;
        }
        boolean pronounLike = containsAny(t,
                "它", "其", "该", "这家", "那家", "上面", "刚才", "之前", "接着", "继续", "再", "还有", "同样",
                "同一家", "同一个", "这家公司", "那个人", "这个人", "我司", "咱们", "这个", "那个", "哪位",
                "啥", "呢", "吗", "嘛", "多少", "几个", "哪边", "那边", "这边");
        if (pronounLike) {
            return true;
        }
        boolean shortWithoutEntity = t.length() <= 22 && !graphContextService.hasExplicitCompanyHint(t);
        return shortWithoutEntity && priorHasCompanyNames(prior);
    }

    private static boolean priorHasCompanyNames(List<ConversationTurn> prior) {
        for (int i = prior.size() - 1; i >= 0; i--) {
            List<String> n = prior.get(i).focusCompanyNames();
            if (n != null && !n.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> extractFocusCompanyNames(List<ContextChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ContextChunk c : evidence) {
            if (c == null) {
                continue;
            }
            String name = c.companyName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            if (trimmed.equalsIgnoreCase("unknown") || trimmed.equals("employee")) {
                continue;
            }
            seen.add(trimmed);
            if (seen.size() >= 4) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }

    private void touch(String id) {
        Conversation c = store.get(id);
        if (c != null) {
            c.updatedAt = System.currentTimeMillis();
        }
    }

    private void evictStale() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now - e.getValue().updatedAt > TTL_MS);
    }

    private void trimConversationsIfNeeded() {
        if (store.size() <= MAX_CONVERSATIONS) {
            return;
        }
        List<String> oldest = store.entrySet().stream()
                .sorted((a, b) -> Long.compare(a.getValue().updatedAt, b.getValue().updatedAt))
                .limit(store.size() - MAX_CONVERSATIONS + 100)
                .map(java.util.Map.Entry::getKey)
                .toList();
        for (String id : oldest) {
            store.remove(id);
        }
    }

    private static String truncateOneLine(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() <= maxChars) {
            return oneLine;
        }
        return oneLine.substring(0, maxChars) + "…";
    }

    private static final class Conversation {
        final List<ConversationTurn> turns = new ArrayList<>();
        volatile long updatedAt = System.currentTimeMillis();
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (n != null && text.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
