package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.GroqProperties;
import com.jobrecommender.backend.dto.CareerPathPredictionSkillDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CareerPathCoachServiceTest {

    @Test
    void buildCoachingMessage_shouldUseFallbackWhenApiKeyMissing() {
        GroqProperties props = new GroqProperties();
        props.setApiKey("");

        CareerPathCoachService service = new CareerPathCoachService(props);

        CareerPathCoachService.CoachingResult result = service.buildCoachingMessage(
                "Yassir",
                List.of("python", "neo4j"),
                List.of(new CareerPathPredictionSkillDTO(
                        "kubernetes",
                        10,
                        8,
                        3,
                        9.4,
                        76.2,
                        List.of("Senior MLOps Engineer")
                ))
        );

        assertFalse(result.fromLlm());
        assertTrue(result.message().toLowerCase().contains("kubernetes"));
        assertTrue(result.message().contains("- "));
    }
}
