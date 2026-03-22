package com.jobrecommender.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticRecommendationDTO {
    private SemanticRecommendedJobDTO matchedJob;
    private double semanticScore;
    private int semanticMatchedSkills;
    private int totalRequiredSkills;
    private List<SemanticMatchExplanationDTO> explanations;
}
