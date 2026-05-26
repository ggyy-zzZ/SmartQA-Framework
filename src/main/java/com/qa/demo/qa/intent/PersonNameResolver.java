package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.domain.PersonNameParser;
import com.qa.demo.qa.learning.ActiveLearningService;
import com.qa.demo.qa.retrieval.EmployeeBaseKnowledgeService;
import com.qa.demo.qa.retrieval.GraphContextService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 将问句/LLM 槽位中的敬称、别名解析为规范姓名，并绑定员工唯一标识（意图边界，检索层不再按姓名模糊匹配）。
 */
@Component
public class PersonNameResolver {

    private final EmployeeBaseKnowledgeService employeeBaseKnowledge;
    private final ActiveLearningService activeLearningService;
    private final GraphContextService graphContextService;

    public PersonNameResolver(
            EmployeeBaseKnowledgeService employeeBaseKnowledge,
            ActiveLearningService activeLearningService,
            GraphContextService graphContextService
    ) {
        this.employeeBaseKnowledge = employeeBaseKnowledge;
        this.activeLearningService = activeLearningService;
        this.graphContextService = graphContextService;
    }

    public String resolve(String rawPersonName, List<ContextChunk> learned) {
        return resolve(rawPersonName, learned, "any").canonicalName();
    }

    public PersonNameResolution resolve(String rawPersonName, List<ContextChunk> learned, String roleFocus) {
        if (rawPersonName == null || rawPersonName.isBlank()) {
            return PersonNameResolution.resolved("");
        }
        String trimmed = rawPersonName.trim();
        String fromLearning = activeLearningService.resolvePersonAlias(trimmed, learned);
        if (!fromLearning.equals(trimmed)) {
            return withEmployeeId(fromLearning);
        }

        String fromEmployee = employeeBaseKnowledge.resolveCanonicalName(trimmed);
        if (!fromEmployee.equals(trimmed) && !PersonNameParser.hasHonorificSuffix(fromEmployee)) {
            return withEmployeeId(fromEmployee);
        }

        String searchCore = PersonNameParser.hasHonorificSuffix(trimmed)
                ? PersonNameParser.stripHonorific(trimmed)
                : trimmed;
        if (searchCore.isBlank()) {
            return withEmployeeId(trimmed);
        }

        String role = roleFocus == null || roleFocus.isBlank() ? "any" : roleFocus;
        List<String> graphNames = graphContextService.listPersonNamesByHintAndRole(searchCore, role, 12);
        if (graphNames.size() == 1) {
            return withEmployeeId(graphNames.get(0));
        }
        if (graphNames.size() > 1) {
            return PersonNameResolution.ambiguous(trimmed, graphNames);
        }

        if (!fromEmployee.equals(trimmed)) {
            return withEmployeeId(fromEmployee);
        }
        return withEmployeeId(trimmed);
    }

    private PersonNameResolution withEmployeeId(String canonicalName) {
        Integer id = employeeBaseKnowledge.resolveToEmployeeId(canonicalName);
        return PersonNameResolution.resolved(canonicalName, id);
    }

    public boolean needsClarification(PersonNameResolution resolution) {
        return resolution != null && resolution.needsClarification();
    }

    public boolean stillHonorific(String personName) {
        return PersonNameParser.hasHonorificSuffix(personName);
    }
}
