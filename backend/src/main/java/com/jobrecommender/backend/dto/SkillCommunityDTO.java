package com.jobrecommender.backend.dto;

import java.util.List;

public record SkillCommunityDTO(
        String seedSkill,
        int degree,
        List<SkillNeighborDTO> topNeighbors
) {
}
