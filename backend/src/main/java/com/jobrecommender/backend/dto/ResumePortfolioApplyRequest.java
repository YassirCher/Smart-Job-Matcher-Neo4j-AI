package com.jobrecommender.backend.dto;

import java.util.List;

public record ResumePortfolioApplyRequest(
        List<String> selectedSkills
) {
}
