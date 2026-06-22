package com.qa.demo.qa.answer;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.retrieval.EvidenceTruncationMeta;
import org.springframework.stereotype.Component;

/**
 * 当证据被截断时，生成对用户可见的说明与后续操作建议。
 */
@Component
public class EvidenceTruncationAdvisor {

    private final QaAssistantProperties properties;

    public EvidenceTruncationAdvisor(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public String buildNotice(EvidenceTruncationMeta meta) {
        if (!properties.isEvidenceTruncationNoticeEnabled() || meta == null || !meta.truncated()) {
            return "";
        }
        int omitted = meta.omittedCount();
        if (omitted <= 0) {
            return "";
        }
        return "\n\n---\n说明：本次检索共找到约 "
                + meta.candidatesConsidered()
                + " 条相关记录，当前回答仅基于其中 "
                + meta.presentedToModel()
                + " 条证据生成，另有约 "
                + omitted
                + " 条未展示。如需查看完整列表，请回复「展示完整数据」。";
    }
}
