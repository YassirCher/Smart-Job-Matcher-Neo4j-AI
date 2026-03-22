package com.jobrecommender.backend.service;

import com.jobrecommender.backend.repository.JobRepository;
import com.jobrecommender.backend.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataQualityAuditService {

    private final JobRepository jobRepository;
    private final SkillRepository skillRepository;

    public Map<String, Object> getDataQualitySnapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now().toString());

        long totalSkills = jobRepository.countSkills();
        long duplicateGroups = skillRepository.countPotentialDuplicateSkillGroups();
        long duplicateNodes = skillRepository.countPotentialDuplicateSkillNodes();
        long hasSkillRelationships = jobRepository.countHasSkillRelationships();
        long requiresRelationships = jobRepository.countRequiresRelationships();

        payload.put("totalSkills", totalSkills);
        payload.put("potentialDuplicateSkillGroups", duplicateGroups);
        payload.put("potentialDuplicateSkillNodes", duplicateNodes);
        payload.put("hasSkillRelationships", hasSkillRelationships);
        payload.put("requiresRelationships", requiresRelationships);

        double duplicateRatio = totalSkills > 0 ? (double) duplicateNodes / (double) totalSkills : 0.0;
        double qualityScoreRaw = Math.max(0.0, 100.0 - (duplicateRatio * 100.0));
        double qualityScore = Math.round(qualityScoreRaw * 100.0) / 100.0;

        String status;
        if (duplicateRatio <= 0.005) {
            status = "HEALTHY";
        } else if (duplicateRatio <= 0.02) {
            status = "WARNING";
        } else {
            status = "CRITICAL";
        }

        payload.put("qualityScore", qualityScore);
        payload.put("status", status);
        payload.put("potentialDuplicateRatio", duplicateRatio);

        return payload;
    }
}
