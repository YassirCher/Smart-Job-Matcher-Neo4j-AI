package com.jobrecommender.backend.dto;

public record SkillNeighborDTO(
        String skillName,
        long cooccurrenceWeight
) {
}
