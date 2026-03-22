package com.jobrecommender.backend.service;

import com.jobrecommender.backend.dto.GitHubProfileAnalysisResult;
import com.jobrecommender.backend.dto.GitHubProfileAnalyzeRequest;
import com.jobrecommender.backend.dto.ResumePortfolioApplyResult;
import com.jobrecommender.backend.dto.ResumePortfolioDeltaResponse;
import com.jobrecommender.backend.dto.ResumePortfolioUploadResponse;
import com.jobrecommender.backend.entity.Candidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePortfolioIntelligenceService {

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile("https?://(?:www\\.)?github\\.com/([A-Za-z0-9-]{1,39})(?:/[^\\s?#]+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_HANDLE_PATTERN = Pattern.compile("(?:github|git hub)\\s*[:@-]?\\s*([A-Za-z0-9-]{1,39})", Pattern.CASE_INSENSITIVE);

    private static final long MAX_FILE_SIZE_BYTES = 8L * 1024L * 1024L;

    private final CandidateService candidateService;
    private final GitHubProfileAnalyzerService gitHubProfileAnalyzerService;
    private final GroqQwenSkillExtractionService groqQwenSkillExtractionService;
    private final ReadmeNlpPreprocessor readmeNlpPreprocessor;
    private final SkillCanonicalizationService skillCanonicalizationService;
    private final PendingResumeEvidenceStore pendingResumeEvidenceStore;

    private final Tika tika = new Tika();

    public ResumePortfolioUploadResponse uploadAndAnalyze(
            String candidateId,
            MultipartFile file,
            boolean autoAnalyzeGithub
    ) {
        candidateService.ensureExists(candidateId);
        validateFile(file);

        String filename = sanitizeFileName(file.getOriginalFilename());
        log.info("[CV_UPLOAD] candidateId={} fileName={} size={}B", candidateId, filename, file.getSize());

        String extractedText = extractRawText(file);
        String compressedText = readmeNlpPreprocessor.compressFreeText(extractedText, 50000, 1800);

        GroqQwenSkillExtractionService.ExtractionResult cvExtraction =
                groqQwenSkillExtractionService.extractSkillsFromCvText(compressedText);

        List<String> cvSkills = canonicalizeSkillLabels(
                cvExtraction.skills().stream().map(GroqQwenSkillExtractionService.ExtractedSkill::label).toList()
        );

        GitHubDetection detection = detectGitHub(extractedText);
        pendingResumeEvidenceStore.put(candidateId, compressedText, detection.username());
        if (detection.detected()) {
            log.info("[GITHUB_DETECTED] candidateId={} username={} profileUrl={}",
                    candidateId, detection.username(), detection.profileUrl());
        } else {
            log.info("[GITHUB_DETECTED] candidateId={} username=NONE", candidateId);
        }

        GitHubProfileAnalysisResult githubAnalysis = null;
        List<String> githubSkills = List.of();
        boolean analysisTriggered = false;
        boolean analysisPending = detection.detected();

        if (detection.detected() && autoAnalyzeGithub) {
            try {
            analysisTriggered = true;
            analysisPending = false;
            githubAnalysis = gitHubProfileAnalyzerService.analyzeCandidateProfile(
                candidateId,
                new GitHubProfileAnalyzeRequest(detection.username(), List.of(), 12, false)
            );
            githubSkills = canonicalizeSkillLabels(
                githubAnalysis.matched().stream().map(GitHubProfileAnalysisResult.MatchedSkillResult::matchedSkillName).toList()
            );
            } catch (RuntimeException ex) {
            log.warn("[GITHUB_ANALYZE_FAIL] candidateId={} username={} message={}",
                candidateId,
                detection.username(),
                ex.getMessage());
            analysisTriggered = false;
            analysisPending = true;
            githubAnalysis = null;
            githubSkills = List.of();
            }
        }

        ResumePortfolioDeltaResponse delta = computeDelta(cvSkills, githubSkills);
        log.info("[DELTA_CALCULATED] candidateId={} validated={} claimed={} hidden={}",
                candidateId,
                delta.validatedCount(),
                delta.claimedButUnverifiedCount(),
                delta.hiddenGemsCount());

        return new ResumePortfolioUploadResponse(
                candidateId,
                filename,
                detection.username(),
                detection.profileUrl(),
                detection.detected(),
                analysisTriggered,
                analysisPending,
                cvSkills,
                githubSkills,
                delta.validatedSkills(),
                delta.claimedButUnverified(),
                delta.hiddenGems(),
                githubAnalysis
        );
    }

    public ResumePortfolioDeltaResponse computeDelta(List<String> cvSkillsRaw, List<String> githubSkillsRaw) {
        List<String> cvSkills = canonicalizeSkillLabels(cvSkillsRaw);
        List<String> githubSkills = canonicalizeSkillLabels(githubSkillsRaw);

        LinkedHashSet<String> cvSet = new LinkedHashSet<>(cvSkills);
        LinkedHashSet<String> githubSet = new LinkedHashSet<>(githubSkills);

        List<String> validated = cvSet.stream().filter(githubSet::contains).toList();
        List<String> claimedButUnverified = cvSet.stream().filter(skill -> !githubSet.contains(skill)).toList();
        List<String> hiddenGems = githubSet.stream().filter(skill -> !cvSet.contains(skill)).toList();

        return new ResumePortfolioDeltaResponse(
                validated,
                claimedButUnverified,
                hiddenGems,
                validated.size(),
                claimedButUnverified.size(),
                hiddenGems.size()
        );
    }

    public ResumePortfolioApplyResult applySelectedSkills(String candidateId, List<String> selectedSkills) {
        Candidate candidate = candidateService.attachSkillNames(candidateId, selectedSkills);
        int attached = selectedSkills == null ? 0 : (int) selectedSkills.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(v -> v.toLowerCase(Locale.ROOT))
                .distinct()
                .count();

        return new ResumePortfolioApplyResult(candidateId, attached, candidate);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Resume file is required");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Resume file is too large (max 8MB)");
        }

        String filename = sanitizeFileName(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        boolean acceptedExt = filename.endsWith(".pdf") || filename.endsWith(".doc") || filename.endsWith(".docx");
        if (!acceptedExt) {
            throw new IllegalArgumentException("Unsupported file type. Allowed: PDF, DOC, DOCX");
        }
    }

    private String extractRawText(MultipartFile file) {
        try {
            String raw = tika.parseToString(file.getInputStream());
            if (!StringUtils.hasText(raw)) {
                throw new IllegalArgumentException("Unable to extract text from CV");
            }
            return raw;
        } catch (Exception ex) {
            throw new IllegalStateException("CV extraction failed", ex);
        }
    }

    private GitHubDetection detectGitHub(String text) {
        String safe = text == null ? "" : text;

        Matcher urlMatcher = GITHUB_URL_PATTERN.matcher(safe);
        if (urlMatcher.find()) {
            String username = urlMatcher.group(1);
            String profileUrl = "https://github.com/" + username;
            return new GitHubDetection(true, username, profileUrl);
        }

        Matcher handleMatcher = GITHUB_HANDLE_PATTERN.matcher(safe);
        if (handleMatcher.find()) {
            String username = handleMatcher.group(1);
            String profileUrl = "https://github.com/" + username;
            return new GitHubDetection(true, username, profileUrl);
        }

        return new GitHubDetection(false, "", "");
    }

    private List<String> canonicalizeSkillLabels(List<String> rawSkills) {
        if (rawSkills == null || rawSkills.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawSkills) {
            String canonical = skillCanonicalizationService.canonicalize(raw);
            if (StringUtils.hasText(canonical)) {
                out.add(canonical);
            }
        }
        return new ArrayList<>(out);
    }

    private String sanitizeFileName(String name) {
        if (!StringUtils.hasText(name)) {
            return "resume";
        }
        return name.replace("\\", "_").replace("/", "_").trim();
    }

    private record GitHubDetection(boolean detected, String username, String profileUrl) {
    }
}
