package com.jobrecommender.backend.dto;

import com.jobrecommender.backend.entity.Candidate;

public record ResumePortfolioApplyResult(
        String candidateId,
        int attachedSkills,
        Candidate candidate
) {
}
