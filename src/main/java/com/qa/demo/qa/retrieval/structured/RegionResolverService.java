package com.qa.demo.qa.retrieval.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行政区划码解析器：把问句里的"北京/京/珠三角/东城"等中英文别名 → GB/T 2260 六位行政代码列表。
 * <p>
 * 数据源：{@code qa/region-dictionary.json}（province + city + area 3 级共 3351 条 + 47 个简称别名）。
 * 匹配策略：
 * <ol>
 *   <li>先用 {@code aliases} 表（简称）做大粒度匹配（"京"→"北京市"）</li>
 *   <li>再扫问句中的 2-12 个汉字连续片段，做 {@code reverse} 表（name → code）反查</li>
 *   <li>如匹配到省级名（"北京市"），自动展开为该省下辖所有市/区代码（110000-119999）</li>
 * </ol>
 * 该服务无业务耦合；调用方可决定如何把 code 列表注入到 Cypher WHERE / Qdrant filter。
 */
@Service
public class RegionResolverService {

    private static final Logger log = LoggerFactory.getLogger(RegionResolverService.class);
    private static final String CLASSPATH_PATH = "qa/region-dictionary.json";

    /** 中文片段：连续 2~12 个汉字，用于反向扫描问句 */
    private static final Pattern HAN_RUN = Pattern.compile("[\\u4e00-\\u9fa5]{2,12}");

    private final ObjectMapper objectMapper;
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile Set<String> allRegionCodes = Collections.emptySet();

    public RegionResolverService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadFromClasspath() {
        try (InputStream in = new ClassPathResource(CLASSPATH_PATH).getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode codesNode = root.path("codes");
            JsonNode aliasesNode = root.path("aliases");
            JsonNode reverseNode = root.path("reverse");

            // codes: <6-digit> -> {name, level, parent, path}
            Map<String, CodeEntry> codes = new LinkedHashMap<>();
            codesNode.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                codes.put(e.getKey(), new CodeEntry(
                        e.getKey(),
                        v.path("name").asText(""),
                        v.path("level").asInt(0),
                        v.path("parent").asText("")
                ));
            });
            // aliases: alias -> name
            Map<String, String> aliases = new HashMap<>();
            aliasesNode.fields().forEachRemaining(e -> aliases.put(e.getKey(), e.getValue().asText("")));
            // reverse: name -> code
            Map<String, String> reverse = new HashMap<>();
            reverseNode.fields().forEachRemaining(e -> reverse.put(e.getKey(), e.getValue().asText("")));

            this.snapshot = new Snapshot(codes, aliases, reverse);
            this.allRegionCodes = Collections.unmodifiableSet(new TreeSet<>(codes.keySet()));

