package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import com.qa.demo.qa.config.store.EnumCatalogRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GraphCompanyFacetCatalogConfiguration {

    @Bean
    public GraphCompanyFacetCatalog graphCompanyFacetCatalog(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) throws Exception {
        return GraphCompanyFacetCatalog.loadDefault(objectMapper, configLoader);
    }

    @Bean
    public CertificateSealEnumCatalog certificateSealEnumCatalog(
            ObjectMapper objectMapper,
            EnumCatalogRepository enumCatalogRepository,
            QaAssistantProperties properties
    ) {
        String scope = properties.getConfigScope();
        if (enumCatalogRepository.hasDict(scope, "certificateTypes")
                && enumCatalogRepository.hasDict(scope, "sealTypes")) {
            return new CertificateSealEnumCatalog(
                    enumCatalogRepository.entryMap(scope, "certificateTypes"),
                    enumCatalogRepository.entryMap(scope, "sealTypes")
            );
        }
        return CertificateSealEnumCatalog.loadDefault(objectMapper);
    }
}
