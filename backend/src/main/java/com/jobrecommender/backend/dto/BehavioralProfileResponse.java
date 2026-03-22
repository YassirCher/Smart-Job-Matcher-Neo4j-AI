package com.jobrecommender.backend.dto;

import java.util.List;

public record BehavioralProfileResponse(
        String candidateId,
        int totalSoftSkills,
        List<SoftSkillEvidence> softSkills
) {
}
