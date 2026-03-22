package com.jobrecommender.backend.dto;

import java.util.List;

public record GitHubProfileAnalyzeRequest(
        String githubUsername,
        List<String> repositoryUrls,
        Integer maxRepos,
        Boolean includeForks
) {
}
