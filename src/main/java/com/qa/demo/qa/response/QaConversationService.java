package com.qa.demo.qa.response;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.retrieval.GraphContextService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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

    private static final Pattern DIGIT_ONLY = Pattern.compile("^\\d{1,2}$");
    private static final Pattern PERSON_BEFORE_GUAN = Pattern.compile("([\\u4e00-\\u9fa5]{2,4})\\s*管");

    public record ConversationTurn(
            String turnId,
            String question,
            String answer,
            List<String> focusCompanyNames,
            List<String> personCandidates,
            String focusPersonName
    ) {
        public ConversationTurn(String turnId, String question, String answer, List<String> focusCompanyNames) {
            this(turnId, question, answer, focusCompanyNames, List.of(), "");
        }

        public ConversationTurn(
                String turnId,
                String question,
                String answer,
                List<String> focusCompanyNames,
                List<String> personCandidates
        ) {
            this(turnId, question, answer, focusCompanyNames, personCandidates, "");
        }
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
        String trimmed = rawQuestion.trim();

        if ((isCertificateFollowUpQuestion(trimmed) || isCertificateTypeOnlyFollowUp(trimmed))
                && isPersonCentricQuestion(last.question())) {
            String person = resolveFocusPerson(last);
            if (!person.isBlank()) {
                return person + " 负责的资质证照具体有哪些（含证照类型名称）";
            }
            return "[上文] 用户："
                    + truncateOneLine(last.question(), 220)
                    + "\n助手："
                    + truncateOneLine(last.answer(), 400)
                    + "\n[追问] "
                    + trimmed;
        }

        if (last.focusCompanyNames() != null && !last.focusCompanyNames().isEmpty()
                && !(isCertificateFollowUpQuestion(trimmed) && isPersonCentricQuestion(last.question()))) {
            StringBuilder sb = new StringBuilder(trimmed);
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
                + trimmed;
    }

    /** 追问「类型有哪些」等，承接上一轮人物证照主题（问句可能不含「证照」二字）。 */
    public boolean isCertificateTypeOnlyFollowUp(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String t = question.strip();
        if (t.length() > 20) {
            return false;
        }
        return containsAny(t, "类型", "哪些", "什么", "啥", "具体", "涉及")
                && !containsAny(t, "主体", "实体类型", "有限责任公司");
    }

    public boolean isCertificateFollowUpQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String t = question.strip();
        boolean mentionsCert = containsAny(t, "证照", "许可证", "执照", "资质", "证书", "备案");
        boolean detailAsk = containsAny(t, "具体", "详细", "分别", "列举", "哪些", "什么", "啥");
        return mentionsCert && (detailAsk || t.length() <= 18);
    }

    public boolean isPersonCentricQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String t = question.strip();
        return containsAny(t, "管", "负责", "保管", "监管", "执行")
                && (containsAny(t, "证照", "许可证", "执照", "资质", "印章")
                || PERSON_BEFORE_GUAN.matcher(t).find());
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
        String person = resolveFocusPerson(last);
        if (!person.isBlank()) {
            sb.append("上一轮关注人物：").append(person).append('\n');
        }
        sb.append("说明：当前为接续追问，请结合上文理解指代（如「它」「这家」「上面」「具体是哪些证照」等），仍只依据本轮证据作答；若追问证照明细，应延续上一轮人物/主题，勿改答无关公司列表。\n");
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
        appendTurn(conversationId, scope, turnId, question, answer, evidence, List.of(), "");
    }

    public void appendTurn(
            String conversationId,
            String scope,
            String turnId,
            String question,
            String answer,
            List<ContextChunk> evidence,
            List<String> personCandidates
    ) {
        appendTurn(conversationId, scope, turnId, question, answer, evidence, personCandidates, "");
    }

    public void appendTurn(
            String conversationId,
            String scope,
            String turnId,
            String question,
            String answer,
            List<ContextChunk> evidence,
            List<String> personCandidates,
            String focusPersonName
    ) {
        Conversation c = store.get(conversationId);
        if (c == null) {
            return;
        }
        List<String> names = extractFocusCompanyNames(evidence);
        List<String> persons = personCandidates == null ? List.of() : List.copyOf(personCandidates);
        String person = focusPersonName == null ? "" : focusPersonName.trim();
        if (person.isBlank()) {
            person = extractPersonFromQuestion(question);
        }
        while (c.turns.size() >= MAX_TURNS) {
            c.turns.remove(0);
        }
        c.turns.add(new ConversationTurn(
                turnId,
                question,
                answer == null ? "" : answer,
                names,
                persons,
                person
        ));
        touch(conversationId);
        trimConversationsIfNeeded();
    }

    /**
     * 上一轮为人物澄清时，解析用户回复的序号或全名。
     */
    public Optional<String> resolvePersonFollowUpSelection(String question, List<ConversationTurn> prior) {
        if (prior == null || prior.isEmpty() || question == null || question.isBlank()) {
            return Optional.empty();
        }
        ConversationTurn last = prior.get(prior.size() - 1);
        List<String> candidates = last.personCandidates();
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String trimmed = question.trim();
        if (DIGIT_ONLY.matcher(trimmed).matches()) {
            int idx = Integer.parseInt(trimmed) - 1;
            if (idx >= 0 && idx < candidates.size()) {
                return Optional.of(candidates.get(idx));
            }
            return Optional.empty();
        }
        for (String name : candidates) {
            if (name != null && (trimmed.equals(name) || trimmed.contains(name))) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    public boolean priorHasPersonClarification(String conversationId) {
        List<ConversationTurn> prior = recentTurns(conversationId, 1);
        if (prior.isEmpty()) {
            return false;
        }
        List<String> c = prior.get(prior.size() - 1).personCandidates();
        return c != null && !c.isEmpty();
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
        if (isCertificateFollowUpQuestion(t)) {
            for (int i = prior.size() - 1; i >= 0; i--) {
                String pq = prior.get(i).question();
                if (isPersonCentricQuestion(pq) || containsAny(pq, "证照", "许可证", "执照", "资质")) {
                    return true;
                }
            }
        }
        if (isCertificateTypeOnlyFollowUp(t)) {
            for (int i = prior.size() - 1; i >= 0; i--) {
                if (isPersonCentricQuestion(prior.get(i).question())) {
                    return true;
                }
            }
        }
        boolean shortWithoutEntity = t.length() <= 22 && !graphContextService.hasExplicitCompanyHint(t);
        return shortWithoutEntity && priorHasCompanyNames(prior);
    }

    private static String resolveFocusPerson(ConversationTurn turn) {
        if (turn == null) {
            return "";
        }
        if (turn.focusPersonName() != null && !turn.focusPersonName().isBlank()) {
            return turn.focusPersonName().trim();
        }
        return extractPersonFromQuestion(turn.question());
    }

    private static String extractPersonFromQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        var matcher = PERSON_BEFORE_GUAN.matcher(question.strip());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
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
