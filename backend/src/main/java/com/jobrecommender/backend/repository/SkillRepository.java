package com.jobrecommender.backend.repository;

import com.jobrecommender.backend.entity.Skill;
import com.jobrecommender.backend.dto.SkillNameProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillRepository extends Neo4jRepository<Skill, String> {
    Optional<Skill> findByName(String name);

    @Query("MERGE (s:Skill {name: $name}) ON CREATE SET s.id = randomUUID() SET s.id = coalesce(s.id, randomUUID()) RETURN s")
    Skill mergeByName(String name);

    @Query("MERGE (s:Skill {name: $name}) ON CREATE SET s.id = randomUUID() SET s.id = coalesce(s.id, randomUUID()) RETURN s.id")
    String mergeByNameId(String name);

        @Query("MERGE (s:Skill {name: $name}) " +
            "ON CREATE SET s.id = randomUUID(), s.embedding = $embedding " +
            "SET s.id = coalesce(s.id, randomUUID()), " +
            "    s.embedding = CASE WHEN s.embedding IS NULL OR size(s.embedding) = 0 THEN $embedding ELSE s.embedding END " +
            "RETURN s")
        Skill mergeByNameWithEmbedding(String name, List<Float> embedding);

        @Query("MERGE (s:Skill {name: $name}) " +
            "ON CREATE SET s.id = randomUUID(), s.embedding = $embedding " +
            "SET s.id = coalesce(s.id, randomUUID()), " +
            "    s.embedding = CASE WHEN s.embedding IS NULL OR size(s.embedding) = 0 THEN $embedding ELSE s.embedding END " +
            "RETURN s.id")
        String mergeByNameWithEmbeddingId(String name, List<Float> embedding);

    @Query(
            value = "MATCH (s:Skill) WHERE toLower(s.name) CONTAINS toLower($name) RETURN s ORDER BY s.name SKIP $skip LIMIT $limit",
            countQuery = "MATCH (s:Skill) WHERE toLower(s.name) CONTAINS toLower($name) RETURN count(s)"
    )
    Page<Skill> searchByName(String name, Pageable pageable);

        @Query(
            value = "MATCH (s:Skill) WHERE toLower(coalesce(s.name,'')) CONTAINS toLower($name) " +
                "RETURN coalesce(s.id,'') AS id, coalesce(s.name,'') AS name ORDER BY name SKIP $skip LIMIT $limit",
            countQuery = "MATCH (s:Skill) WHERE toLower(coalesce(s.name,'')) CONTAINS toLower($name) RETURN count(s)"
        )
        Page<SkillNameProjection> searchNamesByName(String name, Pageable pageable);

        @Query(
            value = "MATCH (s:Skill) RETURN coalesce(s.id,'') AS id, coalesce(s.name,'') AS name ORDER BY name SKIP $skip LIMIT $limit",
            countQuery = "MATCH (s:Skill) RETURN count(s)"
        )
        Page<SkillNameProjection> findAllNames(Pageable pageable);

    @Query("CALL db.index.vector.queryNodes('skill_embedding_index', $k, $embedding) " +
            "YIELD node, score RETURN node AS skill, score ORDER BY score DESC")
        List<com.jobrecommender.backend.dto.SkillSimilarityProjection> semanticNearest(List<Double> embedding, int k);

        @Query("MATCH (s:Skill) " +
            "WHERE s.embedding IS NULL OR size(s.embedding) <> $dimension " +
            "RETURN s.name AS name LIMIT $limit")
        List<String> findSkillNamesMissingEmbedding(int dimension, int limit);

        @Query("MATCH (s:Skill) " +
            "WITH [x IN split(toLower(trim(coalesce(s.name,''))), ' ') WHERE x <> ''] AS parts " +
            "WITH reduce(acc = '', p IN parts | acc + CASE WHEN acc = '' THEN '' ELSE ' ' END + p) AS normalized " +
            "WHERE normalized <> '' " +
            "WITH normalized, count(*) AS c WHERE c > 1 " +
            "RETURN count(*)")
        long countPotentialDuplicateSkillGroups();

        @Query("MATCH (s:Skill) " +
            "WITH [x IN split(toLower(trim(coalesce(s.name,''))), ' ') WHERE x <> ''] AS parts " +
            "WITH reduce(acc = '', p IN parts | acc + CASE WHEN acc = '' THEN '' ELSE ' ' END + p) AS normalized " +
            "WHERE normalized <> '' " +
            "WITH normalized, count(*) AS c WHERE c > 1 " +
            "RETURN coalesce(sum(c), 0)")
        long countPotentialDuplicateSkillNodes();
}