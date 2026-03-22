package com.jobrecommender.backend.dto;

import java.util.List;

public record GitHubProfileAnalysisResult(
        String candidateId,
        String analysisId,
        String githubUsername,
        int repositoriesAnalyzed,
        int repositoriesWithReadme,
        int extractedSkills,
        int skillsReadyToAttach,
        List<MatchedSkillResult> matched,
        List<RejectedSkillResult> rejected,
        List<CategorySummary> categories,
        List<ConfidenceBucket> confidenceBuckets,
        long durationMs
) {

    public record MatchedSkillResult(
            String sourceLabel,
            String canonicalCandidate,
            String matchedSkillId,
            String matchedSkillName,
            String matchStrategy,
            double llmConfidence,
            Double similarityScore,
            List<String> evidence
    ) {
    }

    public record RejectedSkillResult(
            String sourceLabel,
            String canonicalCandidate,
            String reason,
            double llmConfidence,
            Double bestSimilarityScore,
            String bestSkillName,
            List<String> evidence
    ) {
    }

    public record CategorySummary(
            String category,
            int count,
            double averageConfidence
    ) {
    }

    public record ConfidenceBucket(
            String bucket,
            int count
    ) {
    }
}
