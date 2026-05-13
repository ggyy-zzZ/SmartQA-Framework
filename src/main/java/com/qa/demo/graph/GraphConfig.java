package com.qa.demo.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(GraphProperties.class)
public class GraphConfig {

    @Bean
    public Driver neo4jDriver(GraphProperties graphProperties) {
        if (StringUtils.hasText(graphProperties.getUsername())) {
            return GraphDatabase.driver(
                    graphProperties.getUri(),
                    AuthTokens.basic(graphProperties.getUsername(), graphProperties.getPassword())
            );
        }
        return GraphDatabase.driver(graphProperties.getUri(), AuthTokens.none());
    }
}
