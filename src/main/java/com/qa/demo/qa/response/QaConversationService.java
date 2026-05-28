package com.qa.demo.qa.response;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.EntityRef;
import com.qa.demo.qa.domain.ScenarioRuleEngine;
import com.qa.demo.qa.domain.ConversationSessionSupport;
import com.qa.demo.qa.intent.IntentLlmClassifier;
import com.qa.demo.qa.retrieval.GraphContextService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final ScenarioRuleEngine ruleEngine;
    private final IntentLlmClassifier llmClassifier;

    public QaConversationService(GraphContextService graphContextService, ScenarioRuleEngine ruleEngine,
            IntentLlmClassifier llmClassifier) {
        this.graphContextService = graphContextService;
        this.ruleEngine = ruleEngine;
        this.llmClassifier = llmClassifier;
    }

    private static final Pattern DIGIT_ONLY = Pattern.compile("^\\d{1,2}$");
    private static final Pattern PERSON_BEFORE_GUAN = Pattern.compile("([\\u4e00-\\u9fa5]{2,4})\\s*管");

    public record ConversationTurn(
            String turnId,
            String question,
            String answer,
            List<String> focusCompanyNames,
            List<String> personCandidates,
            String focusPersonName,
            String lastQueryType,
            String lastIntent,
            Map<String, List<EntityRef>> retrievedEntities
    ) {
        public ConversationTurn(String turnId, String question, String answer, List<String> focusCompanyNames) {
            this(turnId, question, answer, focusCompanyNames, List.of(), "", "", "", Map.of());
        }

        public ConversationTurn(
                String turnId,
                String question,
                String answer,
                List<String> focusCompanyNames,
                List<String> personCandidates
        ) {
            this(turnId, question, answer, focusCompanyNames, personCandidates, "", "", "", Map.of());
        }

        public ConversationTurn(
                String turnId,
                String question,
                String answer,
                List<String> focusCompanyNames,
                List<String> personCandidates,
                String focusPersonName
        ) {
            this(turnId, question, answer, focusCompanyNames, personCandidates, focusPersonName, "", "", Map.of());
        }

        public ConversationTurn(
                String turnId,
                String question,
                String answer,
                List<String> focusCompanyNames,
                List<String> personCandidates,
                String focusPersonName,
                String lastQueryType,
                String lastIntent
        ) {
            this(turnId, question, answer, focusCompanyNames, personCandidates, focusPersonName, lastQueryType, lastIntent, Map.of());
        }

        /**
         * 获取指定类型的实体列表。
         */
        public List<EntityRef> getEntities(String type) {
            if (retrievedEntities == null || !retrievedEntities.containsKey(type)) {
                return List.of();
            }
            return retrievedEntities.get(type);
        }

        /**
         * 获取公司实体列表。
         */
        public List<EntityRef> getCompanies() {
            return getEntities(EntityRef.TYPE_COMPANY);
        }

        /**
         * 获取人物实体列表。
         */
        public List<EntityRef> getPersons() {
            return getEntities(EntityRef.TYPE_PERSON);
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

    /**
     * 多轮追问时的检索问句：保留用户原问，并附上最近对话摘要，由意图/检索层理解指代，不做业务模板改写。
     */
    public String buildRetrievalQuestion(String rawQuestion, List<ConversationTurn> prior, boolean followUp) {
        if (rawQuestion == null || rawQuestion.isBlank() || !followUp || prior.isEmpty()) {
            return rawQuestion == null ? "" : rawQuestion.trim();
        }
        return formatRetrievalWithDialogContext(rawQuestion.trim(), prior);
    }

    private String formatRetrievalWithDialogContext(String followUpQuestion, List<ConversationTurn> prior) {
        int start = Math.max(0, prior.size() - 2);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < prior.size(); i++) {
            ConversationTurn turn = prior.get(i);
            sb.append("[上文] 用户：").append(truncateOneLine(turn.question(), 220)).append('\n');
            sb.append("助手：").append(truncateOneLine(turn.answer(), 400)).append('\n');
        }
        sb.append("[追问] ").append(followUpQuestion);
        appendSessionAnchorsIfPresent(sb, prior.get(prior.size() - 1));
        return sb.toString();
    }

    private void appendSessionAnchorsIfPresent(StringBuilder sb, ConversationTurn last) {
        if (last == null) {
            return;
        }
        String person = resolveFocusPerson(last);
        String company = ConversationSessionSupport.primaryCompanyFocus(last.focusCompanyNames());
        if (person.isBlank() && company.isBlank()) {
            return;
        }
        sb.append('\n');
        if (!company.isBlank()) {
            sb.append("[会话锚点] 主体：").append(company).append('\n');
        }
        if (!person.isBlank()) {
            sb.append("[会话锚点] 人物：").append(person).append('\n');
        }
    }

    public String buildModelContextBlock(List<ConversationTurn> prior, boolean followUp) {
        return buildModelContextBlock(prior, followUp, "");
    }

    public String buildModelContextBlock(List<ConversationTurn> prior, boolean followUp, String currentQuestion) {
        if (!followUp || prior.isEmpty()) {
            return "";
        }
        int start = Math.max(0, prior.size() - 2);
        StringBuilder sb = new StringBuilder();
        sb.append("[对话上下文]\n");
        for (int i = start; i < prior.size(); i++) {
            ConversationTurn turn = prior.get(i);
            int n = i - start + 1;
            sb.append("第").append(n).append("轮用户问：").append(truncateOneLine(turn.question(), 260)).append('\n');
            sb.append("第").append(n).append("轮助手答：").append(truncateOneLine(turn.answer(), 380)).append('\n');
        }
        ConversationTurn last = prior.get(prior.size() - 1);
        String primaryCompany = ConversationSessionSupport.primaryCompanyFocus(last.focusCompanyNames());
        if (!primaryCompany.isBlank()) {
            sb.append("会话关注主体：").append(primaryCompany).append('\n');
        }
        String person = resolveFocusPerson(last);
        if (!person.isBlank()) {
            sb.append("会话关注人物：").append(person).append('\n');
        }
        if (last.lastQueryType() != null && !last.lastQueryType().isBlank()) {
            sb.append("上一轮查询形态：").append(last.lastQueryType()).append('\n');
        }
        if (ruleEngine.isCorrectionQuestion(currentQuestion)) {
            String correctedName = ruleEngine.extractCorrectedEntityName(currentQuestion, "company");
            if (correctedName != null && !correctedName.isBlank()) {
                sb.append("本轮用户纠偏：请以「").append(correctedName).append("」为正确主体，否定上一轮错误指代。\n");
            }
            sb.append("说明：用户在纠正上一轮的对象或事实，勿用「是的」开头附和；先简短确认纠偏，再仅就用户本轮关心的维度、且仅依据证据作答；"
                    + "若用户未问分公司/下属清单，不要展开罗列关联主体。\n");
        } else {
            sb.append("说明：当前为接续追问，请结合上文理解指代（如「它」「这家」「上面」「这些主体」等），"
                    + "仍只依据本轮证据作答，勿脱离上文范围改答无关对象。\n");
        }
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
        appendTurn(conversationId, scope, turnId, question, answer, evidence, List.of(), "", "", "", Map.of());
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
        appendTurn(conversationId, scope, turnId, question, answer, evidence, personCandidates, "", "", "", Map.of());
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
        appendTurn(conversationId, scope, turnId, question, answer, evidence, personCandidates, focusPersonName, "", "", Map.of());
    }

    public void appendTurn(
            String conversationId,
            String scope,
            String turnId,
            String question,
            String answer,
            List<ContextChunk> evidence,
            List<String> personCandidates,
            String focusPersonName,
            String lastIntent
    ) {
        appendTurn(conversationId, scope, turnId, question, answer, evidence, personCandidates, focusPersonName, lastIntent, "", Map.of());
    }

    public void appendTurn(
            String conversationId,
            String scope,
            String turnId,
            String question,
            String answer,
            List<ContextChunk> evidence,
            List<String> personCandidates,
            String focusPersonName,
            String lastIntent,
            String lastQueryType
    ) {
        appendTurn(conversationId, scope, turnId, question, answer, evidence, personCandidates, focusPersonName, lastIntent, lastQueryType, Map.of());
    }

    public void appendTurn(
            String conversationId,
            String scope,
            String turnId,
            String question,
            String answer,
            List<ContextChunk> evidence,
            List<String> personCandidates,
            String focusPersonName,
            String lastIntent,
            String lastQueryType,
            Map<String, List<EntityRef>> retrievedEntities
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
                person,
                lastQueryType == null ? "" : lastQueryType.trim(),
                lastIntent == null ? "" : lastIntent.trim(),
                retrievedEntities != null ? retrievedEntities : Map.of()
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

    /**
     * 获取上一轮检索到的公司实体列表（用于后续轮次检索）。
     */
    public List<EntityRef> priorRetrievedCompanies(String conversationId) {
        List<ConversationTurn> prior = recentTurns(conversationId, 1);
        if (prior.isEmpty()) {
            return List.of();
        }
        return prior.get(prior.size() - 1).getCompanies();
    }

    /**
     * 获取上一轮检索到的实体列表。
     */
    public List<EntityRef> priorRetrievedEntities(String conversationId, String type) {
        List<ConversationTurn> prior = recentTurns(conversationId, 1);
        if (prior.isEmpty()) {
            return List.of();
        }
        return prior.get(prior.size() - 1).getEntities(type);
    }

    private boolean guessFollowUp(String question, List<ConversationTurn> prior) {
        if (prior.isEmpty()) {
            return false;
        }
        String t = question == null ? "" : question.strip();
        if (t.isEmpty()) {
            return false;
        }
        if (ruleEngine.isCorrectionQuestion(t) && !prior.isEmpty()) {
            return true;
        }
        if (graphContextService.hasExplicitCompanyHint(t)
                && !ConversationSessionSupport.shouldTreatAsFollowUpDespiteCompanyHint(t, true, ruleEngine.isCorrectionQuestion(t))) {
            return false;
        }
        if (ConversationSessionSupport.isContinuationUtterance(t)) {
            return true;
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

    public IntentDecision enrichIntentForFollowUp(
            IntentDecision current,
            List<ConversationTurn> prior,
            boolean followUp,
            String question
    ) {
        if (!followUp || prior.isEmpty() || current == null) {
            return current;
        }
        ConversationTurn last = prior.get(prior.size() - 1);
        String priorQuestion = last.question();
        String priorQueryType = last.lastQueryType();
        String priorAnswer = last.answer();
        String personName = current.personName() != null ? current.personName()
                : (resolveFocusPerson(last) != null ? resolveFocusPerson(last) : "");
        // 使用上一轮检索到的公司实体（含状态元数据）传给 LLM
        List<EntityRef> priorCompanies = last.getCompanies();
        List<String> fallbackHints = current.companyHints() != null ? current.companyHints()
                : (last.focusCompanyNames() != null ? last.focusCompanyNames() : List.of());

        // 使用 LLM 判断本轮 queryType（带上下文和结构化实体）
        try {
            IntentDecision llmDecision = llmClassifier.classifyWithEntities(
                    question, priorQuestion, priorQueryType, priorAnswer, personName, priorCompanies);
            if (llmDecision != null) {
                String reason = llmDecision.reason() == null ? "" : llmDecision.reason();
                if (!reason.contains("followup_llm")) {
                    reason = (reason.isBlank() ? "" : reason + "; ") + "followup_llm";
                }
                // 合并：LLM 返回的公司 hints + 上一轮的公司实体
                List<String> mergedHints = new ArrayList<>();
                if (llmDecision.companyHints() != null) {
                    mergedHints.addAll(llmDecision.companyHints());
                }
                // 将 priorCompanies 的名称加入（去重）
                if (priorCompanies != null) {
                    for (EntityRef ref : priorCompanies) {
                        if (ref.name() != null && !ref.name().isBlank() && !mergedHints.contains(ref.name())) {
                            mergedHints.add(ref.name());
                        }
                    }
                }
                return new IntentDecision(
                        llmDecision.intent(),
                        llmDecision.confidence(),
                        reason,
                        llmDecision.queryType(),
                        llmDecision.personName() != null && !llmDecision.personName().isBlank()
                                ? llmDecision.personName() : personName,
                        mergedHints,
                        llmDecision.roleFocus(),
                        current.personEmployeeId()
                );
            }
        } catch (Exception ignored) {
            // LLM 失败时 fallback 到继承逻辑
        }

        // Fallback：使用原有的继承逻辑
        IntentDecision inherited = ConversationSessionSupport.inheritIntentSlots(
                current,
                question,
                priorQuestion,
                priorQueryType,
                resolveFocusPerson(last)
        );
        return mergeSessionEntityHints(inherited, last, question);
    }

    /**
     * 多轮追问时，将上一轮检索到的实体并入当前槽位（领域无关，不限于证照场景）。
     */
    private IntentDecision mergeSessionEntityHints(
            IntentDecision decision,
            ConversationTurn last,
            String question
    ) {
        if (decision == null || last == null || question == null || question.isBlank()) {
            return decision;
        }
        String q = question.strip();
        if (!isSubjectReferenceFollowUp(q) && !ConversationSessionSupport.isContinuationUtterance(q)) {
            return decision;
        }
        List<String> merged = new ArrayList<>();
        if (decision.companyHints() != null) {
            merged.addAll(decision.companyHints());
        }
        List<EntityRef> priorCompanies = last.getCompanies();
        if (priorCompanies != null && !priorCompanies.isEmpty()) {
            for (EntityRef ref : priorCompanies) {
                if (ref != null && ref.name() != null && !ref.name().isBlank()) {
                    String name = ref.name().trim();
                    if (!merged.contains(name)) {
                        merged.add(name);
                    }
                }
            }
        }
        if (last.focusCompanyNames() != null) {
            for (String name : last.focusCompanyNames()) {
                if (name != null && !name.isBlank() && !merged.contains(name.trim())) {
                    merged.add(name.trim());
                }
            }
        }
        if (merged.isEmpty() || merged.equals(decision.companyHints())) {
            return decision;
        }
        String reason = decision.reason() == null ? "" : decision.reason();
        if (!reason.contains("session_entity_merge")) {
            reason = (reason.isBlank() ? "" : reason + "; ") + "session_entity_merge";
        }
        return new IntentDecision(
                decision.intent(),
                decision.confidence(),
                reason,
                decision.queryType(),
                decision.personName(),
                merged,
                decision.roleFocus(),
                decision.personEmployeeId()
        );
    }

    private static boolean isSubjectReferenceFollowUp(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.strip();
        return q.contains("这些") || q.contains("上面") || q.contains("刚才")
                || q.contains("存续") || q.contains("主体") || q.contains("它们")
                || q.contains("那些") || q.contains("这几") || q.contains("那几");
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
            String name = c.displayLabel();
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = ConversationSessionSupport.stripCompanyIdSuffix(name.trim());
            if (trimmed.equalsIgnoreCase("unknown") || trimmed.equals("employee")) {
                continue;
            }
            seen.add(trimmed);
            if (seen.size() >= 6) {
                break;
            }
        }
        List<String> parents = new ArrayList<>();
        List<String> branches = new ArrayList<>();
        for (String n : seen) {
            if (n.contains("分公司")) {
                branches.add(n);
            } else {
                parents.add(n);
            }
        }
        if (!parents.isEmpty()) {
            return parents.size() > 2 ? parents.subList(0, 2) : parents;
        }
        return branches.isEmpty() ? List.of() : branches.subList(0, Math.min(2, branches.size()));
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
