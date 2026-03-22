package com.jobrecommender.backend.repository;

import com.jobrecommender.backend.entity.Company;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyRepository extends Neo4jRepository<Company, String> {
    Optional<Company> findByName(String name);

    @Query("MERGE (c:Company {name: $name}) ON CREATE SET c.id = randomUUID() SET c.id = coalesce(c.id, randomUUID()) RETURN c")
    Company mergeByName(String name);

    @Query(
            value = "MATCH (c:Company) WHERE toLower(c.name) CONTAINS toLower($name) RETURN c ORDER BY c.name SKIP $skip LIMIT $limit",
            countQuery = "MATCH (c:Company) WHERE toLower(c.name) CONTAINS toLower($name) RETURN count(c)"
    )
    Page<Company> searchByName(String name, Pageable pageable);
}