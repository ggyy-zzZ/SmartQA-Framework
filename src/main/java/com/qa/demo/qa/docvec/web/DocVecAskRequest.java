package com.qa.demo.qa.docvec.web;

import jakarta.validation.constraints.NotBlank;

public record DocVecAskRequest(
        @NotBlank String question,
        String conversationId,
        Boolean followUp,
        Integer topK,
        Integer rerankTopK
) {
}