            log.info("[region] loaded {} codes ({} aliases, {} reverse names) from {}",
                    codes.size(), aliases.size(), reverse.size(), CLASSPATH_PATH);
        } catch (Exception e) {
            log.warn("[region] failed to load {}: {} — region filter disabled", CLASSPATH_PATH, e.toString());
            this.snapshot = Snapshot.empty();
            this.allRegionCodes = Collections.emptySet();
        }
    }

    /** 直接返回字典里所有 6 位代码（用于 ETL 回填 / 单测）。 */
    public Set<String> allCodes() {
        return allRegionCodes;
    }

    /** 反查：根据行政代码返回显示名（找不到返回 null）。 */
    public String nameOf(String code) {
        CodeEntry e = snapshot.codes.get(code);
        return e == null ? null : e.name;
    }

    /**
     * 从问句中抽取行政区划代码：返回所有匹配的 6 位代码去重。
     * <p>
     * 匹配规则：
     * <ul>
     *   <li>简称（aliases 表）"京"→"北京市"→省级展开</li>
     *   <li>2-12 个连续汉字 → reverse 表反查；命中即取该 code</li>
     *   <li>省级命中 → 展开为该省下辖所有市/区代码</li>
     * </ul>
     * 完全无业务耦合；如果问句里没有任何行政区划，返回空集。
     */
    public RegionResolveResult extractRegionCodes(String question) {
        if (question == null || question.isBlank()) {
            return RegionResolveResult.empty();
        }
        Snapshot snap = this.snapshot;
        if (snap.codes.isEmpty()) {
            return RegionResolveResult.empty();
        }
        List<String> matchedNames = new ArrayList<>();
        Set<String> codes = new TreeSet<>();
        // 已扫描的文本 token，避免重复匹配（如 "北京市" 既是 2 字也是 3 字）
        Set<String> consumed = new java.util.HashSet<>();

        // 1) aliases 单字/双字简称：必须做长 alias 优先（"珠三角" > "珠"）
        List<String> aliasKeys = new ArrayList<>(snap.aliases.keySet());
        aliasKeys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        int cursor = 0;
        while (cursor < question.length()) {
            boolean matched = false;
            for (String alias : aliasKeys) {
                if (question.regionMatches(true, cursor, alias, 0, alias.length())) {
                    String name = snap.aliases.get(alias);
                    if (name != null && consumed.add(name)) {
                        matchedNames.add(alias + "→" + name);
                        expandNameToCodes(name, codes, snap, consumed, matchedNames);
                    }
                    cursor += alias.length();
                    matched = true;
                    break;
                }
            }
            if (!matched) cursor++;
        }

        // 2) 2-12 个连续汉字 reverse 反查
        Matcher m = HAN_RUN.matcher(question);
        while (m.find()) {
            String frag = m.group();
            if (consumed.contains(frag)) {
                continue;
            }
            String code = snap.reverse.get(frag);
            if (code != null) {
                consumed.add(frag);
                matchedNames.add(frag);
                expandCodeToCodes(code, codes, snap);
            }
        }

        if (codes.isEmpty()) {
            return RegionResolveResult.empty();
        }
        return new RegionResolveResult(new ArrayList<>(codes), List.copyOf(matchedNames));
    }

    /**
     * 名称 → 该名称的 code + 全部后代 code。
     * <p>
     * 例：name="北京市"（province, level=1）→ codes 包含 11 本身 + 110000-119999 内所有子级。
     */
    private void expandNameToCodes(String name, Set<String> codes, Snapshot snap,
                                   Set<String> consumed, List<String> matchedNames) {
        String code = snap.reverse.get(name);
        if (code == null) return;
        expandCodeToCodes(code, codes, snap);
    }

    private void expandCodeToCodes(String code, Set<String> codes, Snapshot snap) {
        codes.add(code);
        CodeEntry entry = snap.codes.get(code);
        if (entry == null) {
            return;
        }
        if (entry.level == 1) {
            // 省级：展开所有子级（市级 code 以 省级 2 位开头）
            String prefix = code.substring(0, 2);
            for (CodeEntry child : snap.codes.values()) {
                if (child.code.startsWith(prefix)) {
                    codes.add(child.code);
                }
            }
        } else if (entry.level == 2) {
            // 市级：展开所有子级（区县级 code 以 市级 4 位开头）
            String prefix = code.substring(0, 4);
            for (CodeEntry child : snap.codes.values()) {
                if (child.code.startsWith(prefix)) {
                    codes.add(child.code);
                }
            }
        }
        // 区县级 (level 3) 即叶子
    }

    /** 内部快照。 */
    private record Snapshot(Map<String, CodeEntry> codes,
                            Map<String, String> aliases,
                            Map<String, String> reverse) {
        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of());
        }
    }

    private record CodeEntry(String code, String name, int level, String parent) {}

    /**
     * 解析结果。
     *
     * @param codes       6 位行政代码列表（去重，按字典序）
     * @param matchedNames 命中的文本片段（alias / 名称）— 调试与日志用
     */
    public record RegionResolveResult(List<String> codes, List<String> matchedNames) {
        public boolean isEmpty() {
            return codes == null || codes.isEmpty();
        }
        public static RegionResolveResult empty() {
            return new RegionResolveResult(List.of(), List.of());
        }
    }
}
