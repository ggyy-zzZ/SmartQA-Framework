package com.qa.demo.qa.review;

import com.qa.demo.qa.alignment.EvidenceAlignmentService;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostGenerationReviewServiceTest {

    private PostGenerationReviewService service;

    @BeforeEach
    void setUp() {
        QaAssistantProperties properties = new QaAssistantProperties();
        properties.setAlignmentStrict(false);
        service = new PostGenerationReviewService(properties, new EvidenceAlignmentService());
    }

    @Test
    void refusalAnswerDowngradesCanAnswer() {
        PostGenerationReviewService.Adjustment adjustment = service.adjust(
                "注册资本多少",
                "抱歉，根据当前提供的证据，无法回答您这个问题。",
                List.of(ContextChunk.ofCompany("1", "A", "x", "snippet", 10.0, "sql")),
                true,
                0.8,
                false
        );
        assertFalse(adjustment.canAnswer());
    }

    @Test
    void degradedGenerationDowngradesCanAnswer() {
        PostGenerationReviewService.Adjustment adjustment = service.adjust(
                "问题",
                "简要结论",
                List.of(ContextChunk.ofCompany("1", "A", "x", "snippet", 10.0, "sql")),
                true,
                0.8,
                true
        );
        assertFalse(adjustment.canAnswer());
    }
}
