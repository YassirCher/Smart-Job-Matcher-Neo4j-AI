package com.jobrecommender.backend.dto;

import java.util.List;

public record CandidateListItemDTO(
        String id,
        String name,
        String email,
        String resumePath,
        long skillCount,
        List<String> topSkills
) {
}
