package com.jobrecommender.backend.service;

import com.jobrecommender.backend.dto.GitHubProfileAnalysisResult;
import com.jobrecommender.backend.dto.GitHubProfileAnalyzeRequest;
import com.jobrecommender.backend.dto.GitHubSkillApplyResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GitHubProfileAnalyzerService {

    private final CandidateService candidateService;
    private final GitHubIngestionService gitHubIngestionService;
    private final GroqQwenSkillExtractionService groqQwenSkillExtractionService;
    private final SkillReconciliationService skillReconciliationService;
        private final PendingGitHubAnalysisStore pendingGitHubAnalysisStore;

    public GitHubProfileAnalysisResult analyzeCandidateProfile(String candidateId, GitHubProfileAnalyzeRequest request) {
        long start = System.currentTimeMillis();
                                candidateService.ensureExists(candidateId);

        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (!StringUtils.hasText(request.githubUsername()) && (request.repositoryUrls() == null || request.repositoryUrls().isEmpty())) {
            throw new IllegalArgumentException("githubUsername or repositoryUrls must be provided");
        }

        GitHubIngestionService.GitHubReadmePayload payload = gitHubIngestionService.ingest(
                request.githubUsername(),
                request.repositoryUrls(),
                request.maxRepos(),
                request.includeForks()
        );

        GroqQwenSkillExtractionService.ExtractionResult extraction = groqQwenSkillExtractionService.extractSkills(payload);
        SkillReconciliationService.ReconciliationResult reconciliation = skillReconciliationService.reconcile(extraction.skills());

        List<String> skillIdsToAttach = reconciliation.matched().stream()
                .map(SkillReconciliationService.MatchedSkill::matchedSkillId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        String analysisId = pendingGitHubAnalysisStore.put(candidateId, skillIdsToAttach);

        List<GitHubProfileAnalysisResult.MatchedSkillResult> matched = reconciliation.matched().stream()
                .map(m -> new GitHubProfileAnalysisResult.MatchedSkillResult(
                        m.sourceLabel(),
                        m.canonicalCandidate(),
                        m.matchedSkillId(),
                        m.matchedSkillName(),
                        m.strategy(),
                        m.llmConfidence(),
                        m.similarityScore(),
                        m.evidence()
                ))
                .toList();

        List<GitHubProfileAnalysisResult.RejectedSkillResult> rejected = reconciliation.rejected().stream()
                .map(r -> new GitHubProfileAnalysisResult.RejectedSkillResult(
                        r.sourceLabel(),
                        r.canonicalCandidate(),
                        r.reason(),
                        r.llmConfidence(),
                        r.bestSimilarityScore(),
                        r.bestSkillName(),
                        r.evidence()
                ))
                .toList();

                List<GitHubProfileAnalysisResult.CategorySummary> categories = buildCategorySummary(extraction.skills());
                List<GitHubProfileAnalysisResult.ConfidenceBucket> confidenceBuckets = buildConfidenceBuckets(extraction.skills());

        long durationMs = System.currentTimeMillis() - start;
        return new GitHubProfileAnalysisResult(
                candidateId,
                                analysisId,
                request.githubUsername(),
                payload.repositoriesDiscovered(),
                payload.repositories().size(),
                extraction.skills().size(),
                skillIdsToAttach.size(),
                                matched,
                rejected,
                                categories,
                                confidenceBuckets,
                durationMs
        );
    }

        public GitHubSkillApplyResult applyAnalyzedSkills(String candidateId, String analysisId) {
                candidateService.ensureExists(candidateId);
                List<String> skillIds = pendingGitHubAnalysisStore.consume(candidateId, analysisId);
                candidateService.attachSkillIds(candidateId, skillIds);
                return new GitHubSkillApplyResult(
                                candidateId,
                                analysisId,
                                skillIds.size(),
                                System.currentTimeMillis()
                );
        }

        private List<GitHubProfileAnalysisResult.CategorySummary> buildCategorySummary(
                        List<GroqQwenSkillExtractionService.ExtractedSkill> extracted
        ) {
                if (extracted == null || extracted.isEmpty()) {
                        return List.of();
                }
                Map<String, double[]> stats = new LinkedHashMap<>();
                for (GroqQwenSkillExtractionService.ExtractedSkill skill : extracted) {
                        String key = StringUtils.hasText(skill.category()) ? skill.category() : "tool";
                        double[] bucket = stats.computeIfAbsent(key, k -> new double[]{0.0d, 0.0d});
                        bucket[0] += 1.0d;
                        bucket[1] += skill.confidence();
                }
                return stats.entrySet().stream()
                                .map(e -> new GitHubProfileAnalysisResult.CategorySummary(
                                                e.getKey(),
                                                (int) e.getValue()[0],
                                                e.getValue()[1] / Math.max(1.0d, e.getValue()[0])
                                ))
                                .sorted(Comparator.comparingInt(GitHubProfileAnalysisResult.CategorySummary::count).reversed())
                                .toList();
        }

        private List<GitHubProfileAnalysisResult.ConfidenceBucket> buildConfidenceBuckets(
                        List<GroqQwenSkillExtractionService.ExtractedSkill> extracted
        ) {
                if (extracted == null || extracted.isEmpty()) {
                        return List.of(
                                        new GitHubProfileAnalysisResult.ConfidenceBucket("0.00-0.50", 0),
                                        new GitHubProfileAnalysisResult.ConfidenceBucket("0.50-0.75", 0),
                                        new GitHubProfileAnalysisResult.ConfidenceBucket("0.75-0.90", 0),
                                        new GitHubProfileAnalysisResult.ConfidenceBucket("0.90-1.00", 0)
                        );
                }

                int low = 0;
                int medium = 0;
                int high = 0;
                int veryHigh = 0;
                for (GroqQwenSkillExtractionService.ExtractedSkill skill : extracted) {
                        double c = Math.max(0.0d, Math.min(1.0d, skill.confidence()));
                        if (c < 0.50d) {
                                low++;
                        } else if (c < 0.75d) {
                                medium++;
                        } else if (c < 0.90d) {
                                high++;
                        } else {
                                veryHigh++;
                        }
                }

                return List.of(
                                new GitHubProfileAnalysisResult.ConfidenceBucket("0.00-0.50", low),
                                new GitHubProfileAnalysisResult.ConfidenceBucket("0.50-0.75", medium),
                                new GitHubProfileAnalysisResult.ConfidenceBucket("0.75-0.90", high),
                                new GitHubProfileAnalysisResult.ConfidenceBucket("0.90-1.00", veryHigh)
                );
        }
}
