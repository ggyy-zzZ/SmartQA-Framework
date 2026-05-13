package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.CompanyCandidate;
import com.qa.demo.qa.core.QaScopes;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 企业对象指代模糊时的澄清判断与候选展示文案。
 */
@Service
public class CompanyClarificationAdvisor {

    public boolean needsCompanyClarification(String question, String scope) {
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
        return refersToUnnamedObject(lower);
    }

    /**
     * 「这家 / 我们单位 / 哪家公司」等说法没有落到具体名称时，先澄清比盲检索更稳妥。
     */
    public boolean refersToUnnamedObject(String lower) {
        return lower.contains("这家")
                || lower.contains("那家")
                || lower.contains("该公司")
                || lower.contains("本公司")
                || lower.contains("我们公司")
                || lower.contains("这个公司")
                || lower.contains("我司")
                || lower.contains("我们单位")
                || lower.contains("咱们公司")
                || lower.contains("哪家公司")
                || lower.contains("哪个公司")
                || lower.contains("哪一家公司")
                || lower.contains("什么公司")
                || lower.contains("哪一家企业");
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
