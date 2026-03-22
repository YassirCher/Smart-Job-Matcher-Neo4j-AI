package com.jobrecommender.backend.dto;

import java.util.List;

public record ResumePortfolioDeltaResponse(
        List<String> validatedSkills,
        List<String> claimedButUnverified,
        List<String> hiddenGems,
        int validatedCount,
        int claimedButUnverifiedCount,
        int hiddenGemsCount
) {
}
