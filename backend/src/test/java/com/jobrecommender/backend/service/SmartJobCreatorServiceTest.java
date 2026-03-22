package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.GroqProperties;
import com.jobrecommender.backend.dto.SmartJobParseResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartJobCreatorServiceTest {

    @Test
    void parseRawDescription_shouldFallbackWhenApiKeyMissing() {
        GroqProperties props = new GroqProperties();
        props.setApiKey("");

        SmartJobCreatorService service = new SmartJobCreatorService(props);
        SmartJobParseResponse response = service.parseRawDescription(
                "Senior Data Scientist at ACME in Paris. Full-time role with Python and Neo4j."
        );

        assertFalse(response.success());
        assertFalse(response.fromLlm());
        assertTrue(response.warnings().stream().anyMatch(w -> w.toLowerCase().contains("manuel")));
        assertTrue(response.inferredFields().contains("title"));
        assertTrue(response.inferredFields().contains("skills[].name"));
    }

    @Test
    void parseRawDescription_shouldHandleUnusableTextWithoutBlocking() {
        GroqProperties props = new GroqProperties();
        props.setApiKey("dummy-key");

        SmartJobCreatorService service = new SmartJobCreatorService(props);
        SmartJobParseResponse response = service.parseRawDescription("???");

        assertFalse(response.success());
        assertFalse(response.fromLlm());
        assertTrue(response.warnings().stream().anyMatch(w -> w.toLowerCase().contains("incomprehensible") || w.toLowerCase().contains("incomprehensible") || w.toLowerCase().contains("court")));
    }
}