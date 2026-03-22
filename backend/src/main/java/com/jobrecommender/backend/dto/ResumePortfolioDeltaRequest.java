package com.jobrecommender.backend.dto;

import java.util.List;

public record ResumePortfolioDeltaRequest(
        List<String> cvSkills,
        List<String> githubSkills
) {
}
