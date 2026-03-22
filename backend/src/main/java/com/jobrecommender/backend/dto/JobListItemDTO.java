package com.jobrecommender.backend.dto;

import java.util.List;

public record JobListItemDTO(
        String jobLink,
        String title,
        String type,
        String level,
        String companyName,
        String locationName,
        List<String> skills
) {
}
