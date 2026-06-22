package com.qa.demo.qa.retrieval.certificate;

import com.qa.demo.qa.core.InformationNeed;

import java.util.Locale;

/**
 * 无主体约束的证照实例列表问句识别（与 type_catalog「有哪些类型」区分）。
 */
public final class CertificateListQuestionSupport {

    private CertificateListQuestionSupport() {
    }

    public static boolean isGlobalCertificateListNeed(InformationNeed need) {
        return need != null
                && need.hasFacet()
                && "certificate".equalsIgnoreCase(need.facet())
                && InformationNeed.GRANULARITY_INSTANCE.equalsIgnoreCase(need.granularity())
                && need.reason() != null
                && (need.reason().startsWith("inference:global_certificate_list")
                || need.reason().startsWith("inference_rule:global_certificate_list"));
    }

    public static InformationNeed globalCertificateNeed() {
        return new InformationNeed(
                "certificate",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.88,
                "inference_rule:global_certificate_list"
        );
    }

    public static boolean isUnscopedGlobalCertificateList(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (isTypeCatalogWording(lower)) {
            return false;
        }
        if (!hasCertificateContext(lower)) {
            return false;
        }
        if (!hasListIntent(lower)) {
            return false;
        }
        return hasGlobalScope(lower);
    }

    private static boolean isTypeCatalogWording(String lower) {
        return lower.contains("类型")
                || lower.contains("种类")
                || lower.contains("什么类型")
                || lower.contains("哪些类型")
                || lower.contains("有哪些类型")
                || (lower.contains("状态") && lower.contains("取值"));
    }

    private static boolean hasCertificateContext(String lower) {
        return lower.contains("证照")
                || lower.contains("资质")
                || lower.contains("许可证")
                || lower.contains("执照")
                || lower.contains("证书")
                || lower.contains("备案");
    }

    private static boolean hasListIntent(String lower) {
        return lower.contains("有哪些")
                || lower.contains("列出")
                || lower.contains("哪些证");
    }

    private static boolean hasGlobalScope(String lower) {
        return lower.contains("所有")
                || lower.contains("全部")
                || lower.contains("全库")
                || lower.contains("全局");
    }
}
