package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.entity.Candidate;
import com.jobrecommender.backend.entity.Skill;
import com.jobrecommender.backend.dto.ResumePortfolioApplyRequest;
import com.jobrecommender.backend.dto.ResumePortfolioApplyResult;
import com.jobrecommender.backend.dto.ResumePortfolioDeltaRequest;
import com.jobrecommender.backend.dto.ResumePortfolioDeltaResponse;
import com.jobrecommender.backend.dto.ResumePortfolioUploadResponse;
import com.jobrecommender.backend.dto.InterviewScriptJobResponse;
import com.jobrecommender.backend.dto.InterviewScriptStartRequest;
import com.jobrecommender.backend.dto.BehavioralProfileResponse;
import com.jobrecommender.backend.dto.SoftSkillAnalyzeStartRequest;
import com.jobrecommender.backend.dto.SoftSkillAnalysisJobResponse;
import com.jobrecommender.backend.dto.CandidateListItemDTO;
import com.jobrecommender.backend.service.CandidateService;
import com.jobrecommender.backend.service.DynamicInterviewGeneratorService;
import com.jobrecommender.backend.service.GitHubProfileAnalyzerService;
import com.jobrecommender.backend.service.ResumePortfolioIntelligenceService;
import com.jobrecommender.backend.service.SoftSkillBehavioralProfileService;
import com.jobrecommender.backend.dto.GitHubProfileAnalyzeRequest;
import com.jobrecommender.backend.dto.GitHubProfileAnalysisResult;
import com.jobrecommender.backend.dto.GitHubSkillApplyRequest;
import com.jobrecommender.backend.dto.GitHubSkillApplyResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/candidates")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateService candidateService;
    private final GitHubProfileAnalyzerService gitHubProfileAnalyzerService;
    private final ResumePortfolioIntelligenceService resumePortfolioIntelligenceService;
    private final DynamicInterviewGeneratorService dynamicInterviewGeneratorService;
    private final SoftSkillBehavioralProfileService softSkillBehavioralProfileService;

    @GetMapping
    public Page<Candidate> getAllCandidates(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return candidateService.findAll(PageRequest.of(page, size));
    }

    @GetMapping("/list")
    public Page<CandidateListItemDTO> getCandidateSummaries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return candidateService.findAllSummaries(PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public Candidate getCandidateById(@PathVariable String id) {
        return candidateService.findById(id);
    }

    @PostMapping
    public Candidate createCandidate(@RequestBody Candidate candidate) {
        return candidateService.create(candidate);
    }

    @PostMapping("/{id}/skills/{skillId}")
    public Candidate addSkillToCandidate(@PathVariable String id, @PathVariable String skillId) {
        return candidateService.addSkillToCandidate(id, skillId);
    }

    @PostMapping("/{id}/skills")
    public Candidate addSkillToCandidateByName(@PathVariable String id, @RequestBody Skill skill) {
        if (skill == null) {
            throw new IllegalArgumentException("Skill payload is required");
        }
        return candidateService.addSkillToCandidateByName(id, skill.getName());
    }

    @PostMapping("/{id}/cv")
    public Candidate uploadCv(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        Candidate candidate = candidateService.findById(id);
        candidate.setResumePath("/uploads/" + file.getOriginalFilename());
        return candidateService.create(candidate);
    }

    @DeleteMapping("/{id}")
    public void deleteCandidate(@PathVariable String id) {
        candidateService.delete(id);
    }

    @PostMapping("/{id}/github/analyze")
    public GitHubProfileAnalysisResult analyzeGitHubProfile(
            @PathVariable String id,
            @RequestBody GitHubProfileAnalyzeRequest request
    ) {
        return gitHubProfileAnalyzerService.analyzeCandidateProfile(id, request);
    }

    @PostMapping("/{id}/github/apply")
    public GitHubSkillApplyResult applyGitHubAnalysis(
            @PathVariable String id,
            @RequestBody GitHubSkillApplyRequest request
    ) {
        return gitHubProfileAnalyzerService.applyAnalyzedSkills(id, request.analysisId());
    }

    @PostMapping(value = "/{id}/resume-intelligence/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResumePortfolioUploadResponse uploadResumeAndAnalyze(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean autoAnalyzeGithub
    ) {
        return resumePortfolioIntelligenceService.uploadAndAnalyze(id, file, autoAnalyzeGithub);
    }

    @PostMapping("/{id}/resume-intelligence/delta")
    public ResumePortfolioDeltaResponse computeResumeDelta(
            @PathVariable String id,
            @RequestBody ResumePortfolioDeltaRequest request
    ) {
        candidateService.ensureExists(id);
        return resumePortfolioIntelligenceService.computeDelta(request.cvSkills(), request.githubSkills());
    }

    @PostMapping("/{id}/resume-intelligence/apply")
    public ResumePortfolioApplyResult applyResumeIntelligenceSkills(
            @PathVariable String id,
            @RequestBody ResumePortfolioApplyRequest request
    ) {
        return resumePortfolioIntelligenceService.applySelectedSkills(id, request.selectedSkills());
    }

    @PostMapping("/{id}/resume-intelligence/interview-script/start")
    public InterviewScriptJobResponse startDynamicInterviewScript(
            @PathVariable String id,
            @RequestBody InterviewScriptStartRequest request
    ) {
        Candidate candidate = candidateService.findById(id);
        return dynamicInterviewGeneratorService.startGeneration(
                id,
                candidate.getName(),
                request.claimedButUnverified(),
                request.targetQuestions()
        );
    }

    @GetMapping("/{id}/resume-intelligence/interview-script/{jobId}")
    public InterviewScriptJobResponse getDynamicInterviewScriptStatus(
            @PathVariable String id,
            @PathVariable String jobId
    ) {
        candidateService.ensureExists(id);
        return dynamicInterviewGeneratorService.getJob(id, jobId);
    }

    @PostMapping("/{id}/behavioral-profile/start")
    public SoftSkillAnalysisJobResponse startBehavioralProfileAnalysis(
            @PathVariable String id,
            @RequestBody(required = false) SoftSkillAnalyzeStartRequest request
    ) {
        candidateService.ensureExists(id);
        SoftSkillAnalyzeStartRequest safeRequest = request == null
                ? new SoftSkillAnalyzeStartRequest("", "", List.of(), null, null)
                : request;
        return softSkillBehavioralProfileService.startAnalysis(id, safeRequest);
    }

    @GetMapping("/{id}/behavioral-profile/{jobId}")
    public SoftSkillAnalysisJobResponse getBehavioralProfileJob(
            @PathVariable String id,
            @PathVariable String jobId
    ) {
        candidateService.ensureExists(id);
        return softSkillBehavioralProfileService.getJob(id, jobId);
    }

    @GetMapping("/{id}/behavioral-profile")
    public BehavioralProfileResponse getBehavioralProfile(
            @PathVariable String id
    ) {
        return softSkillBehavioralProfileService.getBehavioralProfile(id);
    }
}