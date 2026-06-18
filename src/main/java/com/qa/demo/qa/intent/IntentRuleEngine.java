package com.qa.demo.qa.intent;

import com.qa.demo.qa.core.IntentDecision;
import com.qa.demo.qa.domain.EnterpriseLexicon;
import com.qa.demo.qa.domain.QuestionEntityExtractor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IntentRuleEngine {

    private final EnterpriseLexicon lexicon;
    private final QuestionEntityExtractor entityExtractor;

    public IntentRuleEngine(EnterpriseLexicon lexicon, QuestionEntityExtractor entityExtractor) {
        this.lexicon = lexicon;
        this.entityExtractor = entityExtractor;
    }

    public IntentDecision classify(String question, boolean explicitCompanyHint, String reasonPrefix) {
        boolean relationIntent = lexicon.containsAny(question, lexicon.routingKeywordList("relation"));
        boolean docIntent = lexicon.containsAny(question, lexicon.routingKeywordList("document"));
        boolean semanticIntent = lexicon.containsAny(question, lexicon.routingKeywordList("semantic"));
        boolean mysqlIntent = lexicon.containsAny(question, lexicon.routingKeywordList("mysql"));
        boolean sqlIntent = lexicon.containsAny(question, lexicon.routingKeywordList("sql"));

        String personName = entityExtractor.extractPersonName(question);
        if (personName == null) {
            personName = "";
        }
        String roleFocus = entityExtractor.inferRoleFocus(question);
        String queryType = entityExtractor.inferQueryType(question, personName);
        List<String> companyHints = entityExtractor.extractCompanyHints(question);

        if (relationIntent && semanticIntent) {
            return decision("hybrid", reasonPrefix + "_hybrid_relation_semantic",
                    queryType, personName, companyHints, roleFocus);
        }
        if (sqlIntent && (mysqlIntent || relationIntent || explicitCompanyHint)) {
            return decision("sql", reasonPrefix + "_sql_analytic_or_filtered",
                    queryType, personName, companyHints, roleFocus);
        }
        if (mysqlIntent && !relationIntent) {
            return decision("mysql", reasonPrefix + "_mysql_structured_raw",
                    queryType, personName, companyHints, roleFocus);
        }
        if (relationIntent || explicitCompanyHint) {
            return decision("hybrid", reasonPrefix + "_hybrid_relation_or_entity",
                    queryType, personName, companyHints, roleFocus);
        }
        if (docIntent) {
            return decision("document", reasonPrefix + "_document_policy_flow",
                    queryType, personName, companyHints, roleFocus);
        }
        if (semanticIntent) {
            return decision("vector", reasonPrefix + "_vector_semantic",
                    queryType, personName, companyHints, roleFocus);
        }
        return decision("unknown", reasonPrefix + "_unknown_out_of_scope",
                queryType, personName, companyHints, roleFocus);
    }

    private IntentDecision decision(
            String intent,
            String reason,
            String queryType,
            String personName,
            List<String> companyHints,
            String roleFocus
    ) {
        return IntentSlots.normalize(new IntentDecision(
                intent,
                lexicon.routingScore(intent),
                reason,
                queryType,
                personName,
                companyHints,
                roleFocus
        ));
    }
}
