package com.jobrecommender.backend.dto;

import com.jobrecommender.backend.entity.Skill;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillSimilarityResultDTO {
    private Skill skill;
    private double score;
}
