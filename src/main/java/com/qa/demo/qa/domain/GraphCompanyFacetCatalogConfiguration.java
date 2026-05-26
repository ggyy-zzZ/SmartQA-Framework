package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GraphCompanyFacetCatalogConfiguration {

    @Bean
    public GraphCompanyFacetCatalog graphCompanyFacetCatalog(ObjectMapper objectMapper) {
        return GraphCompanyFacetCatalog.loadDefault(objectMapper);
    }
}
