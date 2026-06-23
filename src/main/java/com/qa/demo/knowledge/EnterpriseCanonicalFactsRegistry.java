package com.qa.demo.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.core.QaScopes;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
/**
 * 企业「常识事实」注册表（classpath:qa/enterprise-canonical-facts.json）。
 * <p>
 * 与主动学习区别：启动即加载、人工维护、高优先级注入证据；锚点 ID 与展示名分离，snippet 走 evidence schema。
 */
@Component
public class EnterpriseCanonicalFactsRegistry {

    public static final String SOURCE = "enterprise_canonical";
    public static final String SCHEMA_ID = "enterprise_fact_v1";
    private static final double CANONICAL_SCORE = 30.0;

    private final List<CanonicalFact> facts;
    private final List<RetrievalAlias> retrievalAliases;
    private final EvidenceSchemaRegistry evidenceSchemas;

    public EnterpriseCanonicalFactsRegistry(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader, EvidenceSchemaRegistry evidenceSchemas) {
        Loaded loaded = load(objectMapper, configLoader);
        this.facts = loaded.facts();
        this.retrievalAliases = loaded.retrievalAliases();
        this.evidenceSchemas = evidenceSchemas;
    }

    /**
     * 按问句触发词匹配常识事实，转为高优先级证据片段。
     */
    public List<ContextChunk> retrieve(String question, String scope) {
        if (question == null || question.isBlank() || !QaScopes.ENTERPRISE.equals(QaScopes.normalize(scope))) {
            return List.of();
        }
        String q = question.toLowerCase(Locale.ROOT);
        List<ContextChunk> matched = new ArrayList<>();
        for (CanonicalFact fact : facts) {
            if (!fact.scope().isBlank() && !QaScopes.ENTERPRISE.equals(fact.scope())) {
                continue;
            }
            if (!fact.matches(q, question)) {
                continue;
            }
            String snippet = evidenceSchemas.formatSnippet(SCHEMA_ID, fact.values());
            if (snippet.isBlank()) {
                continue;
            }
            ContextChunk chunk = chunkFor(fact, snippet);
            matched.add(chunk);
        }
        return matched;
    }

    /**
     * 将别称替换为实名，仅用于结构化检索问句改写（不改变用户可见原句）。
     */
    public String augmentQuestionForStructuredRetrieval(String question, List<ContextChunk> canonicalEvidence) {
        if (question == null || question.isBlank()) {
            return question == null ? "" : question;
        }
        String augmented = question;
        for (RetrievalAlias alias : retrievalAliases) {
            if (alias.matches(augmented)) {
                augmented = alias.apply(augmented);
            }
        }
        if (canonicalEvidence != null) {
            for (ContextChunk chunk : canonicalEvidence) {
                if (chunk == null || !SOURCE.equals(chunk.source()) || chunk.snippet() == null) {
                    continue;
                }
                String legal = extractFieldValue(chunk.snippet(), "姓名");
                String aliasLabel = extractFieldValue(chunk.snippet(), "别称");
                if (!legal.isBlank() && !aliasLabel.isBlank()
                        && augmented.toLowerCase(Locale.ROOT).contains(aliasLabel.toLowerCase(Locale.ROOT))) {
                    augmented = replaceIgnoreCase(augmented, aliasLabel, legal);
                }
            }
        }
        return augmented;
    }

    /**
     * 当员工索引未命中时，用常识事实中的 employee 锚点 id 解析人物（如 BOSS 锚点 29）。
     */
    public OptionalInt resolveEmployeeAnchorId(String personHint, String question) {
        String q = question == null ? "" : question;
        String qLower = q.toLowerCase(Locale.ROOT);
        String hint = personHint == null ? "" : personHint.trim();
        String hintLower = hint.toLowerCase(Locale.ROOT);
        for (CanonicalFact fact : facts) {
            if (!ContextChunk.KIND_EMPLOYEE.equals(fact.entityKind())) {
                continue;
            }
            String legalName = fact.values().getOrDefault("legalName", "");
            boolean hit = (!hint.isBlank() && hint.equals(fact.displayLabel()))
                    || (!hint.isBlank() && hintLower.equals(legalName.toLowerCase(Locale.ROOT)))
                    || (!hint.isBlank() && legalName.length() >= 2
                        && fact.displayLabel().toLowerCase(Locale.ROOT).contains(hintLower));
            if (!hit) {
                continue;
            }
            int id = parseAnchorId(fact.anchorId());
            if (id > 0) {
                return OptionalInt.of(id);
            }
        }
        return OptionalInt.empty();
    }

    /** 问句是否可解析出公司常识锚点（含列表问句，当 fact 配置 injectOnListQuestion）。 */
    public boolean hasResolvableCompanyAnchor(String question) {
        return resolveCompanyAnchorId(question).isPresent();
    }

