package com.qa.demo.qa.retrieval.parent;

import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import com.qa.demo.qa.core.InformationNeed;

import java.util.Locale;

/**
 * 母公司/总部下属主体列表问句识别（如「总部下有哪些存续的主体」），无业务硬编码。
 */
public final class ParentScopedCompanyListSupport {

    private ParentScopedCompanyListSupport() {
    }

    public static boolean isParentScopedCompanyListNeed(InformationNeed need) {
        return need != null
                && need.hasFacet()
                && "profile".equalsIgnoreCase(need.facet())
                && InformationNeed.GRANULARITY_INSTANCE.equalsIgnoreCase(need.granularity())
                && need.reason() != null
                && need.reason().startsWith("inference:parent_company_list");
    }

    public static InformationNeed parentCompanyListNeed() {
        return new InformationNeed(
                "profile",
                InformationNeed.GRANULARITY_INSTANCE,
                true,
                0.88,
                "inference:parent_company_list"
        );
    }

    public static boolean isParentScopedCompanyList(
            String question,
            EnterpriseCanonicalFactsRegistry canonicalFactsRegistry
    ) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (!hasListIntent(lower)) {
            return false;
        }
        if (!hasEntityContext(lower)) {
            return false;
        }
        if (isTypeCatalogOnlyWording(lower)) {
            return false;
        }
        if (!hasParentScope(lower)) {
            return false;
        }
        return canonicalFactsRegistry != null && canonicalFactsRegistry.hasResolvableCompanyAnchor(question);
    }

    private static boolean hasEntityContext(String lower) {
        return lower.contains("主体") || lower.contains("公司") || lower.contains("企业");
    }

    private static boolean hasListIntent(String lower) {
        return lower.contains("有哪些") || lower.contains("哪些公司") || lower.contains("哪些企业")
                || lower.contains("列出") || lower.contains("清单");
    }

    private static boolean hasParentScope(String lower) {
        if (lower.contains("下属") || lower.contains("子公司") || lower.contains("分支机构")) {
            return true;
        }
        if ((lower.contains("总部") || lower.contains("母公司") || lower.contains("总公司"))
                && lower.contains("下")) {
            return true;
        }
        return false;
    }

    /** 排除纯 type_catalog 问法，但允许「存续的主体」这类实例列表 + 属性筛选。 */
    private static boolean isTypeCatalogOnlyWording(String lower) {
        boolean typeCatalogCue = lower.contains("哪些种类") || lower.contains("哪些类型")
                || lower.contains("什么类型") || lower.contains("有哪些类型")
                || lower.contains("有哪些种类") || lower.contains("取值");
        return typeCatalogCue && !lower.contains("主体") && !lower.contains("公司") && !lower.contains("企业");
    }
}
