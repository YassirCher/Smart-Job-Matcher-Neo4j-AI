package com.jobrecommender.backend.dto;

import com.jobrecommender.backend.entity.Job;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationResultDTO {
    private Job job;
    private int matchingSkills;
    private int totalSkillsRequired;
    private double score;
}