    /** 从公司类常识事实解析 company_id 锚点，供子公司列表等结构化检索。 */
    public OptionalInt resolveCompanyAnchorId(String question) {
        if (question == null || question.isBlank()) {
            return OptionalInt.empty();
        }
        String qLower = question.toLowerCase(Locale.ROOT);
        for (CanonicalFact fact : facts) {
            if (!ContextChunk.KIND_COMPANY.equals(fact.entityKind())) {
                continue;
            }
            if (!fact.triggerMatches(qLower, question)) {
                continue;
            }
            int id = parseAnchorId(fact.anchorId());
            if (id > 0) {
                return OptionalInt.of(id);
            }
        }
        return OptionalInt.empty();
    }

    private static int parseAnchorId(String anchorId) {
        if (anchorId == null || anchorId.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(anchorId.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static ContextChunk chunkFor(CanonicalFact fact, String snippet) {
        if (ContextChunk.KIND_EMPLOYEE.equals(fact.entityKind())) {
            return ContextChunk.ofEmployee(
                    fact.anchorId(),
                    fact.displayLabel(),
                    fact.field(),
                    snippet,
                    CANONICAL_SCORE,
                    SOURCE,
                    SCHEMA_ID
            );
        }
        return ContextChunk.ofCompany(
                fact.anchorId(),
                fact.displayLabel(),
                fact.field(),
                snippet,
                CANONICAL_SCORE,
                SOURCE,
                SCHEMA_ID
        );
    }

    private static String extractFieldValue(String snippet, String label) {
        if (snippet == null || label == null) {
            return "";
        }
        String prefix = label + "=";
        int idx = snippet.indexOf(prefix);
        if (idx < 0) {
            return "";
        }
        int start = idx + prefix.length();
        int end = snippet.indexOf(';', start);
        return (end < 0 ? snippet.substring(start) : snippet.substring(start, end)).trim();
    }

    private static String replaceIgnoreCase(String text, String from, String to) {
        if (text == null || from == null || from.isBlank()) {
            return text;
        }
        return text.replaceAll("(?i)" + java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(to));
    }

    private static Loaded load(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) {
        try {
            JsonNode root = configLoader.readTree("enterprise-canonical-facts");
            List<CanonicalFact> factList = new ArrayList<>();
            for (JsonNode node : root.path("facts")) {
                Map<String, String> values = new LinkedHashMap<>();
                node.path("values").fields().forEachRemaining(e ->
                        values.put(e.getKey(), e.getValue().asText(""))
                );
                List<String> triggers = new ArrayList<>();
                node.path("triggers").forEach(t -> {
                    if (t.isTextual() && !t.asText().isBlank()) {
                        triggers.add(t.asText().trim());
                    }
                });
                factList.add(new CanonicalFact(
                        node.path("id").asText(""),
                        node.path("scope").asText(QaScopes.ENTERPRISE),
                        triggers,
                        node.path("entityKind").asText(ContextChunk.KIND_COMPANY),
                        node.path("anchorId").asText(""),
                        node.path("displayLabel").asText(""),
                        node.path("field").asText("enterprise_fact"),
                        node.path("injectOnListQuestion").asBoolean(false),
                        Map.copyOf(values)
                ));
            }
            List<RetrievalAlias> aliases = new ArrayList<>();
            root.path("retrievalAliases").forEach(node ->
                    aliases.add(new RetrievalAlias(
                            node.path("alias").asText(""),
                            node.path("canonical").asText("")
                    ))
            );
            return new Loaded(List.copyOf(factList), List.copyOf(aliases));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load qa/enterprise-canonical-facts.json", ex);
        }
    }

    private record Loaded(List<CanonicalFact> facts, List<RetrievalAlias> retrievalAliases) {
    }

    private record CanonicalFact(
            String id,
            String scope,
            List<String> triggers,
            String entityKind,
            String anchorId,
            String displayLabel,
            String field,
            boolean injectOnListQuestion,
            Map<String, String> values
    ) {
        boolean matches(String qLower, String rawQuestion) {
            return triggerMatches(qLower, rawQuestion);
        }

        boolean triggerMatches(String qLower, String rawQuestion) {
            if (!injectOnListQuestion && looksLikeListQuestion(rawQuestion)) {
                return false;
            }
            for (String trigger : triggers) {
                if (trigger == null || trigger.isBlank()) {
                    continue;
                }
                String t = trigger.toLowerCase(Locale.ROOT);
                if (qLower.contains(t)) {
                    return true;
                }
                if (rawQuestion.contains(trigger)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean looksLikeListQuestion(String question) {
            if (question == null || question.isBlank()) {
                return false;
            }
            return question.contains("哪些")
                    || question.contains("有多少")
                    || question.contains("名单")
                    || question.contains("列表")
                    || question.contains("一共")
                    || question.contains("总共");
        }
    }

    private record RetrievalAlias(String alias, String canonical) {
        boolean matches(String question) {
            return alias != null && !alias.isBlank()
                    && question.toLowerCase(Locale.ROOT).contains(alias.toLowerCase(Locale.ROOT));
        }

        String apply(String question) {
            return question.replaceAll("(?i)" + java.util.regex.Pattern.quote(alias), canonical);
        }
    }
}
