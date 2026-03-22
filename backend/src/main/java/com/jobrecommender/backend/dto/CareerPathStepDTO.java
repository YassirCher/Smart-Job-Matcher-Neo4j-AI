package com.jobrecommender.backend.dto;

public record CareerPathStepDTO(
        String sourceSkill,
        String bridgeSkill,
        String targetSkill,
        long supportCount
) {
}
