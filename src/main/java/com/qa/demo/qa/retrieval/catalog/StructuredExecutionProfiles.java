package com.qa.demo.qa.retrieval.catalog;

import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalExecutionProfile;
import com.qa.demo.qa.retrieval.certificate.CertificateListQuestionSupport;

/**
 * 当 DB 配置 bundle 未包含最新 execution profile 时的 classpath 兜底（与 retrieval-catalog.json 对齐）。
 */
public final class StructuredExecutionProfiles {

    private static final RetrievalExecutionProfile ROLE_LIST = new RetrievalExecutionProfile(
            true,
            false,
            "dedicated_list_sql",
            true,
            false,
            "",
            true,
            true
    );

    private static final RetrievalExecutionProfile CERTIFICATE_PERSON = new RetrievalExecutionProfile(
            false,
            true,
            "dedicated_certificate_sql",
            true,
            true,
            "",
            true,
            true
    );

    private static final RetrievalExecutionProfile CERTIFICATE_GLOBAL = new RetrievalExecutionProfile(
            false,
            true,
            "dedicated_certificate_global_sql",
            true,
            true,
            "",
            true,
            false
    );

    private StructuredExecutionProfiles() {
    }

    public static RetrievalExecutionProfile forNeed(InformationNeed need, IntentDecision intent) {
        if (need == null || !need.hasFacet()) {
            return RetrievalExecutionProfile.DEFAULT;
        }
        if (CertificateListQuestionSupport.isGlobalCertificateListNeed(need)) {
            return CERTIFICATE_GLOBAL;
        }
        if (isRoleListNeed(need)) {
            return ROLE_LIST;
        }
        if (isPersonCertificateListNeed(need, intent)) {
            return CERTIFICATE_PERSON;
        }
        return RetrievalExecutionProfile.DEFAULT;
    }

    public static boolean isDedicated(RetrievalExecutionProfile profile) {
        return profile != null
                && (profile.dedicatedListPath() || profile.dedicatedCertificatePath());
    }

    private static boolean isRoleListNeed(InformationNeed need) {
        return "role".equalsIgnoreCase(need.facet().trim())
                && "list".equalsIgnoreCase(nullToEmpty(need.granularity()));
    }

    private static boolean isPersonCertificateListNeed(InformationNeed need, IntentDecision intent) {
        if (!"certificate".equalsIgnoreCase(need.facet().trim())) {
            return false;
        }
        if (!InformationNeed.GRANULARITY_INSTANCE.equalsIgnoreCase(nullToEmpty(need.granularity()))) {
            return false;
        }
        if (!need.listExpected()) {
            return false;
        }
        String reason = need.reason() == null ? "" : need.reason();
        if (reason.contains("pronoun_certificate")) {
            return true;
        }
        return intent != null && intent.hasPersonFocus();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
