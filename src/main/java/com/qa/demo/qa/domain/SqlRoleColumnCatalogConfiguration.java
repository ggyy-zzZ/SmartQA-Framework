package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.store.AssistantConfigJsonLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SqlRoleColumnCatalogConfiguration {

    @Bean
    public SqlRoleColumnCatalog sqlRoleColumnCatalog(ObjectMapper objectMapper, AssistantConfigJsonLoader configLoader) throws Exception {
        return SqlRoleColumnCatalog.loadDefault(objectMapper, configLoader);
    }
}
