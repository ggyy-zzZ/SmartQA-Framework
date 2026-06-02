package com.qa.demo.qa.cdc.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CdcGraphSyncConfiguration {

    @Bean
    public CdcGraphSyncCatalog cdcGraphSyncCatalog(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) throws Exception {
        return CdcGraphSyncCatalog.loadDefault(objectMapper, configLoader);
    }
}
