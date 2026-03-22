package com.jobrecommender.backend.dto;

public record GitHubSkillApplyResult(
        String candidateId,
        String analysisId,
        int attachedSkills,
        long appliedAtEpochMs
) {
}
