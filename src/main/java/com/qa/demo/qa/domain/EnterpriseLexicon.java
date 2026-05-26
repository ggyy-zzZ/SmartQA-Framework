package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 企业知识问答领域词典（classpath:qa/enterprise-lexicon.json），替代 Java 内散落的关键词硬编码。
 */
public class EnterpriseLexicon {

    private final List<String> personMarkers;
    private final List<String> personQuestionPrefixes;
    private final List<String> personListTriggers;
    private final List<String> roleRelationTriggers;
    private final List<String> companySuffixes;
    private final int companyHintWindowChars;
    private final Map<String, List<String>> roleFocusKeywords;
    private final Map<String, QueryTypeRule> queryTypeRules;
    private final Map<String, List<String>> routingKeywords;
    private final Map<String, Double> routingScores;
    private final Map<String, List<String>> graphFieldKeywords;
    private final List<String> sqlPersonRoleKeywords;
    private final List<String> sqlListStyleKeywords;
    private final List<String> sqlCountStyleKeywords;
    private final List<String> nameStopwords;
    private final List<String> namePrefixNoise;
    private final List<String> roleKeywordMarkers;

    public EnterpriseLexicon(
            List<String> personMarkers,
            List<String> personQuestionPrefixes,
            List<String> personListTriggers,
            List<String> roleRelationTriggers,
            List<String> companySuffixes,
            int companyHintWindowChars,
            Map<String, List<String>> roleFocusKeywords,
            Map<String, QueryTypeRule> queryTypeRules,
            Map<String, List<String>> routingKeywords,
            Map<String, Double> routingScores,
            Map<String, List<String>> graphFieldKeywords,
            List<String> sqlPersonRoleKeywords,
            List<String> sqlListStyleKeywords,
            List<String> sqlCountStyleKeywords,
            List<String> nameStopwords,
            List<String> namePrefixNoise,
            List<String> roleKeywordMarkers
    ) {
        this.personMarkers = List.copyOf(personMarkers);
        this.personQuestionPrefixes = List.copyOf(personQuestionPrefixes);
        this.personListTriggers = List.copyOf(personListTriggers);
        this.roleRelationTriggers = List.copyOf(roleRelationTriggers);
        this.companySuffixes = List.copyOf(companySuffixes);
        this.companyHintWindowChars = companyHintWindowChars;
        this.roleFocusKeywords = Map.copyOf(roleFocusKeywords);
        this.queryTypeRules = Map.copyOf(queryTypeRules);
        this.routingKeywords = Map.copyOf(routingKeywords);
        this.routingScores = Map.copyOf(routingScores);
        this.graphFieldKeywords = Map.copyOf(graphFieldKeywords);
        this.sqlPersonRoleKeywords = List.copyOf(sqlPersonRoleKeywords);
        this.sqlListStyleKeywords = List.copyOf(sqlListStyleKeywords);
        this.sqlCountStyleKeywords = List.copyOf(sqlCountStyleKeywords);
        this.nameStopwords = List.copyOf(nameStopwords);
        this.namePrefixNoise = List.copyOf(namePrefixNoise);
        this.roleKeywordMarkers = List.copyOf(roleKeywordMarkers);
    }

