package com.jobrecommender.backend.repository;

import com.jobrecommender.backend.entity.Location;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LocationRepository extends Neo4jRepository<Location, String> {
    Optional<Location> findByName(String name);

    @Query("MERGE (l:Location {name: $name}) ON CREATE SET l.id = randomUUID() SET l.id = coalesce(l.id, randomUUID()) RETURN l")
    Location mergeByName(String name);

    @Query(
            value = "MATCH (l:Location) WHERE toLower(l.name) CONTAINS toLower($name) RETURN l ORDER BY l.name SKIP $skip LIMIT $limit",
            countQuery = "MATCH (l:Location) WHERE toLower(l.name) CONTAINS toLower($name) RETURN count(l)"
    )
    Page<Location> searchByName(String name, Pageable pageable);
}