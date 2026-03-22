package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CacheNames;
import com.jobrecommender.backend.entity.Skill;
import com.jobrecommender.backend.repository.SkillRepository;
import com.jobrecommender.backend.dto.SkillNameProjection;
import com.jobrecommender.backend.util.FullTextQueryNormalizer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final SkillRepository skillRepository;
    private final SkillResolutionService skillResolutionService;
    private final Neo4jVectorIndexService neo4jVectorIndexService;
    private final LocalTextEmbeddingService localTextEmbeddingService;
        private final Neo4jClient neo4jClient;

    private static final int EMBEDDING_DIMENSION = 384;
    private static final int BACKFILL_BATCH_SIZE = 64;
        private static final String SEMANTIC_NEAREST_QUERY =
            "CALL db.index.vector.queryNodes('skill_embedding_index', $k, $embedding) " +
            "YIELD node, score " +
            "RETURN coalesce(node.id, '') AS skillId, coalesce(node.name, '') AS skillName, score " +
            "ORDER BY score DESC";

            private static final String SKILL_FULLTEXT_PAGE_QUERY =
                "CALL db.index.fulltext.queryNodes('skill_name_ft', $q) YIELD node, score " +
                "RETURN coalesce(node.id, '') AS id, coalesce(node.name, '') AS name " +
                "ORDER BY score DESC, name ASC SKIP $skip LIMIT $limit";

            private static final String SKILL_FULLTEXT_COUNT_QUERY =
                "CALL db.index.fulltext.queryNodes('skill_name_ft', $q) YIELD node " +
                "RETURN count(DISTINCT node) AS total";

    public Page<Skill> findAll(Pageable pageable) {
        return skillRepository.findAll(pageable);
    }

    public Page<Skill> searchByName(String name, Pageable pageable) {
        try {
            if (!StringUtils.hasText(name)) {
                return toSkillPage(skillRepository.findAllNames(pageable), pageable);
            }
            String normalized = name.trim();
            String q = FullTextQueryNormalizer.normalize(normalized);
            if (!StringUtils.hasText(q)) {
                return toSkillPage(skillRepository.searchNamesByName(normalized, pageable), pageable);
            }

            long total = neo4jClient.query(SKILL_FULLTEXT_COUNT_QUERY)
                    .bind(q).to("q")
                    .fetchAs(Long.class)
                    .one()
                    .orElse(0L);

            if (total == 0L) {
                return toSkillPage(skillRepository.searchNamesByName(normalized, pageable), pageable);
            }

            List<Skill> content = neo4jClient.query(SKILL_FULLTEXT_PAGE_QUERY)
                    .bind(q).to("q")
                    .bind(pageable.getOffset()).to("skip")
                    .bind(pageable.getPageSize()).to("limit")
                    .fetch()
                    .all()
                    .stream()
                    .map(row -> new Skill(
                            row.get("id") == null ? "" : row.get("id").toString(),
                            row.get("name") == null ? "" : row.get("name").toString(),
                            null
                    ))
                    .toList();

            return new PageImpl<>(content, pageable, total);
        } catch (RuntimeException ex) {
            log.error("Skill search failed for query '{}': {}", name, ex.getMessage(), ex);
            if (!StringUtils.hasText(name)) {
                return toSkillPage(skillRepository.findAllNames(pageable), pageable);
            }
            return toSkillPage(skillRepository.searchNamesByName(name.trim(), pageable), pageable);
        }
    }

    private Page<Skill> toSkillPage(Page<SkillNameProjection> source, Pageable pageable) {
        List<Skill> skills = source.getContent().stream()
                .map(p -> new Skill(p.getId(), p.getName(), null))
                .toList();
        return new PageImpl<>(skills, pageable, source.getTotalElements());
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheNames.LEXICAL_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SEMANTIC_RECOMMENDATIONS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_CENTRALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_COMMUNITIES, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CAREER_PATHS, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_FUNNEL, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.DATA_DRIFT, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.CANDIDATE_QUALITY, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.SKILL_GAP_ROADMAP, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COMPARISON, allEntries = true),
            @CacheEvict(cacheNames = CacheNames.RECOMMENDATION_COUNTERFACTUAL, allEntries = true)
    })
    public Skill create(Skill skill) {
        if (skill == null || !StringUtils.hasText(skill.getName())) {
            throw new IllegalArgumentException("Skill name is required");
        }

        SkillResolutionService.ResolvedSkill resolved = skillResolutionService.resolve(skill.getName());
        if (!StringUtils.hasText(resolved.canonical())) {
            throw new IllegalArgumentException("Skill name is required");
        }
        return skillRepository.mergeByNameWithEmbedding(resolved.canonical(), resolved.embedding());
    }

    public Map<String, Object> resolveName(String input) {
        SkillResolutionService.ResolvedSkill resolved = skillResolutionService.resolve(input);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("input", input == null ? "" : input);
        payload.put("normalized", resolved.normalized());
        payload.put("canonical", resolved.canonical());
        payload.put("changed", !resolved.canonical().equals(resolved.normalized()));
        payload.put("hasEmbedding", resolved.embedding() != null && !resolved.embedding().isEmpty());
        return payload;
    }

    public List<com.jobrecommender.backend.dto.SkillSimilarityResultDTO> semanticNearest(String query, int topK) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        SkillResolutionService.ResolvedSkill resolved = skillResolutionService.resolve(query);
        if (resolved.embedding() == null || resolved.embedding().isEmpty()) {
            return List.of();
        }

        neo4jVectorIndexService.ensureSkillEmbeddingIndex();

        int k = Math.max(1, Math.min(topK, 25));
        List<Double> numericEmbedding = resolved.embedding().stream()
                .map(Float::doubleValue)
                .toList();
        try {
            List<com.jobrecommender.backend.dto.SkillSimilarityResultDTO> result = runSemanticNearestQuery(numericEmbedding, k);

            if (!result.isEmpty()) {
                return result;
            }

            int backfilled = backfillMissingEmbeddings(BACKFILL_BATCH_SIZE);
            if (backfilled <= 0) {
                return result;
            }

            neo4jVectorIndexService.ensureSkillEmbeddingIndex();

            return runSemanticNearestQuery(numericEmbedding, k);
        } catch (RuntimeException ex) {
            log.warn("Skill semantic search unavailable for query '{}': {}", query, ex.getMessage());
            return List.of();
        }
    }

    private List<com.jobrecommender.backend.dto.SkillSimilarityResultDTO> runSemanticNearestQuery(List<Double> embedding, int k) {
        return neo4jClient.query(SEMANTIC_NEAREST_QUERY)
                .bind(k).to("k")
                .bind(embedding).to("embedding")
                .fetch()
                .all()
                .stream()
                .map(row -> {
                    String skillId = row.get("skillId") == null ? "" : row.get("skillId").toString();
                    String skillName = row.get("skillName") == null ? "" : row.get("skillName").toString();
                    double score = row.get("score") instanceof Number number ? number.doubleValue() : 0.0d;
                    return new com.jobrecommender.backend.dto.SkillSimilarityResultDTO(
                            new Skill(skillId, skillName, null),
                            score
                    );
                })
                .toList();
    }

    private int backfillMissingEmbeddings(int limit) {
        List<String> names = skillRepository.findSkillNamesMissingEmbedding(EMBEDDING_DIMENSION, Math.max(1, limit));
        if (names == null || names.isEmpty()) {
            return 0;
        }

        int updated = 0;
        for (String name : names) {
            if (!StringUtils.hasText(name)) {
                continue;
            }

            List<Float> embedding = localTextEmbeddingService.embed(name);
            if (embedding == null || embedding.isEmpty()) {
                continue;
            }

            List<Float> safeEmbedding = new ArrayList<>(embedding);
            skillRepository.mergeByNameWithEmbedding(name, safeEmbedding);
            updated++;
        }

        if (updated > 0) {
            log.info("Backfilled {} missing skill embeddings.", updated);
        }
        return updated;
    }

}
