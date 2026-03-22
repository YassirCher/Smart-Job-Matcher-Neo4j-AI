package com.jobrecommender.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PendingGitHubAnalysisStore {

    private final long ttlMillis;
    private final Map<String, PendingAnalysis> store = new ConcurrentHashMap<>();

    public PendingGitHubAnalysisStore(@Value("${github.analyzer.analysis-ttl-seconds:1200}") long ttlSeconds) {
        this.ttlMillis = Math.max(60, ttlSeconds) * 1000L;
    }

    public String put(String candidateId, List<String> skillIds) {
        cleanupExpired();
        String analysisId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        PendingAnalysis pending = new PendingAnalysis(
                analysisId,
                candidateId,
                skillIds == null ? List.of() : skillIds,
                now,
                now + ttlMillis
        );
        store.put(analysisId, pending);
        return analysisId;
    }

    public List<String> consume(String candidateId, String analysisId) {
        if (!StringUtils.hasText(analysisId)) {
            throw new IllegalArgumentException("analysisId is required");
        }
        PendingAnalysis pending = store.remove(analysisId.trim());
        if (pending == null) {
            throw new IllegalArgumentException("Analysis session not found or expired");
        }
        if (pending.expiresAtEpochMs() < System.currentTimeMillis()) {
            throw new IllegalArgumentException("Analysis session expired");
        }
        if (!pending.candidateId().equals(candidateId)) {
            throw new IllegalArgumentException("Analysis session does not belong to this candidate");
        }
        return pending.skillIds();
    }

    public long expiresAt(String analysisId) {
        PendingAnalysis pending = store.get(analysisId);
        if (pending == null) {
            return 0L;
        }
        return pending.expiresAtEpochMs();
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expiresAtEpochMs() < now);
        if (store.size() > 5000) {
            log.warn("Pending GitHub analysis store is large at {} entries ({}).", store.size(), Instant.ofEpochMilli(now));
        }
    }

    public record PendingAnalysis(
            String analysisId,
            String candidateId,
            List<String> skillIds,
            long createdAtEpochMs,
            long expiresAtEpochMs
    ) {
    }
}
