package com.qa.demo.qa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.domain.PersonNameParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * 业务规则配置加载器。
 * <p>
 * 将 business-rules.json 加载为 {@link BusinessRulesConfig} 对象，
 * 供 {@link ScenarioRuleEngine} 等组件使用。
 */
@Configuration
public class BusinessRulesConfiguration {

    @Value("${qa.business-rules.path:classpath:qa/business-rules.json}")
    private Resource businessRulesPath;

    @Bean
    public BusinessRulesConfig businessRulesConfig(ObjectMapper objectMapper) throws IOException {
        BusinessRulesConfig config = new BusinessRulesConfig();

        try (InputStream is = businessRulesPath.getInputStream()) {
            JsonNode root = objectMapper.readTree(is);

            // 加载 intentRules
            JsonNode intentRulesNode = root.get("intentRules");
            if (intentRulesNode != null) {
                loadIntentRules(config, intentRulesNode);
            }

            // 加载 dataSources
            JsonNode dataSourcesNode = root.get("dataSources");
            if (dataSourcesNode != null) {
                loadDataSources(config, dataSourcesNode);
            }

            // 加载 correctionRules
            JsonNode correctionRulesNode = root.get("correctionRules");
            if (correctionRulesNode != null && correctionRulesNode.isArray()) {
                for (JsonNode ruleNode : correctionRulesNode) {
                    BusinessRulesConfig.CorrectionRule rule = new BusinessRulesConfig.CorrectionRule();
                    rule.setId(ruleNode.get("id") != null ? ruleNode.get("id").asText() : "");
                    rule.setDescription(ruleNode.get("description") != null ? ruleNode.get("description").asText() : "");
                    rule.setEntityPattern(ruleNode.get("entityPattern") != null ? ruleNode.get("entityPattern").asText() : "");
                    rule.setEntityType(ruleNode.get("entityType") != null ? ruleNode.get("entityType").asText() : "");

                    if (ruleNode.get("prefixPatterns") != null && ruleNode.get("prefixPatterns").isArray()) {
                        for (JsonNode p : ruleNode.get("prefixPatterns")) {
                            rule.getPrefixPatterns().add(p.asText());
                        }
                    }
                    if (ruleNode.get("suffixes") != null && ruleNode.get("suffixes").isArray()) {
                        for (JsonNode s : ruleNode.get("suffixes")) {
                            rule.getSuffixes().add(s.asText());
                        }
                    }
                    if (ruleNode.get("relatedKeywords") != null && ruleNode.get("relatedKeywords").isArray()) {
                        for (JsonNode k : ruleNode.get("relatedKeywords")) {
                            rule.getRelatedKeywords().add(k.asText());
                        }
                    }
                    config.getCorrectionRules().add(rule);
                }
            }

            // 加载 retrievalThresholds
            JsonNode thresholdsNode = root.get("retrievalThresholds");
            if (thresholdsNode != null) {
                loadRetrievalThresholds(config, thresholdsNode);
            }

            // 加载 outputContracts
            JsonNode outputContractsNode = root.get("outputContracts");
            if (outputContractsNode != null && outputContractsNode.isObject()) {
                outputContractsNode.fields().forEachRemaining(entry ->
                        config.getOutputContracts().put(entry.getKey(), entry.getValue().asText())
                );
            }
        }

        // 初始化敬称后缀模式
        PersonNameParser.setHonorificSuffixPattern(config.getIntentRules().getPersonNamePatterns().getHonorificSuffixPattern());

        return config;
    }

    private void loadIntentRules(BusinessRulesConfig config, JsonNode node) {
        JsonNode personNamePatternsNode = node.get("personNamePatterns");
        if (personNamePatternsNode != null) {
            BusinessRulesConfig.PersonNamePatterns patterns = config.getIntentRules().getPersonNamePatterns();

            if (personNamePatternsNode.get("beforeAction") != null && personNamePatternsNode.get("beforeAction").isArray()) {
                personNamePatternsNode.get("beforeAction").forEach(p -> patterns.getBeforeAction().add(p.asText()));
            }
            if (personNamePatternsNode.get("beforeResign") != null && personNamePatternsNode.get("beforeResign").isArray()) {
                personNamePatternsNode.get("beforeResign").forEach(p -> patterns.getBeforeResign().add(p.asText()));
            }
            if (personNamePatternsNode.get("leadingName") != null && personNamePatternsNode.get("leadingName").isArray()) {
                personNamePatternsNode.get("leadingName").forEach(p -> patterns.getLeadingName().add(p.asText()));
            }
            if (personNamePatternsNode.get("pronounBlocklist") != null && personNamePatternsNode.get("pronounBlocklist").isArray()) {
                personNamePatternsNode.get("pronounBlocklist").forEach(p -> patterns.getPronounBlocklist().add(p.asText()));
            }
            if (personNamePatternsNode.get("honorificSuffixes") != null && personNamePatternsNode.get("honorificSuffixes").isArray()) {
                personNamePatternsNode.get("honorificSuffixes").forEach(p -> patterns.getHonorificSuffixes().add(p.asText()));
            }
        }

        JsonNode queryTypeConditionsNode = node.get("queryTypeConditions");
        if (queryTypeConditionsNode != null && queryTypeConditionsNode.isArray()) {
            for (JsonNode conditionNode : queryTypeConditionsNode) {
                BusinessRulesConfig.QueryTypeCondition condition = new BusinessRulesConfig.QueryTypeCondition();
                condition.setId(conditionNode.get("id") != null ? conditionNode.get("id").asText() : "");
                condition.setDescription(conditionNode.get("description") != null ? conditionNode.get("description").asText() : "");
                condition.setQueryType(conditionNode.get("queryType") != null ? conditionNode.get("queryType").asText() : "");
                condition.setRequiresPerson(conditionNode.get("requiresPerson") != null && conditionNode.get("requiresPerson").asBoolean());

                if (conditionNode.get("keywords") != null && conditionNode.get("keywords").isArray()) {
                    conditionNode.get("keywords").forEach(k -> condition.getKeywords().add(k.asText()));
                }
                if (conditionNode.get("weakQueryTypes") != null && conditionNode.get("weakQueryTypes").isArray()) {
                    conditionNode.get("weakQueryTypes").forEach(w -> condition.getWeakQueryTypes().add(w.asText()));
                }
                config.getIntentRules().getQueryTypeConditions().add(condition);
            }
        }
    }

