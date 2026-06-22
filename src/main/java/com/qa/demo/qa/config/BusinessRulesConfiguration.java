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

            JsonNode filterFieldNode = root.get("filterFieldCoverageRules");
            if (filterFieldNode != null && filterFieldNode.isArray()) {
                loadFilterFieldCoverageRules(config, filterFieldNode);
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
                copyStringArray(queryNode, "boundStrategies", queryConfig.getBoundStrategies());
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
        copyStringArray(node, "catalogQuestionMarkers", scope.getCatalogQuestionMarkers());
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
        if (node.get("structuredListStrategies") != null && node.get("structuredListStrategies").isArray()) {
            node.get("structuredListStrategies").forEach(t -> routing.getStructuredListStrategies().add(t.asText()));
        }
        if (node.get("defaultIntentByStrategy") != null && node.get("defaultIntentByStrategy").isObject()) {
            node.get("defaultIntentByStrategy").fields().forEachRemaining(e ->
                    routing.getDefaultIntentByStrategy().put(e.getKey(), e.getValue().asText())
            );
        }
        if (node.get("compiledDocumentKeywords") != null && node.get("compiledDocumentKeywords").isArray()) {
            node.get("compiledDocumentKeywords").forEach(k -> routing.getCompiledDocumentKeywords().add(k.asText()));
        }
        if (node.get("followUpReferenceMarkers") != null && node.get("followUpReferenceMarkers").isArray()) {
            node.get("followUpReferenceMarkers").forEach(m -> routing.getFollowUpReferenceMarkers().add(m.asText()));
        }
        if (node.get("strategySlotRequirements") != null && node.get("strategySlotRequirements").isArray()) {
            for (JsonNode reqNode : node.get("strategySlotRequirements")) {
                BusinessRulesConfig.StrategySlotRequirement req = new BusinessRulesConfig.StrategySlotRequirement();
                req.setRetrievalStrategy(reqNode.path("retrievalStrategy").asText(""));
                req.setRequiresPerson(reqNode.path("requiresPerson").asBoolean(false));
                req.setRequiresCompany(reqNode.path("requiresCompany").asBoolean(false));
                req.setRequiresRoleFocus(reqNode.path("requiresRoleFocus").asBoolean(false));
                routing.getStrategySlotRequirements().add(req);
            }
        }
    }

    private void loadAnswerGate(BusinessRulesConfig config, JsonNode node) {
        JsonNode rulesNode = node.get("requiredEvidenceByNeed");
        if (rulesNode == null || !rulesNode.isArray()) {
            return;
        }
        for (JsonNode ruleNode : rulesNode) {
            BusinessRulesConfig.AnswerGateNeedRule rule = new BusinessRulesConfig.AnswerGateNeedRule();
            rule.setFacet(ruleNode.path("facet").asText(""));
            rule.setGranularity(ruleNode.path("granularity").asText(""));
            rule.setRequiresPerson(ruleNode.path("requiresPerson").asBoolean(false));
            if (ruleNode.get("schemaIds") != null && ruleNode.get("schemaIds").isArray()) {
                ruleNode.get("schemaIds").forEach(s -> rule.getSchemaIds().add(s.asText()));
            }
            config.getAnswerGate().getRequiredEvidenceByNeed().add(rule);
        }
    }

    private void loadRetrievalThresholds(BusinessRulesConfig config, JsonNode node) {
        JsonNode sourceThresholdsNode = node.get("sourceThresholds");
        if (sourceThresholdsNode != null && sourceThresholdsNode.isArray()) {
            for (JsonNode thresholdNode : sourceThresholdsNode) {
                BusinessRulesConfig.SourceThreshold threshold = new BusinessRulesConfig.SourceThreshold();
                threshold.setSource(thresholdNode.get("source") != null ? thresholdNode.get("source").asText() : "");
                threshold.setRetrievalStrategy(thresholdNode.get("retrievalStrategy") != null
                        ? thresholdNode.get("retrievalStrategy").asText()
                        : thresholdNode.path("queryType").asText(""));
                threshold.setMinCount(thresholdNode.has("minCount") ? thresholdNode.get("minCount").asInt() : Integer.MAX_VALUE);
                threshold.setDescription(thresholdNode.get("description") != null ? thresholdNode.get("description").asText() : "");
                config.getRetrievalThresholds().getSourceThresholds().add(threshold);
            }
        }
    }

    private void loadFilterFieldCoverageRules(BusinessRulesConfig config, JsonNode arrayNode) {
        for (JsonNode node : arrayNode) {
            BusinessRulesConfig.FilterFieldCoverageRule rule = new BusinessRulesConfig.FilterFieldCoverageRule();
            rule.setId(node.path("id").asText(""));
            rule.setDisplayLabel(node.path("displayLabel").asText(""));
            copyStringArray(node, "questionAnyKeywords", rule.getQuestionAnyKeywords());
            copyStringArray(node, "filterIntentKeywords", rule.getFilterIntentKeywords());
            copyStringArray(node, "snippetMarkers", rule.getSnippetMarkers());
            copyStringArray(node, "sourceColumns", rule.getSourceColumns());
            config.getFilterFieldCoverageRules().add(rule);
        }
    }
}