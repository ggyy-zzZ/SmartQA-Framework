package com.qa.demo.qa.retrieval.parent;

import com.qa.demo.knowledge.EnterpriseCanonicalFactsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParentScopedCompanyListSupportTest {

    @Mock
    private EnterpriseCanonicalFactsRegistry canonicalFactsRegistry;

    @Test
    void detectsHeadquarterSubsidiaryList() {
        when(canonicalFactsRegistry.hasResolvableCompanyAnchor("总部下现在有哪些存续的主体")).thenReturn(true);
        assertTrue(ParentScopedCompanyListSupport.isParentScopedCompanyList(
                "总部下现在有哪些存续的主体", canonicalFactsRegistry));
    }

    @Test
    void rejectsWithoutParentAnchor() {
        when(canonicalFactsRegistry.hasResolvableCompanyAnchor("总部下现在有哪些存续的主体")).thenReturn(false);
        assertFalse(ParentScopedCompanyListSupport.isParentScopedCompanyList(
                "总部下现在有哪些存续的主体", canonicalFactsRegistry));
    }
}
