package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CacheNames;
import com.jobrecommender.backend.dto.CareerPathDTO;
import com.jobrecommender.backend.dto.CareerPathStepDTO;
import com.jobrecommender.backend.dto.SkillCentralityDTO;
import com.jobrecommender.backend.dto.SkillCommunityDTO;
import com.jobrecommender.backend.dto.SkillNeighborDTO;
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
public class GraphAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(GraphAnalyticsService.class);

    private final Neo4jClient neo4jClient;

    private static final String SKILL_CENTRALITY_QUERY =
            "MATCH (j:Job) WITH count(j) AS totalJobs " +
            "MATCH (s:Skill)<-[:REQUIRES]-(job:Job) " +
            "WITH s, count(DISTINCT job) AS degree, totalJobs " +
            "RETURN s.name AS skillName, " +
            "       degree AS jobCount, " +
            "       CASE WHEN totalJobs > 0 THEN round((toFloat(degree) / toFloat(totalJobs)) * 10000) / 100.0 ELSE 0.0 END AS coveragePct, " +
            "       CASE " +
            "         WHEN degree >= 1000 THEN 'CRITICAL_GATEWAY' " +
            "         WHEN degree >= 500 THEN 'HIGH_VALUE' " +
            "         WHEN degree >= 100 THEN 'MEDIUM_VALUE' " +
            "         ELSE 'NICHE' " +
            "       END AS criticality " +
            "ORDER BY degree DESC " +
            "LIMIT $limit";

    private static final String SKILL_COMMUNITIES_QUERY =
            "MATCH (seed:Skill)<-[:REQUIRES]-(j:Job) " +
            "WITH seed, count(j) AS demand " +
            "WHERE demand >= $minDegree " +
            "ORDER BY demand DESC " +
            "LIMIT $seedLimit " +
            "CALL { " +
            "  WITH seed " +
            "  MATCH (seed)<-[:REQUIRES]-(j:Job)-[:REQUIRES]->(neighbor:Skill) " +
            "  WHERE neighbor <> seed " +
            "  WITH neighbor, count(j) AS weight " +
            "  WHERE weight >= $minCooccurrence " +
            "  ORDER BY weight DESC " +
            "  LIMIT $topNeighbors " +
            "  RETURN collect({skillName: neighbor.name, cooccurrenceWeight: weight}) AS neighbors, count(*) AS degree " +
            "} " +
            "WITH seed, degree, neighbors " +
            "WHERE degree >= $minDegree " +
            "RETURN seed.name AS seedSkill, degree, neighbors AS topNeighbors " +
            "ORDER BY degree DESC, seedSkill ASC " +
            "LIMIT $limit";

    private static final String CAREER_PATHS_QUERY =
            "MATCH (c:Candidate {id: $candidateId})-[:HAS_SKILL]->(source:Skill) " +
            "WHERE source.embedding IS NOT NULL " +
            "WITH c, source ORDER BY source.name LIMIT $maxCandidateSkills " +
            "CALL { " +
            "  WITH c, source " +
            "  CALL db.index.vector.queryNodes('skill_embedding_index', $topKPerSkill, source.embedding) " +
            "  YIELD node, score " +
            "  WHERE score >= $threshold AND node <> source " +
            "  MATCH (targetJob:Job)-[:REQUIRES]->(node) " +
            "  WHERE NOT EXISTS { MATCH (c)-[:HAS_SKILL]->(node) } " +
            "  RETURN source, node AS bridgeSkill, targetJob, score " +
            "  ORDER BY score DESC " +
            "  LIMIT $jobCandidatesPerSource " +
            "} " +
            "WITH targetJob, source, bridgeSkill, score " +
            "ORDER BY score DESC " +
            "WITH targetJob, " +
             "     collect(DISTINCT {sourceSkill: source.name, bridgeSkill: bridgeSkill.name, targetSkill: bridgeSkill.name, supportCount: toInteger(round(score * 1000))})[..3] AS steps, " +
             "     count(DISTINCT bridgeSkill) AS missingSkills, " +
             "     avg(score) AS avgScore " +
            "WHERE size(steps) > 0 " +
            "RETURN coalesce(targetJob.job_link, targetJob.jobLink) AS jobLink, " +
            "       coalesce(targetJob.job_title, targetJob.title, targetJob.name) AS title, " +
            "       coalesce(targetJob.type, targetJob.job_type) AS type, " +
            "       coalesce(targetJob.level, targetJob.job_level) AS level, " +
            "       toInteger(missingSkills) AS missingSkills, " +
            "       steps AS bridgeSteps " +
            "ORDER BY avgScore DESC, missingSkills ASC " +
            "LIMIT $topJobs";

    private static final String CAREER_PATHS_FALLBACK_QUERY =
            "MATCH (c:Candidate {id: $candidateId})-[:HAS_SKILL]->(source:Skill) " +
            "WITH c, source ORDER BY source.name LIMIT $maxCandidateSkills " +
            "MATCH (bridgeJob:Job)-[:REQUIRES]->(source) " +
            "WITH c, source, bridgeJob LIMIT $jobCandidatesPerSource " +
            "MATCH (bridgeJob)-[:REQUIRES]->(bridgeSkill:Skill) " +
            "WHERE bridgeSkill <> source AND NOT EXISTS { MATCH (c)-[:HAS_SKILL]->(bridgeSkill) } " +
            "MATCH (targetJob:Job)-[:REQUIRES]->(bridgeSkill) " +
            "WITH targetJob, source, bridgeSkill, count(bridgeJob) AS support " +
            "ORDER BY support DESC " +
            "WITH targetJob, " +
            "     collect(DISTINCT {sourceSkill: source.name, bridgeSkill: bridgeSkill.name, targetSkill: bridgeSkill.name, supportCount: support})[..3] AS steps, " +
            "     count(DISTINCT bridgeSkill) AS missingSkills, " +
            "     avg(toFloat(support)) AS avgScore " +
            "WHERE size(steps) > 0 " +
            "RETURN coalesce(targetJob.job_link, targetJob.jobLink) AS jobLink, " +
            "       coalesce(targetJob.job_title, targetJob.title, targetJob.name) AS title, " +
            "       coalesce(targetJob.type, targetJob.job_type) AS type, " +
            "       coalesce(targetJob.level, targetJob.job_level) AS level, " +
            "       toInteger(missingSkills) AS missingSkills, " +
            "       steps AS bridgeSteps " +
            "ORDER BY avgScore DESC, missingSkills ASC " +
            "LIMIT $topJobs";

    @Cacheable(cacheNames = CacheNames.SKILL_CENTRALITY, key = "#limit")
    public List<SkillCentralityDTO> getTopSkillCentrality(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));

        return neo4jClient.query(SKILL_CENTRALITY_QUERY)
                .bind(safeLimit).to("limit")
                .fetch()
                .all()
                .stream()
                .map(row -> new SkillCentralityDTO(
                        asString(row.get("skillName")),
                        asLong(row.get("jobCount")),
                        asDouble(row.get("coveragePct")),
                        asString(row.get("criticality"))
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    @Cacheable(cacheNames = CacheNames.SKILL_COMMUNITIES, key = "#limit + ':' + #minCooccurrence + ':' + #minDegree + ':' + #topNeighbors")
    public List<SkillCommunityDTO> getSkillCommunities(int limit, int minCooccurrence, int minDegree, int topNeighbors) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        int safeMinCooccurrence = Math.max(1, minCooccurrence);
        int safeMinDegree = Math.max(1, minDegree);
        int safeTopNeighbors = Math.max(1, Math.min(topNeighbors, 20));
        int seedLimit = Math.max(8, Math.min(safeLimit * 2, 30));

        try {
            return neo4jClient.query(SKILL_COMMUNITIES_QUERY)
                    .bind(safeLimit).to("limit")
                    .bind(safeMinCooccurrence).to("minCooccurrence")
                    .bind(safeMinDegree).to("minDegree")
                    .bind(safeTopNeighbors).to("topNeighbors")
                    .bind(seedLimit).to("seedLimit")
                    .fetch()
                    .all()
                    .stream()
                    .map(row -> {
                        List<SkillNeighborDTO> neighbors = new ArrayList<>();
                        Object raw = row.get("topNeighbors");
                        if (raw instanceof List<?> list) {
                            for (Object item : list) {
                                if (item instanceof Map<?, ?> mapRaw) {
                                    Map<String, Object> map = (Map<String, Object>) mapRaw;
                                    neighbors.add(new SkillNeighborDTO(
                                            asString(map.get("skillName")),
                                            asLong(map.get("cooccurrenceWeight"))
                                    ));
                                }
                            }
                        }

                        return new SkillCommunityDTO(
                                asString(row.get("seedSkill")),
                                asInt(row.get("degree")),
                                neighbors
                        );
                    })
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Graph communities unavailable: {}", ex.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    @Cacheable(cacheNames = CacheNames.CAREER_PATHS, key = "#candidateId + ':' + #topJobs")
    public List<CareerPathDTO> getCareerPaths(String candidateId, int topJobs) {
        int safeTopJobs = Math.max(1, Math.min(topJobs, 20));
        int topKPerSkill = 5;
        int maxCandidateSkills = 4;
        int jobCandidatesPerSource = 20;
        double threshold = 0.65d;

        List<CareerPathDTO> vectorPaths = runCareerPathQuery(
                CAREER_PATHS_QUERY,
                candidateId,
                safeTopJobs,
                topKPerSkill,
                threshold,
                maxCandidateSkills,
                jobCandidatesPerSource
        );
        if (!vectorPaths.isEmpty()) {
            return vectorPaths;
        }

        return runCareerPathQuery(
                CAREER_PATHS_FALLBACK_QUERY,
                candidateId,
                safeTopJobs,
                topKPerSkill,
                threshold,
                maxCandidateSkills,
                jobCandidatesPerSource
        );
    }

    @SuppressWarnings("unchecked")
    private List<CareerPathDTO> runCareerPathQuery(
            String query,
            String candidateId,
            int topJobs,
            int topKPerSkill,
            double threshold,
            int maxCandidateSkills,
            int jobCandidatesPerSource
    ) {
        try {
            return neo4jClient.query(query)
                    .bind(candidateId).to("candidateId")
                    .bind(topJobs).to("topJobs")
                    .bind(topKPerSkill).to("topKPerSkill")
                    .bind(threshold).to("threshold")
                    .bind(maxCandidateSkills).to("maxCandidateSkills")
                    .bind(jobCandidatesPerSource).to("jobCandidatesPerSource")
                    .fetch()
                    .all()
                    .stream()
                    .map(row -> {
                        List<CareerPathStepDTO> steps = new ArrayList<>();
                        Object raw = row.get("bridgeSteps");
                        if (raw instanceof List<?> list) {
                            for (Object item : list) {
                                if (item instanceof Map<?, ?> mapRaw) {
                                    Map<String, Object> map = (Map<String, Object>) mapRaw;
                                    steps.add(new CareerPathStepDTO(
                                            asString(map.get("sourceSkill")),
                                            asString(map.get("bridgeSkill")),
                                            asString(map.get("targetSkill")),
                                            asLong(map.get("supportCount"))
                                    ));
                                }
                            }
                        }

                        return new CareerPathDTO(
                                asString(row.get("jobLink")),
                                asString(row.get("title")),
                                asString(row.get("type")),
                                asString(row.get("level")),
                                asInt(row.get("missingSkills")),
                                steps
                        );
                    })
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Career paths unavailable for candidate {}: {}", candidateId, ex.getMessage());
            return List.of();
        }
    }

    private String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
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
}