    public static EnterpriseLexicon loadDefault(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource("qa/enterprise-lexicon.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            Map<String, QueryTypeRule> queryRules = new LinkedHashMap<>();
            JsonNode qtr = root.path("queryTypeRules");
            qtr.fieldNames().forEachRemaining(name -> {
                JsonNode rule = qtr.path(name);
                queryRules.put(name, new QueryTypeRule(
                        rule.path("requiresPerson").asBoolean(false),
                        readStringList(rule.path("anyKeywords")),
                        readStringList(rule.path("listKeywords"))
                ));
            });
            Map<String, List<String>> roleFocus = new LinkedHashMap<>();
            root.path("roleFocusKeywords").fields().forEachRemaining(e ->
                    roleFocus.put(e.getKey(), readStringList(e.getValue())));
            Map<String, List<String>> routing = new LinkedHashMap<>();
            root.path("routingKeywords").fields().forEachRemaining(e ->
                    routing.put(e.getKey(), readStringList(e.getValue())));
            Map<String, Double> scores = new LinkedHashMap<>();
            root.path("routingScores").fields().forEachRemaining(e ->
                    scores.put(e.getKey(), e.getValue().asDouble()));
            Map<String, List<String>> graphFields = new LinkedHashMap<>();
            root.path("graphFieldKeywords").fields().forEachRemaining(e ->
                    graphFields.put(e.getKey(), readStringList(e.getValue())));

            return new EnterpriseLexicon(
                    readStringList(root.path("personMarkers")),
                    readStringList(root.path("personQuestionPrefixes")),
                    readStringList(root.path("personListTriggers")),
                    readStringList(root.path("roleRelationTriggers")),
                    readStringList(root.path("companySuffixes")),
                    root.path("companyHintWindowChars").asInt(14),
                    roleFocus,
                    queryRules,
                    routing,
                    scores,
                    graphFields,
                    readStringList(root.path("sqlPersonRoleKeywords")),
                    readStringList(root.path("sqlListStyleKeywords")),
                    readStringList(root.path("sqlCountStyleKeywords")),
                    readStringList(root.path("nameStopwords")),
                    readStringList(root.path("namePrefixNoise")),
                    readStringList(root.path("roleKeywordMarkers"))
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load qa/enterprise-lexicon.json", e);
        }
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isBlank()) {
                    list.add(text);
                }
            });
        }
        return list;
    }

    public boolean containsAny(String question, List<String> keywords) {
        if (question == null || keywords == null) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && q.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public String inferRoleFocus(String question) {
        for (Map.Entry<String, List<String>> entry : roleFocusKeywords.entrySet()) {
            if (containsAny(question, entry.getValue())) {
                return entry.getKey();
            }
        }
        return "any";
    }

    public String inferQueryType(String question, String personName) {
        for (Map.Entry<String, QueryTypeRule> entry : queryTypeRules.entrySet()) {
            if (entry.getValue().matches(question, personName, this)) {
                return entry.getKey();
            }
        }
        return "";
    }

    public List<String> routingKeywordList(String category) {
        return routingKeywords.getOrDefault(category, List.of());
    }

    public double routingScore(String intent) {
        return routingScores.getOrDefault(intent, 0.5);
    }

    public List<String> graphFieldLabels(String question) {
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : graphFieldKeywords.entrySet()) {
            if (containsAny(question, entry.getValue())) {
                labels.add(entry.getKey());
            }
        }
        return labels;
    }

    public List<String> personMarkers() {
        return personMarkers;
    }

    public List<String> personQuestionPrefixes() {
        return personQuestionPrefixes;
    }

    public List<String> personListTriggers() {
        return personListTriggers;
    }

    public List<String> roleRelationTriggers() {
        return roleRelationTriggers;
    }

    public List<String> companySuffixes() {
        return companySuffixes;
    }

    public int companyHintWindowChars() {
        return companyHintWindowChars;
    }

    public List<String> sqlPersonRoleKeywords() {
        return sqlPersonRoleKeywords;
    }

    public List<String> sqlListStyleKeywords() {
        return sqlListStyleKeywords;
    }

    public List<String> sqlCountStyleKeywords() {
        return sqlCountStyleKeywords;
    }

    public List<String> nameStopwords() {
        return nameStopwords;
    }

    public List<String> namePrefixNoise() {
        return namePrefixNoise;
    }

    public List<String> roleKeywordMarkers() {
        return roleKeywordMarkers;
    }

    public record QueryTypeRule(boolean requiresPerson, List<String> anyKeywords, List<String> listKeywords) {
        boolean matches(String question, String personName, EnterpriseLexicon lexicon) {
            if (requiresPerson && (personName == null || personName.isBlank())) {
                return false;
            }
            if (!anyKeywords.isEmpty() && !lexicon.containsAny(question, anyKeywords)) {
                return false;
            }
            if (!listKeywords.isEmpty() && !lexicon.containsAny(question, listKeywords)) {
                return false;
            }
            return !anyKeywords.isEmpty() || !listKeywords.isEmpty();
        }
    }
}
