package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.service.AdvancedAnalyticsService;
import com.jobrecommender.backend.service.CareerPathPredictorService;
import com.jobrecommender.backend.dto.CareerPathPredictionResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AdvancedAnalyticsController {

    private final AdvancedAnalyticsService advancedAnalyticsService;
    private final CareerPathPredictorService careerPathPredictorService;

    @GetMapping("/data-funnel")
    public Map<String, Object> getDataFunnel() {
        return advancedAnalyticsService.getDataFunnel();
    }

    @GetMapping("/data-drift")
    public Map<String, Object> getDataDrift() {
        return advancedAnalyticsService.getDataDriftProxy();
    }

    @GetMapping("/candidates/quality")
    public Map<String, Object> getCandidateQuality(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return advancedAnalyticsService.getCandidateQuality(page, size);
    }

    @GetMapping("/candidates/{candidateId}/skill-gap-roadmap")
    public List<Map<String, Object>> getSkillGapRoadmap(
            @PathVariable String candidateId,
            @RequestParam(defaultValue = "5") int limit
    ) {
        return advancedAnalyticsService.getSkillGapRoadmap(candidateId, limit);
    }

    @GetMapping("/recommendations/{candidateId}/comparison")
    public List<Map<String, Object>> getRecommendationComparison(
            @PathVariable String candidateId,
            @RequestParam(defaultValue = "5") int topJobs
    ) {
        return advancedAnalyticsService.getRecommendationComparison(candidateId, topJobs);
    }

    @GetMapping("/recommendations/{candidateId}/counterfactual")
    public List<Map<String, Object>> getRecommendationCounterfactual(
            @PathVariable String candidateId,
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(defaultValue = "3") int maxMissing
    ) {
        return advancedAnalyticsService.getRecommendationCounterfactual(candidateId, limit, maxMissing);
    }

    @GetMapping("/candidates/{candidateId}/career-path-predictor")
    public CareerPathPredictionResultDTO getCareerPathPrediction(
            @PathVariable String candidateId,
            @RequestParam(required = false) Integer topK,
            @RequestParam(required = false) Integer maxRemainingGaps,
            @RequestParam(defaultValue = "true") boolean withCoaching
    ) {
        return careerPathPredictorService.predictForCandidate(
                candidateId,
                topK,
                maxRemainingGaps,
                withCoaching
        );
    }
}
