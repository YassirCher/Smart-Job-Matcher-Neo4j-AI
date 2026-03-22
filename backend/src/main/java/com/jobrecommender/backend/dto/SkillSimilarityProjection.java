package com.jobrecommender.backend.dto;

import com.jobrecommender.backend.entity.Skill;

public interface SkillSimilarityProjection {
    Skill getSkill();
    double getScore();
}
