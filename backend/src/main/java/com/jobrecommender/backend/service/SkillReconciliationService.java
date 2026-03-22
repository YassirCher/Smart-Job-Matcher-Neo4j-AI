package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.GitHubAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SkillReconciliationService {

    private static final String EXACT_QUERY =
            "MATCH (s:Skill) " +
            "WHERE toLower(trim(s.name)) = toLower(trim($name)) " +
            "RETURN s.id AS skillId, s.name AS skillName LIMIT 1";

    private static final String VECTOR_QUERY =
            "CALL db.index.vector.queryNodes('skill_embedding_index', $k, $embedding) " +
            "YIELD node, score " +
            "RETURN coalesce(node.id, '') AS skillId, coalesce(node.name, '') AS skillName, score " +
            "ORDER BY score DESC";

    private final SkillCanonicalizationService skillCanonicalizationService;
    private final LocalTextEmbeddingService localTextEmbeddingService;
    private final Neo4jVectorIndexService neo4jVectorIndexService;
    private final GitHubAnalyzerProperties properties;
    private final Neo4jClient neo4jClient;

    public ReconciliationResult reconcile(List<GroqQwenSkillExtractionService.ExtractedSkill> extracted) {
        if (extracted == null || extracted.isEmpty()) {
            return new ReconciliationResult(List.of(), List.of());
        }

        neo4jVectorIndexService.ensureSkillEmbeddingIndex();

        Set<String> attachedIds = new LinkedHashSet<>();
        List<MatchedSkill> matched = new ArrayList<>();
        List<RejectedSkill> rejected = new ArrayList<>();

        for (GroqQwenSkillExtractionService.ExtractedSkill es : extracted) {
            String raw = es.label();
            String canonical = skillCanonicalizationService.canonicalize(raw);
            if (!StringUtils.hasText(canonical)) {
                rejected.add(new RejectedSkill(raw, "", "empty_or_invalid_label", es.confidence(), null, "", es.evidence()));
                continue;
            }

            if (es.confidence() < properties.getLlmMinConfidence()) {
                rejected.add(new RejectedSkill(raw, canonical, "llm_confidence_below_threshold", es.confidence(), null, "", es.evidence()));
                continue;
            }

            ExactMatch exact = findExact(canonical);
            if (exact != null && StringUtils.hasText(exact.skillId())) {
                if (attachedIds.add(exact.skillId().toLowerCase(Locale.ROOT))) {
                    matched.add(new MatchedSkill(raw, canonical, exact.skillId(), exact.skillName(), "EXACT", es.confidence(), null, es.evidence()));
                }
                continue;
            }

            List<Float> embedding = localTextEmbeddingService.embed(canonical);
            if (embedding == null || embedding.isEmpty()) {
                rejected.add(new RejectedSkill(raw, canonical, "embedding_unavailable", es.confidence(), null, "", es.evidence()));
                continue;
            }

            List<VectorMatch> neighbors = findVectorNearest(embedding, properties.getVectorTopK());
            if (neighbors.isEmpty()) {
                rejected.add(new RejectedSkill(raw, canonical, "no_vector_candidate", es.confidence(), null, "", es.evidence()));
                continue;
            }

            VectorMatch top = neighbors.get(0);
            double second = neighbors.size() > 1 ? neighbors.get(1).score() : 0.0d;
            double margin = top.score() - second;

            if (top.score() >= properties.getVectorMinScore() && margin >= properties.getVectorMinMargin() && StringUtils.hasText(top.skillId())) {
                if (attachedIds.add(top.skillId().toLowerCase(Locale.ROOT))) {
                    matched.add(new MatchedSkill(raw, canonical, top.skillId(), top.skillName(), "VECTOR", es.confidence(), top.score(), es.evidence()));
                }
            } else {
                rejected.add(new RejectedSkill(raw, canonical, "vector_match_not_confident_enough", es.confidence(), top.score(), top.skillName(), es.evidence()));
            }
        }

        return new ReconciliationResult(matched, rejected);
    }

    private ExactMatch findExact(String canonicalName) {
        return neo4jClient.query(EXACT_QUERY)
                .bind(canonicalName).to("name")
                .fetchAs(ExactMatch.class)
                .mappedBy((typeSystem, record) -> new ExactMatch(
                        record.get("skillId").asString(""),
                        record.get("skillName").asString("")
                ))
                .one()
                .orElse(null);
    }

    private List<VectorMatch> findVectorNearest(List<Float> embedding, int topK) {
        List<Double> dense = embedding.stream().map(Float::doubleValue).toList();
        int k = Math.max(1, Math.min(topK, 10));

        return neo4jClient.query(VECTOR_QUERY)
                .bind(k).to("k")
                .bind(dense).to("embedding")
                .fetch()
                .all()
                .stream()
                .map(row -> {
                    String id = row.get("skillId") == null ? "" : row.get("skillId").toString();
                    String name = row.get("skillName") == null ? "" : row.get("skillName").toString();
                    double score = row.get("score") instanceof Number n ? n.doubleValue() : 0.0d;
                    return new VectorMatch(id, name, score);
                })
                .toList();
    }

    private record ExactMatch(String skillId, String skillName) {
    }

    private record VectorMatch(String skillId, String skillName, double score) {
    }

    public record MatchedSkill(
            String sourceLabel,
            String canonicalCandidate,
            String matchedSkillId,
            String matchedSkillName,
            String strategy,
            double llmConfidence,
            Double similarityScore,
            List<String> evidence
    ) {
    }

    public record RejectedSkill(
            String sourceLabel,
            String canonicalCandidate,
            String reason,
            double llmConfidence,
            Double bestSimilarityScore,
            String bestSkillName,
            List<String> evidence
    ) {
    }

    public record ReconciliationResult(
            List<MatchedSkill> matched,
            List<RejectedSkill> rejected
    ) {
    }
}
