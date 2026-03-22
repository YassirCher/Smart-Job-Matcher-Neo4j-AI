package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CacheNames;
import com.jobrecommender.backend.dto.StatResult;
import com.jobrecommender.backend.dto.JobRecommendationProjection;
import com.jobrecommender.backend.dto.SemanticRecommendationDTO;
import com.jobrecommender.backend.repository.JobRepository;
import com.jobrecommender.backend.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdvancedAnalyticsService {

    private final JobRepository jobRepository;
    private final SkillRepository skillRepository;
    private final Neo4jClient neo4jClient;
    private final RecommendationService recommendationService;

    private static final String CANDIDATE_QUALITY_QUERY =
        "MATCH (c:Candidate) " +
        "OPTIONAL MATCH (c)-[:HAS_SKILL]->(s:Skill) " +
        "WITH c, count(DISTINCT s) AS skillCount " +
        "WITH c, skillCount, " +
        "     (CASE WHEN c.name IS NOT NULL AND trim(c.name) <> '' THEN 1 ELSE 0 END + " +
        "      CASE WHEN c.email IS NOT NULL AND trim(c.email) <> '' THEN 1 ELSE 0 END + " +
        "      CASE WHEN c.resumePath IS NOT NULL AND trim(c.resumePath) <> '' THEN 1 ELSE 0 END) AS completeness " +
        "WITH c, skillCount, completeness, " +
        "     round((0.45 * (toFloat(completeness) / 3.0) + 0.55 * CASE WHEN skillCount >= 20 THEN 1.0 ELSE toFloat(skillCount) / 20.0 END) * 10000) / 100.0 AS qualityScore " +
        "RETURN c.id AS candidateId, coalesce(c.name, '') AS candidateName, skillCount, completeness, qualityScore, " +
        "       CASE " +
        "         WHEN skillCount < 3 OR completeness < 2 THEN 'LOW_SIGNAL' " +
        "         WHEN qualityScore < 65 THEN 'MEDIUM_SIGNAL' " +
        "         ELSE 'HIGH_SIGNAL' " +
        "       END AS signalClass " +
        "ORDER BY qualityScore DESC " +
        "SKIP $skip LIMIT $limit";

    private static final String CANDIDATE_QUALITY_COUNT_QUERY =
        "MATCH (c:Candidate) RETURN count(c) AS total";

    private static final String SKILL_GAP_ROADMAP_QUERY =
        "MATCH (c:Candidate {id: $candidateId})-[:HAS_SKILL]->(owned:Skill)<-[:REQUIRES]-(j:Job) " +
        "MATCH (j)-[:REQUIRES]->(requiredSkill:Skill) " +
        "WITH j, collect(DISTINCT owned.name) AS ownedNames, collect(DISTINCT requiredSkill.name) AS required " +
        "WITH j, [x IN required WHERE NOT x IN ownedNames] AS missing, size(required) AS totalReq " +
        "WHERE totalReq > 0 AND size(missing) > 0 " +
        "UNWIND missing AS ms " +
        "WITH ms AS skillName, j, totalReq, size(missing) AS missingCount " +
        "WITH skillName, count(DISTINCT j) AS supportedJobs, avg(toFloat(missingCount)) AS avgMissing, " +
        "     collect(DISTINCT coalesce(j.job_title, j.title, j.name))[..3] AS exampleJobs " +
        "RETURN skillName, toInteger(supportedJobs) AS supportedJobs, " +
        "       round((1.0 / avgMissing) * 10000) / 100.0 AS impactScore, " +
        "       exampleJobs " +
        "ORDER BY supportedJobs DESC, impactScore DESC " +
        "LIMIT $limit";

    private static final String COUNTERFACTUAL_QUERY =
        "MATCH (c:Candidate {id: $candidateId}) " +
        "OPTIONAL MATCH (c)-[:HAS_SKILL]->(owned:Skill) " +
        "WITH collect(DISTINCT owned.name) AS ownedNames " +
        "MATCH (j:Job)-[:REQUIRES]->(s:Skill) " +
        "WITH ownedNames, j, collect(DISTINCT s.name) AS required " +
        "WITH j, [x IN required WHERE NOT x IN ownedNames] AS missing, size(required) AS totalReq " +
        "WHERE totalReq > 0 AND size(missing) > 0 AND size(missing) <= $maxMissing " +
        "UNWIND missing AS missingSkill " +
        "WITH missingSkill AS skillName, j, size(missing) AS missingCount " +
        "WITH skillName, count(DISTINCT j) AS unlockableJobs, avg(toFloat(missingCount)) AS avgMissingCount, " +
        "     collect(DISTINCT coalesce(j.job_title, j.title, j.name))[..3] AS exampleJobs " +
        "RETURN skillName, toInteger(unlockableJobs) AS unlockableJobs, " +
        "       round((1.0 / avgMissingCount) * 10000) / 100.0 AS priorityScore, " +
        "       exampleJobs " +
        "ORDER BY unlockableJobs DESC, priorityScore DESC " +
        "LIMIT $limit";

    @Cacheable(cacheNames = CacheNames.DATA_FUNNEL)
    public Map<String, Object> getDataFunnel() {
        long totalSkills = jobRepository.countSkills();
        long duplicateNodes = skillRepository.countPotentialDuplicateSkillNodes();
        long deduplicatedSkills = Math.max(0L, totalSkills - duplicateNodes);

        long requiredMentions = jobRepository.countRequiredSkillMentions();
        long marketValidatedSkills = jobRepository.countDistinctSkillsRequiredByJobs();
        long candidateValidatedSkills = jobRepository.countDistinctSkillsOwnedByCandidates();
        long sharedSkills = jobRepository.countDistinctSharedSkillsBetweenJobsAndCandidates();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("totalSkillNodes", totalSkills);
        payload.put("potentialDuplicateSkillNodes", duplicateNodes);
        payload.put("deduplicatedSkillNodes", deduplicatedSkills);
        payload.put("requiredSkillMentions", requiredMentions);
        payload.put("marketValidatedSkills", marketValidatedSkills);
        payload.put("candidateValidatedSkills", candidateValidatedSkills);
        payload.put("sharedSkills", sharedSkills);

        double marketCoverage = deduplicatedSkills > 0 ? (100.0 * marketValidatedSkills / deduplicatedSkills) : 0.0;
        double candidateCoverage = deduplicatedSkills > 0 ? (100.0 * candidateValidatedSkills / deduplicatedSkills) : 0.0;
        double sharedCoverage = deduplicatedSkills > 0 ? (100.0 * sharedSkills / deduplicatedSkills) : 0.0;

        payload.put("marketCoveragePct", round2(marketCoverage));
        payload.put("candidateCoveragePct", round2(candidateCoverage));
        payload.put("sharedCoveragePct", round2(sharedCoverage));
        return payload;
    }

    @Cacheable(cacheNames = CacheNames.DATA_DRIFT)
    public Map<String, Object> getDataDriftProxy() {
        List<StatResult> byLevel = jobRepository.countJobsByLevelWithUnknown();
        List<StatResult> byType = jobRepository.countJobsByTypeWithUnknown();
        List<StatResult> top10Skills = jobRepository.getTop10Skills();

        long totalJobs = jobRepository.countJobs();
        long totalMentions = jobRepository.countRequiredSkillMentions();

        double levelEntropy = entropy(byLevel);
        double typeEntropy = entropy(byType);
        long top10Mentions = top10Skills.stream().mapToLong(s -> s.count() == null ? 0L : s.count()).sum();
        double top10Concentration = totalMentions > 0 ? (100.0 * top10Mentions / totalMentions) : 0.0;

        String risk;
        if (top10Concentration >= 45.0 || levelEntropy < 1.1 || typeEntropy < 1.1) {
            risk = "HIGH";
        } else if (top10Concentration >= 30.0 || levelEntropy < 1.5 || typeEntropy < 1.5) {
            risk = "MEDIUM";
        } else {
            risk = "LOW";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("isProxy", true);
        payload.put("note", "Historical snapshots not available: drift risk inferred from concentration and entropy.");
        payload.put("totalJobs", totalJobs);
        payload.put("totalRequiredSkillMentions", totalMentions);
        payload.put("jobsByLevel", byLevel);
        payload.put("jobsByType", byType);
        payload.put("top10SkillConcentrationPct", round2(top10Concentration));
        payload.put("levelEntropy", round2(levelEntropy));
        payload.put("typeEntropy", round2(typeEntropy));
        payload.put("imbalanceRisk", risk);

        return payload;
    }

    @Cacheable(cacheNames = CacheNames.CANDIDATE_QUALITY, key = "#page + ':' + #size")
    public Map<String, Object> getCandidateQuality(int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        int skip = safePage * safeSize;

        List<Map<String, Object>> content = new ArrayList<>(neo4jClient.query(CANDIDATE_QUALITY_QUERY)
                .bind(skip).to("skip")
                .bind(safeSize).to("limit")
                .fetch()
            .all());

        long totalElements = neo4jClient.query(CANDIDATE_QUALITY_COUNT_QUERY)
                .fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("total").asLong())
                .one()
                .orElse(0L);

        long totalPages = (totalElements + safeSize - 1) / safeSize;

        Map<String, Object> pageMap = new LinkedHashMap<>();
        pageMap.put("content", content);
        pageMap.put("size", safeSize);
        pageMap.put("totalElements", totalElements);
        pageMap.put("totalPages", totalPages);
        pageMap.put("number", safePage);
        return pageMap;
    }

    @Cacheable(cacheNames = CacheNames.SKILL_GAP_ROADMAP, key = "#candidateId + ':' + #limit")
    public List<Map<String, Object>> getSkillGapRoadmap(String candidateId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10));
        return new ArrayList<>(neo4jClient.query(SKILL_GAP_ROADMAP_QUERY)
                .bind(candidateId).to("candidateId")
                .bind(safeLimit).to("limit")
                .fetch()
            .all());
    }

    @Cacheable(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, key = "#candidateId + ':' + #topJobs")
    public List<Map<String, Object>> getRecommendationComparison(String candidateId, int topJobs) {
        int safeTopJobs = Math.max(1, Math.min(topJobs, 20));

        List<JobRecommendationProjection> lexical = recommendationService.getRecommendationsForCandidate(candidateId);
        List<SemanticRecommendationDTO> semantic = recommendationService.getSemanticRecommendationsForCandidate(
                candidateId,
                0.8,
                safeTopJobs,
                20,
                384
        );

        Map<String, Map<String, Object>> rowsByJobLink = new HashMap<>();

        for (JobRecommendationProjection rec : lexical) {
            String link = nonBlank(rec.getJobLink(), rec.getJob() != null ? rec.getJob().getJobLink() : "", "UNKNOWN");
            Map<String, Object> row = rowsByJobLink.computeIfAbsent(link, k -> new LinkedHashMap<>());
            row.put("jobLink", link);
            row.put("title", nonBlank(rec.getJobTitle(), rec.getJob() != null ? rec.getJob().getTitle() : "", "Unknown"));
            row.put("lexicalScore", round2(rec.getScore()));
            row.putIfAbsent("semanticScore", 0.0d);
        }

        for (SemanticRecommendationDTO rec : semantic) {
            String link = nonBlank(rec.getMatchedJob() != null ? rec.getMatchedJob().getJobLink() : "", "UNKNOWN");
            String title = rec.getMatchedJob() != null ? nonBlank(rec.getMatchedJob().getTitle(), "Unknown") : "Unknown";
            Map<String, Object> row = rowsByJobLink.computeIfAbsent(link, k -> new LinkedHashMap<>());
            row.put("jobLink", link);
            row.put("title", title);
            row.put("semanticScore", round2(rec.getSemanticScore() * 100.0));
            row.putIfAbsent("lexicalScore", 0.0d);
        }

        List<Map<String, Object>> rows = new ArrayList<>(rowsByJobLink.values());
        for (Map<String, Object> row : rows) {
            double lexicalScore = asDouble(row.get("lexicalScore"));
            double semanticScore = asDouble(row.get("semanticScore"));
            row.put("delta", round2(semanticScore - lexicalScore));
        }

        rows.sort(Comparator.comparingDouble((Map<String, Object> x) -> asDouble(x.get("semanticScore"))).reversed());
        if (rows.size() > safeTopJobs) {
            return rows.subList(0, safeTopJobs);
        }
        return rows;
    }

    @Cacheable(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, key = "#candidateId + ':' + #limit + ':' + #maxMissing")
    public List<Map<String, Object>> getRecommendationCounterfactual(String candidateId, int limit, int maxMissing) {
        int safeLimit = Math.max(1, Math.min(limit, 10));
        int safeMaxMissing = Math.max(1, Math.min(maxMissing, 10));

        return new ArrayList<>(neo4jClient.query(COUNTERFACTUAL_QUERY)
                .bind(candidateId).to("candidateId")
                .bind(safeLimit).to("limit")
                .bind(safeMaxMissing).to("maxMissing")
                .fetch()
            .all());
    }

    private double entropy(List<StatResult> rows) {
        long total = rows.stream().mapToLong(r -> r.count() == null ? 0L : r.count()).sum();
        if (total <= 0) {
            return 0.0;
        }

        double h = 0.0;
        for (StatResult row : rows) {
            long c = row.count() == null ? 0L : row.count();
            if (c <= 0) {
                continue;
            }
            double p = (double) c / (double) total;
            h += -p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private String nonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
