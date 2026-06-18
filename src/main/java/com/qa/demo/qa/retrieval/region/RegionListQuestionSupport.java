package com.qa.demo.qa.retrieval.region;

import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.retrieval.structured.RegionResolverService;

import java.util.Locale;

/**
 * 无主体约束的地区公司列表问句识别（如「北京的公司有哪些」）。
 */
public final class RegionListQuestionSupport {

    private RegionListQuestionSupport() {
    }

    public static boolean isRegionCompanyListNeed(InformationNeed need) {
        return need != null
                && need.hasFacet()
                && "profile".equalsIgnoreCase(need.facet())
                && InformationNeed.GRANULARITY_INSTANCE.equalsIgnoreCase(need.granularity())
                && need.reason() != null
                && need.reason().startsWith("inference:region_company_list");
    }

    public static InformationNeed regionCompanyNeed() {
        return new InformationNeed(
                "profile",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.86,
                "inference:region_company_list"
        );
    }

    public static boolean isRegionCompanyList(String question, RegionResolverService regionResolver) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (!hasCompanyContext(lower)) {
            return false;
        }
        if (!hasListIntent(lower)) {
            return false;
        }
        if (isTypeCatalogWording(lower)) {
            return false;
        }
        if (regionResolver == null) {
            return false;
        }
        var resolved = regionResolver.extractRegionCodes(question);
        if (resolved == null) {
            return false;
        }
        boolean hasCodes = resolved.codes() != null && !resolved.codes().isEmpty();
        boolean hasNames = resolved.matchedNames() != null && !resolved.matchedNames().isEmpty();
        return hasCodes || hasNames;
    }

    private static boolean hasCompanyContext(String lower) {
        return lower.contains("公司") || lower.contains("企业");
    }

    private static boolean hasListIntent(String lower) {
        return lower.contains("有哪些")
                || lower.contains("哪些公司")
                || lower.contains("哪些企业")
                || lower.contains("列出");
    }

    private static boolean isTypeCatalogWording(String lower) {
        return lower.contains("类型")
                || lower.contains("种类")
                || lower.contains("状态")
                || lower.contains("取值");
    }
}
