package com.jobrecommender.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticMatchExplanationDTO {
    private String candidateSkillName;
    private String requiredSkillName;
    private double similarityScore;

    public String getNarrative() {
        return "Le job demande '" + requiredSkillName + "', similaire a votre competence '" + candidateSkillName +
                "' avec un score de " + Math.round(similarityScore * 100.0) + "%";
    }
}
