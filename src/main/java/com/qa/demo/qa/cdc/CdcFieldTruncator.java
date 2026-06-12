package com.qa.demo.qa.cdc;

import com.qa.demo.qa.config.GraphNodeDefinitionsProperties;
import com.qa.demo.qa.config.GraphNodeFieldSpec;
import org.springframework.stereotype.Component;

/**
 * 与 Python ``scripts/enterprise_pipeline/graph_export_util.py::truncate_value``
 * 行为对齐的通用截断器：超长按 ``global.truncation.suffix`` 截断并附
 * ``*Truncated=true`` / ``*CharCount=N`` 标记。
 *
 * <p>CDC 写入路径以该组件做最终保险，避免单条 MySQL 变更事件把整张 Neo4j 节点
 * 撑爆；Python 离线灌库侧已先截过一遍，这里只覆盖 CDC 单独触发的场景。</p>
 */
@Component
public class CdcFieldTruncator {

    private final GraphNodeDefinitionsProperties definitions;

    public CdcFieldTruncator(GraphNodeDefinitionsProperties definitions) {
        this.definitions = definitions;
    }

    /**
     * 截断返回结果。三个值分别写入 Neo4j：
     * <ul>
     *   <li>value：被截断的字符串（或原值）</li>
     *   <li>charCount：原始字符串长度</li>
     *   <li>truncated：是否被截断</li>
     * </ul>
     */
    public Truncation truncate(String value, int maxChars, String propName) {
        if (value == null) {
            return new Truncation(null, 0, false);
        }
        int charCount = value.length();
        if (maxChars <= 0 || charCount <= maxChars) {
            return new Truncation(value, charCount, false);
        }
        String suffix = definitions.truncationSuffix();
        int keep = Math.max(0, maxChars - suffix.length());
        String truncated = value.substring(0, keep) + suffix;
        return new Truncation(truncated, charCount, true);
    }

    /**
     * 按白名单 spec 的 maxChars 截断（maxChars <= 0 表示不截断）。
     */
    public Truncation truncateBySpec(String value, GraphNodeFieldSpec spec) {
        if (spec == null) {
            return new Truncation(value, value == null ? 0 : value.length(), false);
        }
        int max = spec.maxCharsOrDefault(definitions.defaultMaxChars());
        return truncate(value, max, spec.name());
    }

    /** 截断结果三元组。 */
    public record Truncation(String value, int charCount, boolean truncated) {
    }
}
