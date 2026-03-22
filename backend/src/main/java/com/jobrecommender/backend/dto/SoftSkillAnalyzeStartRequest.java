package com.jobrecommender.backend.dto;

import java.util.List;

public record SoftSkillAnalyzeStartRequest(
        String cvSummary,
        String githubUsername,
        List<String> repositoryUrls,
        Integer maxRepos,
        Boolean includeForks
) {
}
