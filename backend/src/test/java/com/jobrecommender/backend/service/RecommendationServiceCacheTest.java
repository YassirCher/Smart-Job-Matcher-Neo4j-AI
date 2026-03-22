package com.jobrecommender.backend.service;

import com.jobrecommender.backend.dto.JobRecommendationProjection;
import com.jobrecommender.backend.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(classes = RecommendationServiceCacheTest.TestConfig.class)
class RecommendationServiceCacheTest {

    @Autowired
    private RecommendationService recommendationService;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void lexicalRecommendations_shouldBeServedFromCacheOnSecondCall() {
        when(jobRepository.recommendJobsForCandidate("cand-1")).thenReturn(List.<JobRecommendationProjection>of());

        recommendationService.getRecommendationsForCandidate("cand-1");
        recommendationService.getRecommendationsForCandidate("cand-1");

        verify(jobRepository, times(1)).recommendJobsForCandidate("cand-1");
    }

    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("lexicalRecommendations", "semanticRecommendations");
        }

        @Bean
        JobRepository jobRepository() {
            return mock(JobRepository.class);
        }

        @Bean
        Neo4jClient neo4jClient() {
            return mock(Neo4jClient.class);
        }

        @Bean
        Neo4jVectorIndexService neo4jVectorIndexService() {
            return mock(Neo4jVectorIndexService.class);
        }

        @Bean
        RecommendationService recommendationService(
                JobRepository jobRepository,
                Neo4jClient neo4jClient,
                Neo4jVectorIndexService neo4jVectorIndexService
        ) {
            return new RecommendationService(jobRepository, neo4jClient, neo4jVectorIndexService);
        }
    }
}
