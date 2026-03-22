package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.CacheNames;
import com.jobrecommender.backend.entity.Location;
import com.jobrecommender.backend.repository.LocationRepository;
import com.jobrecommender.backend.util.FullTextQueryNormalizer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationService.class);

    private final LocationRepository locationRepository;
    private final Neo4jClient neo4jClient;

    private static final String LOCATION_FULLTEXT_PAGE_QUERY =
        "CALL db.index.fulltext.queryNodes('location_name_ft', $q) YIELD node, score " +
        "RETURN coalesce(node.id, '') AS id, coalesce(node.name, '') AS name " +
        "ORDER BY score DESC, name ASC SKIP $skip LIMIT $limit";

    private static final String LOCATION_FULLTEXT_COUNT_QUERY =
        "CALL db.index.fulltext.queryNodes('location_name_ft', $q) YIELD node " +
        "RETURN count(DISTINCT node) AS total";

    public Page<Location> findAll(Pageable pageable) {
        return locationRepository.findAll(pageable);
    }

    public Page<Location> searchByName(String name, Pageable pageable) {
        if (!StringUtils.hasText(name)) {
            return locationRepository.findAll(pageable);
        }
        String normalized = name.trim();
        String q = FullTextQueryNormalizer.normalize(normalized);
        if (!StringUtils.hasText(q)) {
            return locationRepository.searchByName(normalized, pageable);
        }

        try {
            long total = neo4jClient.query(LOCATION_FULLTEXT_COUNT_QUERY)
                    .bind(q).to("q")
                    .fetchAs(Long.class)
                    .one()
                    .orElse(0L);

            if (total == 0L) {
                return locationRepository.searchByName(normalized, pageable);
            }

            List<Location> content = neo4jClient.query(LOCATION_FULLTEXT_PAGE_QUERY)
                    .bind(q).to("q")
                    .bind(pageable.getOffset()).to("skip")
                    .bind(pageable.getPageSize()).to("limit")
                    .fetch()
                    .all()
                    .stream()
                    .map(row -> new Location(
                            row.get("id") == null ? "" : row.get("id").toString(),
                            row.get("name") == null ? "" : row.get("name").toString()
                    ))
                    .toList();

            return new PageImpl<>(content, pageable, total);
        } catch (RuntimeException ex) {
            log.warn("Location full-text search fallback for query '{}': {}", normalized, ex.getMessage());
            return locationRepository.searchByName(normalized, pageable);
        }
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
    public Location createOrMerge(Location location) {
        if (location == null || !StringUtils.hasText(location.getName())) {
            throw new IllegalArgumentException("Location name is required");
        }
        return locationRepository.mergeByName(location.getName().trim());
    }
}
