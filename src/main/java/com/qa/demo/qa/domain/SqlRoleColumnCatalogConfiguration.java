package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SqlRoleColumnCatalogConfiguration {

    @Bean
    public SqlRoleColumnCatalog sqlRoleColumnCatalog(ObjectMapper objectMapper) {
        return SqlRoleColumnCatalog.loadDefault(objectMapper);
    }
}
