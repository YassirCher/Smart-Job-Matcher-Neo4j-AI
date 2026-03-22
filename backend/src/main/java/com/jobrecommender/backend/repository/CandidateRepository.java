package com.jobrecommender.backend.repository;

import com.jobrecommender.backend.entity.Candidate;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CandidateRepository extends Neo4jRepository<Candidate, String> {

	@Query("MATCH (c:Candidate {id: $candidateId}) " +
			"MATCH (s:Skill) WHERE s.id IN $skillIds " +
			"MERGE (c)-[:HAS_SKILL]->(s) " +
			"RETURN count(s)")
	long attachSkillIds(String candidateId, List<String> skillIds);
}