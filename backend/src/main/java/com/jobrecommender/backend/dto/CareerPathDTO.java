package com.jobrecommender.backend.dto;

import java.util.List;

public record CareerPathDTO(
        String jobLink,
        String title,
        String type,
        String level,
        int missingSkills,
        List<CareerPathStepDTO> bridgeSteps
) {
}
