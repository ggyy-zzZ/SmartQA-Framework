package com.qa.demo.qa.constraint;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.retrieval.structured.RegionResolverService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "region 命中"判断：判定一个 {@link ContextChunk} 是否属于指定区域约束。
 * <p>
 * 命中规则（D3 已确认）：
 * <ol>
 *   <li>chunk 的 displayLabel 命中"XX 城市"code（注册地 code）</li>
 *   <li>chunk 的 snippet 中抽取到的 6 位 code 与 officeRegionCodes 有交</li>
 *   <li>公司名（displayLabel）含"XX 城市分公司"模式 — 用于"母公司在外地，名字含
 *       '北京分公司'"的强语义信号</li>
 * </ol>
 * 三个条件取并集；任一命中即视为满足 region 约束。
 * <p>
 * 该类为无状态组件；可被 {@link HardConstraintGate} 与 Vector/Graph/SQL 三路召回器复用。
 */
@Component
public class RegionMatcher {

    /** 6 位行政代码：GB/T 2260 */
    private static final Pattern REGION_CODE = Pattern.compile("\\b\\d{6}\\b");

    /** "XX 城市分公司"弱信号：从公司名里提取城市名作 name-pattern 校验 */
    private static final Pattern BRANCH_NAME = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,12})\\s*(分公司|分店|办事处|代表处)");

    private final RegionResolverService regionResolver;

    public RegionMatcher(RegionResolverService regionResolver) {
        this.regionResolver = regionResolver;
    }

    /**
     * 判定 chunk 是否满足 region 约束。
     *
     * @param chunk       召回片段
     * @param constraint  约束集（仅 regionCodes / officeRegionCodes 参与判断）
     * @return true 表示 chunk 命中 region 约束，可保留
     */
    public boolean matches(ContextChunk chunk, ConstraintSet constraint) {
        if (constraint == null || !constraint.hasRegion()) {
            return true;
        }
        Set<String> chunkCodes = extractRegionCodes(chunk);
        if (!chunkCodes.isEmpty()) {
            // 任意 code 落在注册地 / 办公地 集合 → 命中
            for (String code : chunkCodes) {
                if (constraint.regionCodes().contains(code)
                        || constraint.officeRegionCodes().contains(code)) {
                    return true;
                }
            }
        }
        // name-pattern 兜底：displayLabel 含"XX 分公司"且 XX 是约束目标城市之一
        if (matchesBranchNamePattern(chunk.displayLabel(), constraint)) {
            return true;
        }
        return false;
    }

    /**
     * 从 chunk 的 displayLabel / snippet 中抽取所有可能的 6 位行政代码。
     */
    public Set<String> extractRegionCodes(ContextChunk chunk) {
        Set<String> out = new HashSet<>();
        // 1) snippet 中的 6 位 code
        Matcher m = REGION_CODE.matcher(chunk.snippet());
        while (m.find()) {
            String code = m.group();
            if (regionResolver.allCodes().contains(code)) {
                out.add(code);
            }
        }
        // 2) snippet 中的"省/市/区"中文名 → reverse 反查
        //    与 RegionResolverService.extractRegionCodes 共享"长 alias 优先"策略
        RegionResolverService.RegionResolveResult snippetHits =
                regionResolver.extractRegionCodes(chunk.snippet());
        out.addAll(snippetHits.codes());
        return out;
    }

    /**
     * name-pattern 兜底：公司名（如"同道精英（天津）信息技术有限公司北京分公司"）含
     * "北京分公司"且"北京"对应约束目标 → 视为满足。
     */
    private boolean matchesBranchNamePattern(String displayLabel, ConstraintSet constraint) {
        if (displayLabel == null || displayLabel.isBlank()) {
            return false;
        }
        Matcher m = BRANCH_NAME.matcher(displayLabel);
        while (m.find()) {
            String cityFrag = m.group(1);
            RegionResolverService.RegionResolveResult hit =
                    regionResolver.extractRegionCodes(cityFrag);
            if (hit.isEmpty()) {
                continue;
            }
            for (String code : hit.codes()) {
                if (constraint.regionCodes().contains(code)
                        || constraint.officeRegionCodes().contains(code)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 工具方法：从问句中抽取 officeRegionCodes（D3 暂不区分注册/办公用词，
     * 一律使用 regionCodes；该方法为未来扩展"办公地"语义预留）。
     */
    public List<String> extractOfficeRegionCodes(String question) {
        return new ArrayList<>(regionResolver.extractRegionCodes(question).codes());
    }
}
