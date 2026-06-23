package com.qa.demo.qa.docvec.routing;

import com.qa.demo.qa.docvec.session.DocVecSessionSnapshot;
import org.springframework.stereotype.Component;

/**
 * DocVec 问句路由入口（LLM 决策，无关键词规则）。
 */
@Component
public class DocVecQueryRouter {

    private final DocVecLlmRouter llmRouter;

    public DocVecQueryRouter(DocVecLlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    public DocVecRouteDecision route(String question, DocVecSessionSnapshot session) {
        return llmRouter.route(question, session == null ? DocVecSessionSnapshot.empty() : session);
    }
}
