package com.qa.demo.qa.docvec.web;

import com.qa.demo.qa.docvec.config.DocVecProperties;
import com.qa.demo.qa.docvec.service.DocVecAskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Doc-RAG 实验 API（与 /qa/ask 完全隔离）。
 */
@RestController
@RequestMapping("/qa/docvec")
public class DocVecController {

    private final DocVecAskService askService;
    private final DocVecProperties properties;

    public DocVecController(DocVecAskService askService, DocVecProperties properties) {
        this.askService = askService;
        this.properties = properties;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "enabled", properties.isEnabled(),
                "collection", properties.getCollection(),
                "qdrantUrl", properties.getQdrantUrl(),
                "vectorTopK", properties.getVectorTopK(),
                "rerankTopK", properties.getRerankTopK(),
                "route", "docvec_experiment"
        );
    }

    @PostMapping("/ask")
    public DocVecAskResponse ask(@Valid @RequestBody DocVecAskRequest request) {
        return askService.ask(request);
    }
}
