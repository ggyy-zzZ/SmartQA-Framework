package com.qa.demo.qa.domain;

import com.qa.demo.qa.config.BusinessRulesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationScopeSupportTest {

    private ConversationScopeSupport scopeSupport;

    @BeforeEach
    void setUp() {
        BusinessRulesConfig config = new BusinessRulesConfig();
        BusinessRulesConfig.ConversationScope scope = config.getConversationScope();
        scope.setCatalogQuestionMarkers(List.of(
                "哪些种类", "包含哪些种类", "有哪些类型", "包含哪些类型"
        ));
        scope.setContinuationMarkers(List.of("这些", "那些", "它们"));
        config.getIntentRouting().setFollowUpReferenceMarkers(List.of("这些", "那些", "它们", "上面", "刚才"));
        scopeSupport = new ConversationScopeSupport(config);
    }

    @Test
    void catalogQuestion_detectsOperatingStatusCatalog() {
        assertTrue(scopeSupport.isCatalogQuestion("公司经营状态包含哪些种类"));
    }

    @Test
    void catalogQuestion_rejectsPronounContinuation() {
        assertFalse(scopeSupport.isCatalogQuestion("这些公司的经营状态有哪些种类"));
    }

    @Test
    void catalogQuestion_rejectsNonCatalogList() {
        assertFalse(scopeSupport.isCatalogQuestion("北京的公司有哪些"));
    }
}
