package com.jobrecommender.backend.dto;

public record SoftSkillEvidence(
        String softSkillName,
        double confidence,
        String evidence
) {
}
