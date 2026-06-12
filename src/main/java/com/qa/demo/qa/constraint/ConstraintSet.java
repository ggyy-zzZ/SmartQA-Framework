package com.qa.demo.qa.constraint;

import java.util.List;
import java.util.Map;

/**
 * 结构化硬约束：从问句抽取 + intent 透传的查询前置条件。
 * <p>
 * 约束层级：{@link #regionCodes}（注册地）+ {@link #officeRegionCodes}（办公地）+
 * {@link #industryCodes}（行业枚举）+ {@link #statusCodes}（经营状态枚举）+
 * {@link #companyHints}（候选公司名）。
 * <p>
 * 该结构由 {@link ConstraintResolver} 在 orchestrator 之前产出，注入到
 * {@code QaRetrievalPipeline.retrieveUnifiedEnterprise}，作为多源召回的硬过滤与
 * 重排前后两道闸门的判定依据。
 */
public record ConstraintSet(
        /** GB/T 2260 6 位代码（去重、按字典序），按"粒度从粗到细"排列 */
        List<String> regionCodes,
        /** 办公地区代码集合；与 regionCodes 取并集用于"在北京"语义判断 */
        List<String> officeRegionCodes,
        /** 行业枚举（暂留空，由后续枚举目录扩展） */
        List<String> industryCodes,
        /** 经营状态枚举（"存续"/"注销"/"吊销"） */
        List<String> statusCodes,
        /** 候选公司名（由 intent.companyHints() 透传） */
        List<String> companyHints,
        /** 查询形态：AGGREGATE / SINGLE_FACT / COMPLEX / UNKNOWN */
        QueryShape queryShape,
        /** 内部元数据：resolverMode / droppedCount / ambiguityResolved 等 */
        Map<String, Object> meta
) {
    public ConstraintSet {
        regionCodes = regionCodes == null ? List.of() : List.copyOf(regionCodes);
        officeRegionCodes = officeRegionCodes == null ? List.of() : List.copyOf(officeRegionCodes);
        industryCodes = industryCodes == null ? List.of() : List.copyOf(industryCodes);
        statusCodes = statusCodes == null ? List.of() : List.copyOf(statusCodes);
        companyHints = companyHints == null ? List.of() : List.copyOf(companyHints);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public static ConstraintSet empty() {
        return new ConstraintSet(List.of(), List.of(), List.of(), List.of(), List.of(),
                QueryShape.UNKNOWN, Map.of());
    }

    public boolean isEmpty() {
        return regionCodes.isEmpty() && officeRegionCodes.isEmpty()
                && industryCodes.isEmpty() && statusCodes.isEmpty()
                && companyHints.isEmpty();
    }

    public boolean hasRegion() {
        return !regionCodes.isEmpty() || !officeRegionCodes.isEmpty();
    }

    public boolean hasStatus() {
        return !statusCodes.isEmpty();
    }

    public boolean hasIndustry() {
        return !industryCodes.isEmpty();
    }

    public boolean hasCompanyHint() {
        return !companyHints.isEmpty();
    }

    /** 查询形态：决定 pipeline 是否走 aggregate 路径。 */
    public enum QueryShape {
        AGGREGATE,
        SINGLE_FACT,
        COMPLEX,
        UNKNOWN
    }
}
