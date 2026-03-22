package com.jobrecommender.backend.repository;

import com.jobrecommender.backend.entity.Job;
import com.jobrecommender.backend.dto.JobRecommendationProjection;
import com.jobrecommender.backend.dto.StatResult;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends Neo4jRepository<Job, String> {

    @Query("MATCH (j:Job {job_link: $jobLink}) RETURN j LIMIT 1")
    Optional<Job> findByJobLink(String jobLink);

    @Query(value="MATCH (n:Job) RETURN n SKIP $skip LIMIT $limit", countQuery="MATCH (n:Job) RETURN count(n)")
    Page<Job> findAll(Pageable pageable);

        @Query(
            value = "MATCH (j:Job) " +
                "WHERE ($title IS NULL OR $title = '' OR toLower(coalesce(j.job_title, j.title, '')) CONTAINS toLower($title)) " +
                "AND ($level IS NULL OR $level = '' OR toLower(coalesce(j.level, j.job_level, '')) CONTAINS toLower($level)) " +
                "AND ($skill IS NULL OR $skill = '' OR EXISTS { MATCH (j)-[:REQUIRES]->(s:Skill) WHERE toLower(s.name) CONTAINS toLower($skill) }) " +
                "RETURN j ORDER BY coalesce(j.job_title, j.title, '') SKIP $skip LIMIT $limit",
            countQuery = "MATCH (j:Job) " +
                "WHERE ($title IS NULL OR $title = '' OR toLower(coalesce(j.job_title, j.title, '')) CONTAINS toLower($title)) " +
                "AND ($level IS NULL OR $level = '' OR toLower(coalesce(j.level, j.job_level, '')) CONTAINS toLower($level)) " +
                "AND ($skill IS NULL OR $skill = '' OR EXISTS { MATCH (j)-[:REQUIRES]->(s:Skill) WHERE toLower(s.name) CONTAINS toLower($skill) }) " +
                "RETURN count(j)"
        )
        Page<Job> searchJobs(String title, String level, String skill, Pageable pageable);

            @Query("MATCH (c:Candidate {id: $candidateId})-[:HAS_SKILL]->(s:Skill)<-[:REQUIRES]-(j:Job) " +
                "MATCH (j)-[:REQUIRES]->(all_s:Skill) " +
                "WITH j, count(DISTINCT s) AS matchingSkills, count(DISTINCT all_s) AS totalSkills, collect(DISTINCT s.name) AS matchedSkillsList " +
                   "RETURN j AS job, " +
                   "coalesce(j.job_title, j.title, j.name) AS jobTitle, " +
                   "coalesce(j.job_link, j.jobLink) AS jobLink, " +
                   "coalesce(j.type, j.job_type) AS jobType, " +
                   "coalesce(j.level, j.job_level) AS jobLevel, " +
                "matchingSkills, totalSkills, matchedSkillsList, " +
                "CASE WHEN totalSkills > 0 THEN toFloat(matchingSkills) * 100.0 / totalSkills ELSE 0.0 END AS score " +
                "ORDER BY score DESC LIMIT 5")
    List<JobRecommendationProjection> recommendJobsForCandidate(String candidateId);

    @Query("MATCH (n:Job) RETURN count(n)")
    long countJobs();

    @Query("MATCH (n:Skill) RETURN count(n)")
    long countSkills();

    @Query("MATCH (n:Candidate) RETURN count(n)")
    long countCandidates();

    @Query("MATCH (j:Job) RETURN j.level AS category, count(j) AS count ORDER BY count DESC")
    List<StatResult> countJobsByLevel();

    @Query("MATCH (j:Job) RETURN j.type AS category, count(j) AS count ORDER BY count DESC")
    List<StatResult> countJobsByType();

    @Query("MATCH (j:Job)-[:REQUIRES]->(s:Skill) RETURN s.name AS category, count(j) AS count ORDER BY count DESC LIMIT 10")
    List<StatResult> getTop10Skills();

    @Query("MATCH (:Candidate)-[r:HAS_SKILL]->(:Skill) RETURN count(r)")
    long countHasSkillRelationships();

    @Query("MATCH (:Job)-[r:REQUIRES]->(:Skill) RETURN count(r)")
    long countRequiresRelationships();

    @Query("MATCH (j:Job)-[:REQUIRES]->(s:Skill) RETURN count(DISTINCT s)")
    long countDistinctSkillsRequiredByJobs();

    @Query("MATCH (c:Candidate)-[:HAS_SKILL]->(s:Skill) RETURN count(DISTINCT s)")
    long countDistinctSkillsOwnedByCandidates();

    @Query("MATCH (:Job)-[:REQUIRES]->(s:Skill)<-[:HAS_SKILL]-(:Candidate) RETURN count(DISTINCT s)")
    long countDistinctSharedSkillsBetweenJobsAndCandidates();

    @Query("MATCH (j:Job) WITH count(j) AS total MATCH (j:Job) RETURN coalesce(j.level, 'UNKNOWN') AS category, count(j) AS count ORDER BY count DESC")
    List<StatResult> countJobsByLevelWithUnknown();

    @Query("MATCH (j:Job) WITH count(j) AS total MATCH (j:Job) RETURN coalesce(j.type, 'UNKNOWN') AS category, count(j) AS count ORDER BY count DESC")
    List<StatResult> countJobsByTypeWithUnknown();

    @Query("MATCH (j:Job)-[:REQUIRES]->(s:Skill) RETURN count(*)")
    long countRequiredSkillMentions();
}
