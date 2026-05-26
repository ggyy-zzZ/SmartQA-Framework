package com.qa.demo.qa.response;

import com.qa.demo.qa.retrieval.GraphContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QaConversationServiceTest {

    @Mock
    private GraphContextService graphContextService;

    private QaConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new QaConversationService(graphContextService);
        when(graphContextService.hasExplicitCompanyHint(anyString())).thenReturn(false);
    }

    @Test
    void typeOnlyFollowUpAfterPersonCertificateQuestion() {
        List<QaConversationService.ConversationTurn> prior = List.of(
                new QaConversationService.ConversationTurn(
                        "t1",
                        "张雁雯负责哪些证照",
                        "共20条",
                        List.of(),
                        List.of(),
                        "张雁雯"
                )
        );
        assertTrue(conversationService.resolveFollowUp(null, "涉及类型有哪些", prior));
        String retrieval = conversationService.buildRetrievalQuestion("涉及类型有哪些", prior, true);
        assertTrue(retrieval.contains("张雁雯"));
        assertTrue(retrieval.contains("证照类型"));
    }

    @Test
    void certificateFollowUpRewritesRetrievalQuestionWithPriorPerson() {
        List<QaConversationService.ConversationTurn> prior = List.of(
                new QaConversationService.ConversationTurn(
                        "t1",
                        "张雁雯管哪些资质证照",
                        "共20条…",
                        List.of("公司A", "公司B"),
                        List.of(),
                        "张雁雯"
                )
        );
        boolean followUp = conversationService.resolveFollowUp(null, "具体是哪些证照？", prior);
        assertTrue(followUp);

        String retrieval = conversationService.buildRetrievalQuestion("具体是哪些证照？", prior, true);
        assertTrue(retrieval.contains("张雁雯"));
        assertTrue(retrieval.contains("证照"));
    }
}
