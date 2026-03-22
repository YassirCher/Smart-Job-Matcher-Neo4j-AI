package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CacheNames;
import com.jobrecommender.backend.dto.JobRecommendationProjection;
import com.jobrecommender.backend.dto.SemanticMatchExplanationDTO;
import com.jobrecommender.backend.dto.SemanticRecommendedJobDTO;
import com.jobrecommender.backend.dto.SemanticRecommendationDTO;
import com.jobrecommender.backend.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final JobRepository jobRepository;
    private final Neo4jClient neo4jClient;
    private final Neo4jVectorIndexService neo4jVectorIndexService;

    private static final String SEMANTIC_QUERY =
            "MATCH (c:Candidate {id: $candidateId})-[:HAS_SKILL]->(candidateSkill:Skill) " +
            "WHERE candidateSkill.embedding IS NOT NULL AND size(candidateSkill.embedding) = $embeddingDim " +
            "CALL { " +
            "  WITH candidateSkill " +
            "  CALL db.index.vector.queryNodes('skill_embedding_index', $topKPerSkill, candidateSkill.embedding) " +
            "  YIELD node, score " +
            "  WHERE score >= $threshold " +
            "  RETURN candidateSkill AS candidateSkillRef, node AS requiredSkill, score " +
            "} " +
            "MATCH (j:Job)-[:REQUIRES]->(requiredSkill) " +
            "WITH j, requiredSkill, candidateSkillRef, score " +
            "ORDER BY score DESC " +
            "WITH j, requiredSkill, collect({candidateSkill: candidateSkillRef, score: score})[0] AS bestMatch " +
            "CALL { " +
            "  WITH j " +
            "  MATCH (j)-[:REQUIRES]->(allRequired:Skill) " +
            "  RETURN count(allRequired) AS totalRequiredSkills " +
            "} " +
            "WITH j, totalRequiredSkills, " +
            "     collect({ " +
            "       candidateSkillName: bestMatch.candidateSkill.name, " +
            "       requiredSkillName: requiredSkill.name, " +
            "       similarityScore: round(bestMatch.score * 10000) / 10000.0 " +
            "     }) AS explanations, " +
            "     avg(bestMatch.score) AS avgSimilarity, " +
            "     count(requiredSkill) AS semanticMatchedSkills " +
            "WHERE semanticMatchedSkills > 0 " +
            "WITH j, explanations, semanticMatchedSkills, totalRequiredSkills, avgSimilarity, " +
            "     (toFloat(semanticMatchedSkills) / toFloat(totalRequiredSkills)) AS coverage " +
            "WITH j, explanations, semanticMatchedSkills, totalRequiredSkills, " +
            "     round((0.7 * avgSimilarity + 0.3 * coverage) * 10000) / 10000.0 AS semanticScore " +
            "RETURN { " +
            "  jobLink: coalesce(j.job_link, j.jobLink), " +
            "  title: coalesce(j.job_title, j.title, j.name), " +
            "  type: coalesce(j.type, j.job_type), " +
            "  level: coalesce(j.level, j.job_level) " +
            "} AS job, " +
            "semanticScore, semanticMatchedSkills, totalRequiredSkills, explanations " +
            "ORDER BY semanticScore DESC, semanticMatchedSkills DESC " +
            "LIMIT $topJobs";

    @Cacheable(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, key = "#candidateId")
    public List<JobRecommendationProjection> getRecommendationsForCandidate(String candidateId) {
        return jobRepository.recommendJobsForCandidate(candidateId);
    }

    @Cacheable(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, key = "#candidateId + ':' + #threshold + ':' + #topJobs + ':' + #topKPerSkill + ':' + #embeddingDim")
    public List<SemanticRecommendationDTO> getSemanticRecommendationsForCandidate(
            String candidateId,
            double threshold,
            int topJobs,
            int topKPerSkill,
            int embeddingDim
    ) {
        double safeThreshold = Math.max(0.0d, Math.min(1.0d, threshold));
        int safeTopJobs = Math.max(1, Math.min(topJobs, 20));
        int safeTopKPerSkill = Math.max(1, Math.min(topKPerSkill, 100));
        int safeEmbeddingDim = Math.max(1, embeddingDim);

        neo4jVectorIndexService.ensureSkillEmbeddingIndex();

        try {
            return neo4jClient.query(SEMANTIC_QUERY)
                    .bind(candidateId).to("candidateId")
                    .bind(safeThreshold).to("threshold")
                    .bind(safeTopJobs).to("topJobs")
                    .bind(safeTopKPerSkill).to("topKPerSkill")
                    .bind(safeEmbeddingDim).to("embeddingDim")
                    .fetch()
                    .all()
                    .stream()
                    .map(this::toSemanticRecommendation)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Semantic recommendation unavailable for candidate {}: {}", candidateId, ex.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private SemanticRecommendationDTO toSemanticRecommendation(Map<String, Object> row) {
        Map<String, Object> jobMap = row.get("job") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : new LinkedHashMap<>();

        SemanticRecommendedJobDTO job = new SemanticRecommendedJobDTO(
                asString(jobMap.get("jobLink")),
                asString(jobMap.get("title")),
                asString(jobMap.get("type")),
                asString(jobMap.get("level"))
        );

        List<SemanticMatchExplanationDTO> explanations = new ArrayList<>();
        Object expRaw = row.get("explanations");
        if (expRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> expMapRaw) {
                    Map<String, Object> expMap = (Map<String, Object>) expMapRaw;
                    explanations.add(new SemanticMatchExplanationDTO(
                            asString(expMap.get("candidateSkillName")),
                            asString(expMap.get("requiredSkillName")),
                            asDouble(expMap.get("similarityScore"))
                    ));
                }
            }
        }

        return new SemanticRecommendationDTO(
                job,
                asDouble(row.get("semanticScore")),
                asInt(row.get("semanticMatchedSkills")),
                asInt(row.get("totalRequiredSkills")),
                explanations
        );
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0d;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}