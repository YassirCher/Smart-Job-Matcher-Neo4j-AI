package com.jobrecommender.backend.dto;

import java.util.List;

public record CareerPathPredictionResultDTO(
        String candidateId,
        String candidateName,
        List<String> currentSkills,
        List<CareerPathPredictionSkillDTO> recommendedSkills,
        String coachingMessage,
        boolean coachingFromLlm,
        long generatedAtEpochMs
) {
}
