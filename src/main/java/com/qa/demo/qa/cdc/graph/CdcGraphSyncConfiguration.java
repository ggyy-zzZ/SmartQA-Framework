package com.qa.demo.qa.cdc.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CdcGraphSyncConfiguration {

    @Bean
    public CdcGraphSyncCatalog cdcGraphSyncCatalog(ObjectMapper objectMapper) {
        return CdcGraphSyncCatalog.loadDefault(objectMapper);
    }
}