    private void loadDataSources(BusinessRulesConfig config, JsonNode node) {
        JsonNode structuredQueriesNode = node.get("structuredQueries");
        if (structuredQueriesNode != null && structuredQueriesNode.isArray()) {
            for (JsonNode queryNode : structuredQueriesNode) {
                BusinessRulesConfig.StructuredQueryConfig queryConfig = new BusinessRulesConfig.StructuredQueryConfig();
                queryConfig.setId(queryNode.get("id") != null ? queryNode.get("id").asText() : "");
                queryConfig.setDescription(queryNode.get("description") != null ? queryNode.get("description").asText() : "");
                queryConfig.setTable(queryNode.get("table") != null ? queryNode.get("table").asText() : "");
                queryConfig.setDeleteflagColumn(queryNode.has("deleteflagColumn") ? queryNode.get("deleteflagColumn").asText() : "deleteflag");
                queryConfig.setEntityIdColumn(queryNode.has("entityIdColumn") ? queryNode.get("entityIdColumn").asText() : "id");

                if (queryNode.get("displayNameColumns") != null && queryNode.get("displayNameColumns").isArray()) {
                    queryNode.get("displayNameColumns").forEach(c -> queryConfig.getDisplayNameColumns().add(c.asText()));
                }
                if (queryNode.get("roleColumns") != null && queryNode.get("roleColumns").isArray()) {
                    for (JsonNode rcNode : queryNode.get("roleColumns")) {
                        BusinessRulesConfig.RoleColumnConfig rc = new BusinessRulesConfig.RoleColumnConfig();
                        rc.setColumn(rcNode.get("column") != null ? rcNode.get("column").asText() : "");
                        rc.setLabel(rcNode.get("label") != null ? rcNode.get("label").asText() : "");
                        rc.setType(rcNode.has("type") ? rcNode.get("type").asText() : "");
                        queryConfig.getRoleColumns().add(rc);
                    }
                }
                if (queryNode.get("enumMappings") != null && queryNode.get("enumMappings").isObject()) {
                    queryNode.get("enumMappings").fields().forEachRemaining(e ->
                            queryConfig.getEnumMappings().put(e.getKey(), e.getValue().asText())
                    );
                }
                if (queryNode.get("statusNormalization") != null && queryNode.get("statusNormalization").isObject()) {
                    queryNode.get("statusNormalization").fields().forEachRemaining(e ->
                            queryConfig.getStatusNormalization().put(e.getKey(), e.getValue().asText())
                    );
                }
                if (queryNode.get("supplementalTables") != null && queryNode.get("supplementalTables").isArray()) {
                    queryNode.get("supplementalTables").forEach(t -> queryConfig.getSupplementalTables().add(t.asText()));
                }
                config.getDataSources().getStructuredQueries().add(queryConfig);
            }
        }
    }

    private void loadRetrievalThresholds(BusinessRulesConfig config, JsonNode node) {
        JsonNode sourceThresholdsNode = node.get("sourceThresholds");
        if (sourceThresholdsNode != null && sourceThresholdsNode.isArray()) {
            for (JsonNode thresholdNode : sourceThresholdsNode) {
                BusinessRulesConfig.SourceThreshold threshold = new BusinessRulesConfig.SourceThreshold();
                threshold.setSource(thresholdNode.get("source") != null ? thresholdNode.get("source").asText() : "");
                threshold.setQueryType(thresholdNode.get("queryType") != null ? thresholdNode.get("queryType").asText() : "");
                threshold.setMinCount(thresholdNode.has("minCount") ? thresholdNode.get("minCount").asInt() : Integer.MAX_VALUE);
                threshold.setDescription(thresholdNode.get("description") != null ? thresholdNode.get("description").asText() : "");
                config.getRetrievalThresholds().getSourceThresholds().add(threshold);
            }
        }
    }
}