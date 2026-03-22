package com.jobrecommender.backend.dto;

public record SkillCentralityDTO(
        String skillName,
        long jobCount,
        double marketCoveragePct,
        String criticality
) {
}
