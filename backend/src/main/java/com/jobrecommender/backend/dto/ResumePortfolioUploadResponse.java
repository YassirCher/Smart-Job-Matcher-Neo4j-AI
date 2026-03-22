package com.jobrecommender.backend.dto;

import java.util.List;

public record ResumePortfolioUploadResponse(
        String candidateId,
        String fileName,
        String detectedGithubUsername,
        String detectedGithubUrl,
        boolean githubDetected,
        boolean githubAnalysisTriggered,
        boolean githubAnalysisPending,
        List<String> cvClaimedSkills,
        List<String> githubSkills,
        List<String> validatedSkills,
        List<String> claimedButUnverified,
        List<String> hiddenGems,
        GitHubProfileAnalysisResult githubAnalysis
) {
}
