package com.qa.demo.qa.docvec.session;

import com.qa.demo.qa.docvec.routing.DocVecQueryType;

import java.util.List;

public record DocVecConversationTurn(
        String turnId,
        String question,
        String answer,
        String route,
        DocVecQueryType queryType,
        String personName,
        String roleLabel,
        String regionKeyword,
        String certificateTypeName,
        List<String> companyNames,
        List<String> companyIds
) {
}
