package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.dto.JobRecommendationProjection;
import com.jobrecommender.backend.dto.SemanticRecommendationDTO;
import com.jobrecommender.backend.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;

    @GetMapping("/{candidateId}")
    public List<JobRecommendationProjection> getRecommendations(@PathVariable String candidateId) {
        return recommendationService.getRecommendationsForCandidate(candidateId);
    }

    @GetMapping("/{candidateId}/semantic")
    public List<SemanticRecommendationDTO> getSemanticRecommendations(
            @PathVariable String candidateId,
            @RequestParam(defaultValue = "0.8") double threshold,
            @RequestParam(defaultValue = "5") int topJobs,
            @RequestParam(defaultValue = "20") int topKPerSkill,
            @RequestParam(defaultValue = "384") int embeddingDim
    ) {
        return recommendationService.getSemanticRecommendationsForCandidate(
                candidateId,
                threshold,
                topJobs,
                topKPerSkill,
                embeddingDim
        );
    }
}
