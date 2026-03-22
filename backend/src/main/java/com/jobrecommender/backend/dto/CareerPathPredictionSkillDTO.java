package com.jobrecommender.backend.dto;

import java.util.List;

public record CareerPathPredictionSkillDTO(
        String skillName,
        long cooccurrenceSupport,
        long unlockableJobs,
        long seniorUnlockableJobs,
        double compensationLiftScore,
        double linkPredictionScore,
        List<String> sampleJobs
) {
}
