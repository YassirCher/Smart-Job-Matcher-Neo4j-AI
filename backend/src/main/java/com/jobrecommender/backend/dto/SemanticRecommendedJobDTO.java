package com.jobrecommender.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticRecommendedJobDTO {
    private String jobLink;
    private String title;
    private String type;
    private String level;
}
