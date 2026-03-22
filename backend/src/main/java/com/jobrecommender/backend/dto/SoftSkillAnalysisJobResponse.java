package com.jobrecommender.backend.dto;

import java.util.List;

public record SoftSkillAnalysisJobResponse(
        String jobId,
        String candidateId,
        String status,
        int pollAfterMs,
        String message,
        long startedAtEpochMs,
        Long completedAtEpochMs,
        String model,
        boolean fallbackUsed,
        List<SoftSkillEvidence> softSkills
) {
}
