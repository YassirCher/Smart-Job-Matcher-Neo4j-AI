package com.jobrecommender.backend.dto;

import com.jobrecommender.backend.entity.Job;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobRecommendationProjection {
    private Job job;
    private String jobTitle;
    private String jobLink;
    private String jobType;
    private String jobLevel;
    private int matchingSkills;
    private int totalSkills;
    private double score;
    private List<String> matchedSkillsList;
}
