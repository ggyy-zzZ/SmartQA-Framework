package com.qa.demo.qa.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EnterpriseLexiconConfiguration {

    @Bean
    public EnterpriseLexicon enterpriseLexicon(ObjectMapper objectMapper) {
        return EnterpriseLexicon.loadDefault(objectMapper);
    }
}
