package com.qa.demo.qa.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String SCHEMA_CACHE = "schemaCache";
    public static final String RETRIEVAL_CACHE = "retrievalCache";
    public static final String LLM_RESPONSE_CACHE = "llmResponseCache";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(SCHEMA_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .build());
        cacheManager.registerCustomCache(RETRIEVAL_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .build());
        cacheManager.registerCustomCache(LLM_RESPONSE_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .build());
        return cacheManager;
    }
}