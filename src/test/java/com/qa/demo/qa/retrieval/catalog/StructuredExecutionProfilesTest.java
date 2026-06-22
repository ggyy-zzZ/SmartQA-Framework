package com.qa.demo.qa.retrieval.catalog;

import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.core.RetrievalExecutionProfile;
import com.qa.demo.qa.retrieval.certificate.CertificateListQuestionSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredExecutionProfilesTest {

    @Test
    void roleListNeedUsesDedicatedListSql() {
        InformationNeed need = new InformationNeed("role", "list", true, 0.9, "inference_rule:role_list");
        RetrievalExecutionProfile profile = StructuredExecutionProfiles.forNeed(need, null);
        assertTrue(profile.dedicatedListPath());
        assertEquals("dedicated_list_sql", profile.routeLabel());
    }

    @Test
    void globalCertificateUsesDedicatedGlobalSql() {
        InformationNeed need = CertificateListQuestionSupport.globalCertificateNeed();
        RetrievalExecutionProfile profile = StructuredExecutionProfiles.forNeed(need, null);
        assertTrue(profile.dedicatedCertificatePath());
        assertEquals("dedicated_certificate_global_sql", profile.routeLabel());
    }

    @Test
    void personCertificateUsesDedicatedSql() {
        InformationNeed need = new InformationNeed(
                "certificate",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.9,
                "inference_rule:certificate_instance"
        );
        IntentDecision intent = new IntentDecision(
                "hybrid", 0.9, "rule", "张三", List.of(), "any", null, "structured_list");
        RetrievalExecutionProfile profile = StructuredExecutionProfiles.forNeed(need, intent);
        assertTrue(profile.dedicatedCertificatePath());
        assertEquals("dedicated_certificate_sql", profile.routeLabel());
    }
}
