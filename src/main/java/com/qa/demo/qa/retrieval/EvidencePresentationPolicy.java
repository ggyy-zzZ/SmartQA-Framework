package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.response.QaConversationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析证据呈现策略：默认尽量完整；支持 API 开关与用户强调「完整展示」。
 */
@Component
public class EvidencePresentationPolicy {

    private static final Pattern EMPHASIS_PATTERN = Pattern.compile(
            "(展示完整|完整数据|全部展示|全部列出|列出全部|完整列出|不要省略|不要缩略|看全部|穷举|完整列表|所有记录)"
    );
    private static final Pattern FOLLOW_UP_FULL_PATTERN = Pattern.compile(
            "^(展示完整|完整数据|全部展示|看全部|列出全部|展示全部|要完整).*$"
    );

    private final QaAssistantProperties properties;

    public EvidencePresentationPolicy(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public EvidencePresentationContext resolve(
            String question,
            String requestedMode,
            List<QaConversationService.ConversationTurn> priorTurns
    ) {
        EvidencePresentationMode explicit = EvidencePresentationMode.parse(requestedMode);
        EvidencePresentationMode defaultMode = properties.isEvidencePresentationDefaultFull()
                ? EvidencePresentationMode.FULL
                : EvidencePresentationMode.COMPACT;
        EvidencePresentationMode mode = explicit != null ? explicit : defaultMode;

        boolean emphasized = detectsUserEmphasis(question) || detectsFollowUpForFullDetail(question, priorTurns);
        if (emphasized) {
            mode = EvidencePresentationMode.FULL;
        }

        int evidenceTopK;
        int sqlMax = Math.max(1, properties.getSqlQueryMaxRows());
        if (mode == EvidencePresentationMode.FULL) {
            evidenceTopK = emphasized
                    ? Math.max(1, properties.getEvidenceEmphasisTopK())
                    : Math.max(1, properties.getEvidenceFullTopK());
        } else {
            evidenceTopK = Math.max(1, properties.getRetrievalTopK());
            sqlMax = Math.min(sqlMax, Math.max(evidenceTopK, properties.getMysqlTopK()));
        }

        return new EvidencePresentationContext(mode, emphasized, evidenceTopK, sqlMax);
    }

    private boolean detectsUserEmphasis(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return EMPHASIS_PATTERN.matcher(question).find();
    }

    private boolean detectsFollowUpForFullDetail(
            String question,
            List<QaConversationService.ConversationTurn> priorTurns
    ) {
        if (question == null || question.isBlank() || priorTurns == null || priorTurns.isEmpty()) {
            return false;
        }
        String q = question.strip();
        if (!FOLLOW_UP_FULL_PATTERN.matcher(q).matches()) {
            return false;
        }
        for (int i = priorTurns.size() - 1; i >= 0; i--) {
            QaConversationService.ConversationTurn turn = priorTurns.get(i);
            if (turn == null || turn.answer() == null) {
                continue;
            }
            String answer = turn.answer();
            if (answer.contains("另有约") && answer.contains("未展示")
                    || answer.contains("展示完整数据")) {
                return true;
            }
            break;
        }
        return false;
    }
}
