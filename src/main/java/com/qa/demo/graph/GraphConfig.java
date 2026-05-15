package com.qa.demo.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(GraphProperties.class)
public class GraphConfig {

    @Bean
    public Driver neo4jDriver(GraphProperties graphProperties) {
        Config config = Config.builder()
                .withConnectionTimeout(5000, TimeUnit.MILLISECONDS)
                .withConnectionAcquisitionTimeout(10000, TimeUnit.MILLISECONDS)
                .withMaxConnectionLifetime(5, TimeUnit.MINUTES)
                .build();

        if (StringUtils.hasText(graphProperties.getUsername())) {
            return GraphDatabase.driver(
                    graphProperties.getUri(),
                    AuthTokens.basic(graphProperties.getUsername(), graphProperties.getPassword()),
                    config
            );
        }
        return GraphDatabase.driver(graphProperties.getUri(), AuthTokens.none(), config);
    }
}
