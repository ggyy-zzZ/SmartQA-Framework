package com.qa.demo.qa.docvec.routing;

import com.qa.demo.qa.domain.CertificateSealEnumCatalog;
import org.springframework.stereotype.Component;

/**
 * 将 LLM 槽位规范化为可执行参数（证照类型 ID 等）。
 */
@Component
public class DocVecSlotResolver {

    private final CertificateSealEnumCatalog enumCatalog;

    public DocVecSlotResolver(CertificateSealEnumCatalog enumCatalog) {
        this.enumCatalog = enumCatalog;
    }

    public int resolveCertificateTypeId(String certificateTypeName, String question) {
        String label = certificateTypeName == null ? "" : certificateTypeName.trim();
        if (label.isBlank() && question != null) {
            var mentioned = enumCatalog.certificateLabelsMentionedIn(question);
            if (!mentioned.isEmpty()) {
                label = mentioned.get(0);
            }
        }
        if (label.isBlank()) {
            return -1;
        }
        return enumCatalog.resolveCertificateTypeNumericId(label);
    }

    public String defaultRoleLabel(String roleLabel) {
        if (roleLabel != null && !roleLabel.isBlank()) {
            return roleLabel.trim();
        }
        return "法定代表人";
    }

    public String normalizePersonName(String personName) {
        return personName == null ? "" : personName.trim();
    }

    public String normalizeRegion(String regionKeyword) {
        return regionKeyword == null ? "" : regionKeyword.trim();
    }

    public String normalizeCompanyHint(String companyNameHint, String question) {
        if (companyNameHint != null && !companyNameHint.isBlank()) {
            return companyNameHint.trim();
        }
        return question == null ? "" : question.trim();
    }

    public boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public String mergePersonFromSession(String fromLlm, String fromSession) {
        if (!isBlank(fromLlm)) {
            return fromLlm.trim();
        }
        return fromSession == null ? "" : fromSession.trim();
    }

    public String mergeRoleFromSession(String fromLlm, String fromSession) {
        String role = !isBlank(fromLlm) ? fromLlm.trim() : (fromSession == null ? "" : fromSession.trim());
        return role.isBlank() ? "法定代表人" : role;
    }
}
