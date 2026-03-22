package com.jobrecommender.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "career.predictor")
public class CareerPathPredictorProperties {

    private boolean enabled = true;

    private int topK = 3;

    private int maxRemainingGaps = 1;

    private int maxCandidateSkills = 20;

    private int candidatePoolSize = 120;

    private int contextSkillsLimit = 25;
}
