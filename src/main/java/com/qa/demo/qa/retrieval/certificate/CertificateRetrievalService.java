package com.qa.demo.qa.retrieval.certificate;

import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.config.BusinessRulesConfig.CertificateRetrievalConfig;
import com.qa.demo.qa.config.BusinessRulesConfig.QueryTypeSlotRequirement;
import com.qa.demo.qa.config.BusinessRulesConfig.StructuredQueryConfig;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.StructuredDataProvider;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalPlan;
import com.qa.demo.qa.domain.ConversationScopeSupport;
import com.qa.demo.qa.retrieval.GraphContextService;
import com.qa.demo.qa.retrieval.catalog.NeedInferenceService;
import com.qa.demo.qa.retrieval.structured.BusinessCompanyScopeResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 证照证据检索：有作用域才查库/图，禁止无主体全表扫描；优先图实例，结构化 SQL 兜底。
 */
@Service
public class CertificateRetrievalService {

    private final BusinessRulesConfig businessRules;
    private final StructuredDataProvider structuredDataProvider;
    private final GraphContextService graphContextService;
    private final BusinessCompanyScopeResolver companyScopeResolver;
    private final ConversationScopeSupport scopeSupport;
    private final NeedInferenceService needInferenceService;
    private final QaAssistantProperties properties;

    public CertificateRetrievalService(
            BusinessRulesConfig businessRules,
            StructuredDataProvider structuredDataProvider,
            GraphContextService graphContextService,
            BusinessCompanyScopeResolver companyScopeResolver,
            ConversationScopeSupport scopeSupport,
            NeedInferenceService needInferenceService,
            QaAssistantProperties properties
    ) {
        this.businessRules = businessRules;
        this.structuredDataProvider = structuredDataProvider;
        this.graphContextService = graphContextService;
        this.companyScopeResolver = companyScopeResolver;
        this.scopeSupport = scopeSupport;
        this.needInferenceService = needInferenceService;
        this.properties = properties;
    }

    public List<ContextChunk> retrieve(RetrievalPlan plan, String question, List<ContextChunk> existingEvidence) {
        IntentDecision intent = plan.intent();
        if (intent == null || !plan.personCertificateList()) {
            return List.of();
        }
        CertificateRetrievalConfig cr = businessRules.getCertificateRetrieval();
        String queryType = intent.queryType();
        if (queryType == null || queryType.isBlank()) {
            return List.of();
        }

        if ("person_certificate_list".equalsIgnoreCase(queryType)) {
            return retrievePersonStewardship(intent, cr, maxRows(intent));
        }
        if ("company_certificate".equalsIgnoreCase(queryType)) {
            if (!hasRequiredCompanyScope(intent)) {
                return List.of();
            }
            return retrieveCompanyCertificates(intent, question, cr, existingEvidence, maxRows(intent));
        }
        return List.of();
    }

    private List<ContextChunk> retrievePersonStewardship(IntentDecision intent, CertificateRetrievalConfig cr, int maxRows) {
        if (!intent.hasPersonEmployeeId()) {
            return List.of();
        }
        Set<Integer> employeeIds = Set.of(intent.personEmployeeId());
        return structuredDataProvider.queryByEmployeeIds(cr.getPersonQueryConfigId(), employeeIds, maxRows);
    }

    private List<ContextChunk> retrieveCompanyCertificates(
            IntentDecision intent,
            String question,
            CertificateRetrievalConfig cr,
            List<ContextChunk> existingEvidence,
            int maxRows
    ) {
        List<String> companyHints = intent.hasCompanyHints()
                ? intent.companyHints()
                : List.of();
        if (companyHints.isEmpty()) {
            return List.of();
        }

        List<ContextChunk> result = new ArrayList<>();
        String graphSource = cr.getGraphInstanceSource();
        if (!hasCertificateInstanceEvidence(existingEvidence, graphSource)) {
            result.addAll(graphContextService.retrieveCertificateInstances(
                    companyHints,
                    intent,
                    Math.min(maxRows, properties.getRecallGraphTopK()),
                    resolveActiveCertificatesOnly(question, intent)
            ));
        }

        if (!hasStructuredCertificateEvidence(result) && !hasStructuredCertificateEvidence(existingEvidence)) {
            Optional<Boolean> activeCompanies = scopeSupport.resolveActiveCompaniesOnly(question);
            Set<String> companyIds = companyScopeResolver.resolveCompanyIds(companyHints, activeCompanies);
            if (!companyIds.isEmpty()) {
                result.addAll(structuredDataProvider.queryByCompanyIds(
                        cr.getCompanyQueryConfigId(),
                        companyIds,
                        resolveActiveCertificatesOnly(question, intent),
                        maxRows
                ));
            }
        }
        return result;
    }

    private boolean hasRequiredCompanyScope(IntentDecision intent) {
        for (QueryTypeSlotRequirement req : businessRules.getIntentRouting().getQueryTypeSlotRequirements()) {
            if (req.getQueryType() != null
                    && "company_certificate".equalsIgnoreCase(req.getQueryType())
                    && req.isRequiresCompany()) {
                return intent.hasCompanyHints();
            }
        }
        return intent.hasCompanyHints();
    }

    private boolean resolveActiveCertificatesOnly(String question, IntentDecision intent) {
        InformationNeed need = needInferenceService.infer(question, intent);
        if (need != null
                && "certificate".equalsIgnoreCase(need.facet())
                && needInferenceTargetsActiveLicenseStatus(need)) {
            return true;
        }
        List<String> activeValues = businessRules.getCertificateRetrieval().getActiveCertificateStatusValues();
        return activeValues != null && !activeValues.isEmpty()
                && need != null
                && "license_status_catalog".equals(need.reason());
    }

    private boolean needInferenceTargetsActiveLicenseStatus(InformationNeed need) {
        if (need == null || need.reason() == null) {
            return false;
        }
        return need.reason().contains("license_status_catalog")
                || need.reason().contains("inference_rule:license_status");
    }

    private static boolean hasCertificateInstanceEvidence(List<ContextChunk> evidence, String graphSource) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        for (ContextChunk chunk : evidence) {
            if (chunk != null && graphSource.equals(chunk.source())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStructuredCertificateEvidence(List<ContextChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        Optional<StructuredQueryConfig> companyConfig = structuredDataProvider.findConfig(
                businessRules.getCertificateRetrieval().getCompanyQueryConfigId()
        );
        String prefix = companyConfig
                .map(c -> "mysql-structured-" + c.getId())
                .orElse("mysql-structured-certificate_by_company");
        for (ContextChunk chunk : evidence) {
            if (chunk != null && chunk.source() != null && chunk.source().startsWith("mysql-structured-")) {
                if (chunk.source().equals(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int maxRows(IntentDecision intent) {
        int base = Math.max(properties.getRecallGraphTopK() * 8, 64);
        if (intent != null && intent.hasCompanyHints()) {
            return Math.min(Math.max(base, intent.companyHints().size() * 12), 256);
        }
        return base;
    }
}
