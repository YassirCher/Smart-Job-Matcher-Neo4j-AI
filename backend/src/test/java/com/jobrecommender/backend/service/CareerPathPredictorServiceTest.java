package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CareerPathPredictorProperties;
import com.jobrecommender.backend.dto.CareerPathPredictionResultDTO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CareerPathPredictorServiceTest {

    @Test
    void predictForCandidate_shouldMapRowsAndUseCoachMessage() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        CareerPathPredictorProperties props = new CareerPathPredictorProperties();
        CareerPathCoachService coachService = mock(CareerPathCoachService.class);

        CareerPathPredictorService service = Mockito.spy(new CareerPathPredictorService(neo4jClient, props, coachService));

        doReturn(Map.of(
                "candidateName", "Yassir",
                "currentSkills", List.of("python", "neo4j")
        )).when(service).fetchCandidateContext(anyString());

        doReturn(List.of(
                Map.of(
                        "skillName", "kubernetes",
                        "cooccurrenceSupport", 12L,
                        "unlockedJobs", 9L,
                        "seniorUnlocked", 4L,
                        "compensationLift", 11.45d,
                        "linkPredictionScore", 78.22d,
                        "sampleJobs", List.of("senior ml engineer", "lead data platform engineer")
                )
        )).when(service).fetchPredictionRows(anyString(), anyInt(), anyInt());

        when(coachService.buildCoachingMessage(anyString(), any(), any()))
                .thenReturn(new CareerPathCoachService.CoachingResult("Coach text", false));

        CareerPathPredictionResultDTO result = service.predictForCandidate("cand-1", 3, 1, true);

        assertEquals("cand-1", result.candidateId());
        assertEquals("Yassir", result.candidateName());
        assertEquals(2, result.currentSkills().size());
        assertEquals(1, result.recommendedSkills().size());
        assertEquals("kubernetes", result.recommendedSkills().get(0).skillName());
        assertEquals("Coach text", result.coachingMessage());
        assertFalse(result.coachingFromLlm());
    }

    @Test
    void predictForCandidate_shouldHandleNoRecommendations() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        CareerPathPredictorProperties props = new CareerPathPredictorProperties();
        CareerPathCoachService coachService = mock(CareerPathCoachService.class);

        CareerPathPredictorService service = Mockito.spy(new CareerPathPredictorService(neo4jClient, props, coachService));

        doReturn(Map.of(
                "candidateName", "Yassir",
                "currentSkills", List.of("java")
        )).when(service).fetchCandidateContext(anyString());

        doReturn(List.of()).when(service).fetchPredictionRows(anyString(), anyInt(), anyInt());

        when(coachService.buildCoachingMessage(anyString(), any(), any()))
                .thenReturn(new CareerPathCoachService.CoachingResult("Aucune reco", false));

        CareerPathPredictionResultDTO result = service.predictForCandidate("cand-2", 3, 1, true);

        assertTrue(result.recommendedSkills().isEmpty());
        assertEquals("Aucune reco", result.coachingMessage());
    }
}
