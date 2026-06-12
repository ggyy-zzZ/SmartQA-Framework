package com.qa.demo.qa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.domain.PersonNameParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;

/**
 * 业务规则配置加载器。
 * <p>
 * 将 business-rules.json 加载为 {@link BusinessRulesConfig} 对象，
 * 供 {@link ScenarioRuleEngine} 等组件使用。
 */
@Configuration
public class BusinessRulesConfiguration {

    @Bean
    public BusinessRulesConfig businessRulesConfig(AssistantConfigJsonLoader configLoader) throws IOException {
        BusinessRulesConfig config = new BusinessRulesConfig();
        JsonNode root = configLoader.readTree("business-rules");

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

            JsonNode certificateRetrievalNode = root.get("certificateRetrieval");
            if (certificateRetrievalNode != null) {
                loadCertificateRetrieval(config, certificateRetrievalNode);
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

            JsonNode conversationScopeNode = root.get("conversationScope");
            if (conversationScopeNode != null) {
                loadConversationScope(config, conversationScopeNode);
            }

            JsonNode intentRoutingNode = root.get("intentRouting");
            if (intentRoutingNode != null) {
                loadIntentRouting(config, intentRoutingNode);
            }

            JsonNode answerGateNode = root.get("answerGate");
            if (answerGateNode != null) {
                loadAnswerGate(config, answerGateNode);
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
                if (queryNode.has("scopeKind")) {
                    queryConfig.setScopeKind(queryNode.get("scopeKind").asText());
                }
                if (queryNode.has("scopeColumn")) {
                    queryConfig.setScopeColumn(queryNode.get("scopeColumn").asText());
                }
                copyStringArray(queryNode, "boundQueryTypes", queryConfig.getBoundQueryTypes());
                copyStringArray(queryNode, "statusActiveValues", queryConfig.getStatusActiveValues());
                if (queryNode.get("projections") != null && queryNode.get("projections").isArray()) {
                    for (JsonNode projNode : queryNode.get("projections")) {
                        BusinessRulesConfig.ProjectionColumnConfig proj = new BusinessRulesConfig.ProjectionColumnConfig();
                        proj.setColumn(projNode.path("column").asText(""));
                        proj.setLabel(projNode.path("label").asText(""));
                        if (projNode.has("enumField")) {
                            proj.setEnumField(projNode.get("enumField").asText());
                        }
                        queryConfig.getProjections().add(proj);
                    }
                }

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

    private void loadCertificateRetrieval(BusinessRulesConfig config, JsonNode node) {
        BusinessRulesConfig.CertificateRetrievalConfig cr = config.getCertificateRetrieval();
        if (node.has("personQueryConfigId")) {
            cr.setPersonQueryConfigId(node.get("personQueryConfigId").asText());
        }
        if (node.has("companyQueryConfigId")) {
            cr.setCompanyQueryConfigId(node.get("companyQueryConfigId").asText());
        }
        if (node.has("graphInstanceSource")) {
            cr.setGraphInstanceSource(node.get("graphInstanceSource").asText());
        }
        if (node.has("forbidUnscopedGlobalScan")) {
            cr.setForbidUnscopedGlobalScan(node.get("forbidUnscopedGlobalScan").asBoolean(true));
        }
        copyStringArray(node, "activeCompanyOperatingStatusCodes", cr.getActiveCompanyOperatingStatusCodes());
        copyStringArray(node, "activeCertificateStatusValues", cr.getActiveCertificateStatusValues());
    }

    private void loadConversationScope(BusinessRulesConfig config, JsonNode node) {
        BusinessRulesConfig.ConversationScope scope = config.getConversationScope();
        copyStringArray(node, "breakContextPhrases", scope.getBreakContextPhrases());
        copyStringArray(node, "globalListMarkers", scope.getGlobalListMarkers());
        copyStringArray(node, "globalListContextKeywords", scope.getGlobalListContextKeywords());
        copyStringArray(node, "continuationMarkers", scope.getContinuationMarkers());
        copyStringArray(node, "continuationExcludePatterns", scope.getContinuationExcludePatterns());
        if (node.has("continuationMaxLength")) {
            scope.setContinuationMaxLength(node.get("continuationMaxLength").asInt(36));
        }
        JsonNode statusNode = node.get("operatingStatus");
        if (statusNode != null) {
            BusinessRulesConfig.OperatingStatusScopeRules rules = scope.getOperatingStatus();
            copyStringArray(statusNode, "activeMarkers", rules.getActiveMarkers());
            copyStringArray(statusNode, "inactiveMarkers", rules.getInactiveMarkers());
            copyStringArray(statusNode, "negationPrefixes", rules.getNegationPrefixes());
            copyStringArray(statusNode, "inactivePhrases", rules.getInactivePhrases());
            copyStringArray(statusNode, "certificateContextKeywords", rules.getCertificateContextKeywords());
        }
    }

    private static void copyStringArray(JsonNode parent, String field, List<String> target) {
        JsonNode arr = parent.get(field);
        if (arr == null || !arr.isArray()) {
            return;
        }
        arr.forEach(item -> target.add(item.asText()));
    }

    private void loadIntentRouting(BusinessRulesConfig config, JsonNode node) {
        BusinessRulesConfig.IntentRouting routing = config.getIntentRouting();
        if (node.get("filterRulePrefixes") != null && node.get("filterRulePrefixes").isArray()) {
            node.get("filterRulePrefixes").forEach(p -> routing.getFilterRulePrefixes().add(p.asText()));
        }
        if (node.get("structuredListQueryTypes") != null && node.get("structuredListQueryTypes").isArray()) {
            node.get("structuredListQueryTypes").forEach(t -> routing.getStructuredListQueryTypes().add(t.asText()));
        }
        if (node.get("certificateQueryTypes") != null && node.get("certificateQueryTypes").isArray()) {
            node.get("certificateQueryTypes").forEach(t -> routing.getCertificateQueryTypes().add(t.asText()));
        }
        if (node.get("defaultIntentByQueryType") != null && node.get("defaultIntentByQueryType").isObject()) {
            node.get("defaultIntentByQueryType").fields().forEachRemaining(e ->
                    routing.getDefaultIntentByQueryType().put(e.getKey(), e.getValue().asText())
            );
        }
        if (node.get("followUpReferenceMarkers") != null && node.get("followUpReferenceMarkers").isArray()) {
            node.get("followUpReferenceMarkers").forEach(m -> routing.getFollowUpReferenceMarkers().add(m.asText()));
        }
        if (node.get("queryTypeSlotRequirements") != null && node.get("queryTypeSlotRequirements").isArray()) {
            for (JsonNode reqNode : node.get("queryTypeSlotRequirements")) {
                BusinessRulesConfig.QueryTypeSlotRequirement req = new BusinessRulesConfig.QueryTypeSlotRequirement();
                req.setQueryType(reqNode.path("queryType").asText(""));
                req.setRequiresPerson(reqNode.path("requiresPerson").asBoolean(false));
                req.setRequiresCompany(reqNode.path("requiresCompany").asBoolean(false));
                req.setRequiresRoleFocus(reqNode.path("requiresRoleFocus").asBoolean(false));
                routing.getQueryTypeSlotRequirements().add(req);
            }
        }
    }

    private void loadAnswerGate(BusinessRulesConfig config, JsonNode node) {
        JsonNode rulesNode = node.get("requiredEvidenceByQueryType");
        if (rulesNode == null || !rulesNode.isArray()) {
            return;
        }
        for (JsonNode ruleNode : rulesNode) {
            BusinessRulesConfig.AnswerGateQueryTypeRule rule = new BusinessRulesConfig.AnswerGateQueryTypeRule();
            rule.setQueryType(ruleNode.path("queryType").asText(""));
            if (ruleNode.get("schemaIds") != null && ruleNode.get("schemaIds").isArray()) {
                ruleNode.get("schemaIds").forEach(s -> rule.getSchemaIds().add(s.asText()));
            }
            config.getAnswerGate().getRequiredEvidenceByQueryType().add(rule);
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