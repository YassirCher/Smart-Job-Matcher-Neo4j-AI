package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.SkillEmbeddingProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class Neo4jVectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jVectorIndexService.class);

    private static final String INDEX_NAME = "skill_embedding_index";
    private static final Duration CHECK_THROTTLE = Duration.ofSeconds(20);

    private final Neo4jClient neo4jClient;
    private final SkillEmbeddingProperties embeddingProperties;

    private volatile long lastCheckEpochMs = 0L;
    private volatile boolean indexOnline = false;
    private volatile boolean embeddingTypesNormalized = false;

    public void ensureSkillEmbeddingIndex() {
        if (!embeddingProperties.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (indexOnline && (now - lastCheckEpochMs) < CHECK_THROTTLE.toMillis()) {
            return;
        }

        synchronized (this) {
            now = System.currentTimeMillis();
            if (indexOnline && (now - lastCheckEpochMs) < CHECK_THROTTLE.toMillis()) {
                return;
            }

            normalizeEmbeddingTypes();
            sanitizeInvalidEmbeddings();

            Optional<String> state = readIndexState();
            if (state.isEmpty()) {
                createIndexIfMissing();
            }

            waitUntilOnline();
            lastCheckEpochMs = System.currentTimeMillis();
        }
    }

    private void normalizeEmbeddingTypes() {
        if (embeddingTypesNormalized) {
            return;
        }

        String normalizeQuery =
                "MATCH (s:Skill) " +
                "WHERE s.embedding IS NOT NULL AND size(s.embedding) > 0 " +
                "SET s.embedding = [v IN s.embedding | toFloat(v)] " +
                "RETURN count(s) AS converted";

        try {
            Long converted = neo4jClient.query(normalizeQuery)
                    .fetchAs(Long.class)
                    .one()
                    .orElse(0L);

            embeddingTypesNormalized = true;
            log.info("Normalized embedding numeric types for {} Skill nodes.", converted);
        } catch (RuntimeException ex) {
            log.warn("Unable to normalize embedding numeric types: {}", ex.getMessage());
        }
    }

    private void sanitizeInvalidEmbeddings() {
        String cleanupQuery =
                "MATCH (s:Skill) " +
                "WHERE s.embedding IS NOT NULL AND (" +
                "  size(s.embedding) <> $dim OR " +
                "  NOT all(v IN s.embedding WHERE v IS NOT NULL AND v = v) OR " +
                "  reduce(norm = 0.0, v IN s.embedding | norm + toFloat(v) * toFloat(v)) <= 0.0" +
                ") " +
                "SET s.embedding = null " +
                "RETURN count(s) AS cleaned";

        try {
            Long cleaned = neo4jClient.query(cleanupQuery)
                    .bind(embeddingProperties.getDimension()).to("dim")
                    .fetchAs(Long.class)
                    .one()
                    .orElse(0L);

            if (cleaned > 0) {
                log.warn("Cleaned {} invalid Skill embeddings before vector search.", cleaned);
            }
        } catch (RuntimeException ex) {
            log.warn("Unable to sanitize invalid embeddings: {}", ex.getMessage());
        }
    }

    private Optional<String> readIndexState() {
        try {
            return neo4jClient.query(
                            "SHOW INDEXES YIELD name, state WHERE name = $indexName RETURN state LIMIT 1"
                    )
                    .bind(INDEX_NAME).to("indexName")
                    .fetchAs(String.class)
                    .one();
        } catch (RuntimeException ex) {
            log.warn("Unable to read vector index state '{}': {}", INDEX_NAME, ex.getMessage());
            return Optional.empty();
        }
    }

    private void createIndexIfMissing() {
        String createQuery = "CREATE VECTOR INDEX " + INDEX_NAME + " IF NOT EXISTS " +
                "FOR (s:Skill) ON (s.embedding) " +
                "OPTIONS {indexConfig: {`vector.dimensions`: $dim, `vector.similarity_function`: 'cosine'}}";

        try {
            neo4jClient.query(createQuery)
                    .bind(embeddingProperties.getDimension()).to("dim")
                    .run();
            log.info("Vector index '{}' created or already existed.", INDEX_NAME);
        } catch (RuntimeException ex) {
            log.warn("Unable to create vector index '{}': {}", INDEX_NAME, ex.getMessage());
        }
    }

    private void waitUntilOnline() {
        for (int i = 0; i < 40; i++) {
            Optional<String> state = readIndexState();
            if (state.isPresent() && "ONLINE".equalsIgnoreCase(state.get())) {
                indexOnline = true;
                return;
            }

            sleep(250);
        }

        indexOnline = false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
