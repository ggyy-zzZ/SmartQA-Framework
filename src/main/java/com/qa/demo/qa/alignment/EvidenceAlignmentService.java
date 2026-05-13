package com.qa.demo.qa.alignment;

import com.qa.demo.qa.core.ContextChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量证据对齐：回答与检索片段的关键词重合度与启发式提示（不替代二次 LLM 深度审计）。
 */
@Service
public class EvidenceAlignmentService {

    private static final Pattern ZH_TOKEN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
    private static final Pattern EN_TOKEN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{2,}");

    public record AlignmentInsight(double keywordOverlap, boolean lowOverlap, List<String> warnings) {
    }

    /**
     * @param canAnswer 本轮是否按「有证据可答」路径生成（用于启发式，非严格逻辑门）
     */
    public AlignmentInsight analyze(
            String question,
            String answer,
            List<ContextChunk> evidence,
            boolean canAnswer
    ) {
        List<String> warnings = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return new AlignmentInsight(1.0, false, warnings);
        }
        if (evidence == null || evidence.isEmpty()) {
            if (canAnswer) {
                warnings.add("标记为可答但证据列表为空，请人工核查路由与检索。");
            }
            return new AlignmentInsight(0.0, true, warnings);
        }

        StringBuilder corpus = new StringBuilder();
        if (question != null) {
            corpus.append(question).append(' ');
        }
        for (ContextChunk c : evidence) {
            if (c == null) {
                continue;
            }
            corpus.append(c.companyName()).append(' ');
            corpus.append(c.snippet()).append(' ');
            corpus.append(c.field()).append(' ');
        }
        Set<String> answerTokens = tokenize(answer.toLowerCase(Locale.ROOT));
        Set<String> corpusTokens = tokenize(corpus.toString().toLowerCase(Locale.ROOT));
        if (answerTokens.isEmpty()) {
            return new AlignmentInsight(1.0, false, warnings);
        }
        long hits = answerTokens.stream().filter(corpusTokens::contains).count();
        double overlap = (double) hits / (double) answerTokens.size();
        boolean low = overlap < 0.08 && answer.length() > 48;
        if (low) {
            warnings.add("回答与证据片段的关键词重合度较低，请人工核实是否偏离证据。");
        }
        return new AlignmentInsight(overlap, low, warnings);
    }

    private Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher zh = ZH_TOKEN.matcher(text);
        while (zh.find()) {
            String t = zh.group();
            if (t.length() >= 2 && t.length() <= 12) {
                out.add(t);
            }
        }
        Matcher en = EN_TOKEN.matcher(text);
        while (en.find()) {
            out.add(en.group());
        }
        return out;
    }
}
