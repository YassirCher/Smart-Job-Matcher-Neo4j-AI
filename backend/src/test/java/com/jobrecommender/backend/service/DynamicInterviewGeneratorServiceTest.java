package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.GroqProperties;
import com.jobrecommender.backend.config.SkillGovernanceProperties;
import com.jobrecommender.backend.dto.InterviewScriptJobResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicInterviewGeneratorServiceTest {

    @Test
    void startGeneration_shouldCompleteWithFallbackWhenApiKeyMissing() throws Exception {
        GroqProperties groqProperties = new GroqProperties();
        groqProperties.setApiKey("");
        groqProperties.setInterviewPollMs(20);

        DynamicInterviewGeneratorService service = buildService(groqProperties);
        try {
            InterviewScriptJobResponse started = service.startGeneration(
                    "cand-1",
                    "Yassir",
                    java.util.List.of("Java", "Kubernetes"),
                    4
            );

            InterviewScriptJobResponse completed = waitForCompletion(service, "cand-1", started.jobId());
            assertEquals("completed", completed.status());
            assertTrue(completed.fallbackUsed());
            assertEquals("fallback-template", completed.model());
            assertEquals(4, completed.questions().size());
            assertTrue(completed.questions().stream().anyMatch(q -> q.skill().contains("java")));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void startGeneration_shouldUseTpmGuardBeforeNetworkCall() throws Exception {
        GroqProperties groqProperties = new GroqProperties();
        groqProperties.setApiKey("dummy-key");
        groqProperties.setInterviewTpmBudget(1);
        groqProperties.setInterviewPollMs(20);

        DynamicInterviewGeneratorService service = buildService(groqProperties);
        try {
            InterviewScriptJobResponse started = service.startGeneration(
                    "cand-2",
                    "Yassir",
                    java.util.List.of("neo4j", "sql"),
                    3
            );

            InterviewScriptJobResponse completed = waitForCompletion(service, "cand-2", started.jobId());
            assertEquals("completed", completed.status());
            assertTrue(completed.fallbackUsed());
            assertTrue(completed.message().toLowerCase().contains("tpm"));
            assertFalse(completed.questions().isEmpty());
        } finally {
            service.shutdown();
        }
    }

    private DynamicInterviewGeneratorService buildService(GroqProperties groqProperties) {
        SkillGovernanceProperties skillGovernanceProperties = new SkillGovernanceProperties();
        skillGovernanceProperties.setAliases(Map.of("js", "javascript"));

        SkillCanonicalizationService canonicalizationService = new SkillCanonicalizationService(skillGovernanceProperties);
        return new DynamicInterviewGeneratorService(groqProperties, canonicalizationService);
    }

    private InterviewScriptJobResponse waitForCompletion(
            DynamicInterviewGeneratorService service,
            String candidateId,
            String jobId
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        InterviewScriptJobResponse last = service.getJob(candidateId, jobId);

        while (!"completed".equals(last.status()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            last = service.getJob(candidateId, jobId);
        }

        return last;
    }
}
