package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.PersonNameParser;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import com.qa.demo.qa.retrieval.GraphContextService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 人物指称不清时引导用户补充全名；与 {@link CompanyClarificationAdvisor} 对称。
 */
@Service
public class PersonClarificationAdvisor {

    private final QuestionEntityExtractor entityExtractor;
    private final GraphContextService graphContextService;

    public PersonClarificationAdvisor(
            QuestionEntityExtractor entityExtractor,
            GraphContextService graphContextService
    ) {
        this.entityExtractor = entityExtractor;
        this.graphContextService = graphContextService;
    }

    public boolean needsClarification(IntentDecision intent, List<ContextChunk> evidence, String question) {
        if (intent == null || !intent.isPersonRoleListQuery()) {
            return false;
        }
        String person = intent.personName();
        if (person == null || person.isBlank()) {
            String extracted = entityExtractor.extractPersonName(question);
            return extracted != null && PersonNameParser.hasHonorificSuffix(extracted);
        }
        if (PersonNameParser.hasHonorificSuffix(person)) {
            return true;
        }
        if (evidence == null || evidence.isEmpty()) {
            return entityExtractor.isRoleRelationQuery(question);
        }
        return evidence.stream().anyMatch(this::isPersonNotFoundEvidence);
    }

    public String buildClarificationAnswer(IntentDecision intent, String question, PersonNameResolution resolution) {
        List<String> candidates = resolution != null && resolution.needsClarification()
                ? resolution.candidates()
                : List.of();
        if (candidates.isEmpty()) {
            String hint = intent != null && intent.hasPersonFocus()
                    ? intent.personName()
                    : entityExtractor.extractPersonName(question);
            String role = intent == null || intent.roleFocus() == null ? "any" : intent.roleFocus();
            candidates = graphContextService.listPersonNamesByHintAndRole(
                    hint == null ? "" : hint,
                    role,
                    8
            );
        }
        String partial = intent != null && intent.hasPersonFocus() ? intent.personName() : "该位";
        if (candidates.isEmpty()) {
            return "当前知识库中未能根据「" + partial + "」锁定到具体人员。"
                    + "请补充**全名**（或工号/花名），例如「" + PersonNameParser.stripHonorific(partial) + "某某」担任哪些公司的法人」，"
                    + "我会记住并在下次直接回答。";
        }
        List<String> lines = new ArrayList<>();
        lines.add("根据您提到的任职类型，知识库中有以下可能对应的人员，请确认是其中一位（回复序号或全名）：");
        for (int i = 0; i < candidates.size(); i++) {
            lines.add((i + 1) + ") " + candidates.get(i));
        }
        lines.add("若均不是，请补充完整姓名，系统将记录待学习。");
        return String.join("\n", lines);
    }

    private boolean isPersonNotFoundEvidence(ContextChunk chunk) {
        if (chunk == null) {
            return false;
        }
        return "employee_not_found".equals(chunk.anchorId())
                || (chunk.snippet() != null && chunk.snippet().contains("未匹配到人员"));
    }
}
