package com.qa.demo.qa.intent;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.QaScopes;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 歧义指代澄清：当问题中存在"我们公司"、"这家"等模糊指代时，先让用户澄清再检索。
 * <p>
 * 歧义短语列表从配置文件动态加载（qa.assistant.ambiguous-phrases.{scope}），
 * 支持按 scope（enterprise/personal）配置不同的模式。
 */
@Service
public class CompanyClarificationAdvisor {

    private final QaAssistantProperties properties;

    public CompanyClarificationAdvisor(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public boolean needsClarification(String question, String scope) {
        if (QaScopes.PERSONAL.equals(scope)) {
            return false;
        }
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        boolean aggregateIntent = lower.contains("全部")
                || lower.contains("所有")
                || lower.contains("整体")
                || lower.contains("汇总")
                || lower.contains("top")
                || lower.contains("排名");
        if (aggregateIntent) {
            return false;
        }
        return refersToAmbiguousPhrase(lower, scope);
    }

    /**
     * 检查问题是否包含指定 scope 的歧义指代短语。
     */
    private boolean refersToAmbiguousPhrase(String lower, String scope) {
        List<String> phrases = properties.getAmbiguousPhrasesForScope(scope);
        for (String phrase : phrases) {
            if (lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public String buildClarificationAnswer(List<CompanyCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "当前无法从问题里锁定你要查的具体对象。请补充全称、可区分的名称或编号后再问。";
        }
        List<String> lines = new ArrayList<>();
        lines.add("为避免张冠李戴，需要先确认你指的是下面哪一个对象（或补充更具体的名称/编号）：");
        lines.add("你可以直接回复序号，或粘贴完整名称：");
        for (int i = 0; i < candidates.size(); i++) {
            CompanyCandidate c = candidates.get(i);
            lines.add(String.format(
                    "%d) %s（ID:%s，状态:%s）",
                    i + 1, c.companyName(), c.companyId(), c.status()
            ));
        }
        return String.join("\n", lines);
    }
}