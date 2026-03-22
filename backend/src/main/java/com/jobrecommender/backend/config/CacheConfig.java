package com.jobrecommender.backend.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                CacheNames.LEXICAL_RECOMMENDATIONS,
                CacheNames.SEMANTIC_RECOMMENDATIONS,
                CacheNames.SKILL_CENTRALITY,
                CacheNames.SKILL_COMMUNITIES,
                CacheNames.CAREER_PATHS,
                CacheNames.DATA_FUNNEL,
                CacheNames.DATA_DRIFT,
                CacheNames.CANDIDATE_QUALITY,
                CacheNames.SKILL_GAP_ROADMAP,
                CacheNames.RECOMMENDATION_COMPARISON,
                CacheNames.RECOMMENDATION_COUNTERFACTUAL
        );

        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats());
        return manager;
    }
}
