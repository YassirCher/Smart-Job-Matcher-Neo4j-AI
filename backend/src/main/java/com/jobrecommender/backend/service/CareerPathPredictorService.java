package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CareerPathPredictorProperties;
import com.jobrecommender.backend.dto.CareerPathPredictionResultDTO;
import com.jobrecommender.backend.dto.CareerPathPredictionSkillDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CareerPathPredictorService {

    private static final String CANDIDATE_CONTEXT_QUERY =
            "MATCH (c:Candidate {id: $candidateId}) " +
            "OPTIONAL MATCH (c)-[:HAS_SKILL]->(s:Skill) " +
            "RETURN coalesce(c.name, '') AS candidateName, collect(DISTINCT s.name)[..$contextSkillsLimit] AS currentSkills";

    private static final String CAREER_PATH_PREDICTION_QUERY =
            "MATCH (c:Candidate {id: $candidateId})-[:HAS_SKILL]->(owned:Skill) " +
            "WITH collect(DISTINCT owned.name)[..$maxCandidateSkills] AS ownedNames " +
            "WHERE size(ownedNames) > 0 " +
            "UNWIND ownedNames AS ownedName " +
            "MATCH (:Skill {name: ownedName})<-[:REQUIRES]-(frontier:Job)-[:REQUIRES]->(missing:Skill) " +
            "WHERE NOT missing.name IN ownedNames " +
            "WITH ownedNames, missing, count(DISTINCT frontier) AS cooccurrenceSupport " +
            "ORDER BY cooccurrenceSupport DESC " +
            "LIMIT $candidatePoolSize " +
            "CALL { " +
            "  WITH ownedNames, missing " +
            "  MATCH (missing)<-[:REQUIRES]-(target:Job)-[:REQUIRES]->(req:Skill) " +
            "  WITH ownedNames, missing, target, collect(DISTINCT req.name) AS reqNames " +
            "  WITH missing, target, " +
            "       size([x IN reqNames WHERE NOT x IN ownedNames]) AS gapsBefore, " +
            "       size([x IN reqNames WHERE NOT x IN ownedNames AND x <> missing.name]) AS gapsAfter " +
            "  WHERE gapsBefore > 0 AND gapsAfter <= $maxRemainingGaps " +
            "  RETURN count(DISTINCT target) AS unlockedJobs, " +
            "         sum(CASE " +
            "               WHEN toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'principal' THEN 1.8 " +
            "               WHEN toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'staff' THEN 1.7 " +
            "               WHEN toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'lead' THEN 1.6 " +
            "               WHEN toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'senior' THEN 1.4 " +
            "               WHEN toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'expert' THEN 1.5 " +
            "               ELSE 1.0 END) AS compensationLift, " +
            "         sum(CASE " +
            "               WHEN toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'principal' " +
            "                 OR toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'staff' " +
            "                 OR toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'lead' " +
            "                 OR toLower(coalesce(target.level, target.job_level, '')) CONTAINS 'senior' " +
            "               THEN 1 ELSE 0 END) AS seniorUnlocked, " +
            "         collect(DISTINCT coalesce(target.job_title, target.title, target.name))[..3] AS sampleJobs " +
            "} " +
            "WITH missing.name AS skillName, cooccurrenceSupport, " +
            "     coalesce(unlockedJobs, 0) AS unlockedJobs, " +
            "     coalesce(seniorUnlocked, 0) AS seniorUnlocked, " +
            "     coalesce(compensationLift, 0.0) AS compensationLift, " +
            "     coalesce(sampleJobs, []) AS sampleJobs " +
            "WHERE unlockedJobs > 0 " +
            "WITH skillName, cooccurrenceSupport, unlockedJobs, seniorUnlocked, compensationLift, sampleJobs, " +
            "     (0.45 * log(1 + toFloat(cooccurrenceSupport)) + " +
            "      0.35 * log(1 + toFloat(unlockedJobs)) + " +
            "      0.20 * log(1 + compensationLift)) AS scoreRaw " +
            "RETURN skillName, cooccurrenceSupport, unlockedJobs, seniorUnlocked, compensationLift, sampleJobs, " +
            "       round(scoreRaw * 10000) / 100.0 AS linkPredictionScore " +
            "ORDER BY linkPredictionScore DESC, unlockedJobs DESC " +
            "LIMIT $topK";

    private final Neo4jClient neo4jClient;
    private final CareerPathPredictorProperties properties;
    private final CareerPathCoachService careerPathCoachService;

    public CareerPathPredictionResultDTO predictForCandidate(
            String candidateId,
            Integer topK,
            Integer maxRemainingGaps,
            boolean withCoaching
    ) {
        long t0 = System.currentTimeMillis();

        int safeTopK = clamp(topK == null ? properties.getTopK() : topK, 1, 6);
        int safeMaxRemainingGaps = clamp(
                maxRemainingGaps == null ? properties.getMaxRemainingGaps() : maxRemainingGaps,
                0,
                3
        );

        String candidateName = "";
        List<String> currentSkills = List.of();
        List<CareerPathPredictionSkillDTO> recommendations = List.of();

        try {
            Map<String, Object> context = fetchCandidateContext(candidateId);
            candidateName = asString(context.get("candidateName"));
            currentSkills = asStringList(context.get("currentSkills"));

            List<Map<String, Object>> rows = fetchPredictionRows(candidateId, safeTopK, safeMaxRemainingGaps);
            recommendations = rows.stream()
                    .map(this::toPredictionSkill)
                    .toList();
        } catch (Exception ex) {
            log.error("CareerPathPredictor graph query failed for candidateId={}", candidateId, ex);
        }

        String coachingMessage = "";
        boolean coachingFromLlm = false;

        if (withCoaching) {
            try {
                CareerPathCoachService.CoachingResult coaching = careerPathCoachService.buildCoachingMessage(
                        candidateName,
                        currentSkills,
                        recommendations
                );
                coachingMessage = coaching.message();
                coachingFromLlm = coaching.fromLlm();
            } catch (Exception ex) {
                log.error("CareerPathPredictor coaching failed for candidateId={}", candidateId, ex);
                coachingMessage = "Le coaching IA est momentanement indisponible, mais votre profil reste analyse.";
                coachingFromLlm = false;
            }
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        log.info(
                "CareerPathPredictor candidateId={} topK={} maxRemainingGaps={} recCount={} coaching={} elapsedMs={}",
                candidateId,
                safeTopK,
                safeMaxRemainingGaps,
                recommendations.size(),
                withCoaching,
                elapsedMs
        );

        return new CareerPathPredictionResultDTO(
                candidateId,
                candidateName,
                currentSkills,
                recommendations,
                coachingMessage,
                coachingFromLlm,
                Instant.now().toEpochMilli()
        );
    }

    protected Map<String, Object> fetchCandidateContext(String candidateId) {
        return neo4jClient.query(CANDIDATE_CONTEXT_QUERY)
                .bind(candidateId).to("candidateId")
                .bind(clamp(properties.getContextSkillsLimit(), 5, 60)).to("contextSkillsLimit")
                .fetch()
                .one()
                .orElseGet(LinkedHashMap::new);
    }

    protected List<Map<String, Object>> fetchPredictionRows(
            String candidateId,
            int topK,
            int maxRemainingGaps
    ) {
        if (!properties.isEnabled()) {
            return List.of();
        }

        return new ArrayList<>(neo4jClient.query(CAREER_PATH_PREDICTION_QUERY)
                .bind(candidateId).to("candidateId")
                .bind(topK).to("topK")
                .bind(maxRemainingGaps).to("maxRemainingGaps")
                .bind(clamp(properties.getMaxCandidateSkills(), 5, 80)).to("maxCandidateSkills")
                .bind(clamp(properties.getCandidatePoolSize(), 20, 300)).to("candidatePoolSize")
                .fetch()
                .all());
    }

    @SuppressWarnings("unchecked")
    private CareerPathPredictionSkillDTO toPredictionSkill(Map<String, Object> row) {
        List<String> sampleJobs = asStringList(row.get("sampleJobs"));

        return new CareerPathPredictionSkillDTO(
                asString(row.get("skillName")),
                asLong(row.get("cooccurrenceSupport")),
                asLong(row.get("unlockedJobs")),
                asLong(row.get("seniorUnlocked")),
                round2(asDouble(row.get("compensationLift"))),
                round2(asDouble(row.get("linkPredictionScore"))),
                sampleJobs
        );
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0d;
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String v = item.toString().trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
