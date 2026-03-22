package com.jobrecommender.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PendingResumeEvidenceStore {

    private final long ttlMillis;
    private final Map<String, ResumeEvidenceContext> contexts = new ConcurrentHashMap<>();

    public PendingResumeEvidenceStore(@Value("${github.analyzer.analysis-ttl-seconds:1200}") long ttlSeconds) {
        this.ttlMillis = Math.max(120, ttlSeconds) * 1000L;
    }

    public void put(String candidateId, String compressedCvText, String detectedGithubUsername) {
        if (!StringUtils.hasText(candidateId)) {
            return;
        }
        cleanupExpired();
        long now = System.currentTimeMillis();
        contexts.put(candidateId.trim(), new ResumeEvidenceContext(
                candidateId.trim(),
                compressedCvText == null ? "" : compressedCvText,
                detectedGithubUsername == null ? "" : detectedGithubUsername,
                now + ttlMillis
        ));
    }

    public ResumeEvidenceContext get(String candidateId) {
        if (!StringUtils.hasText(candidateId)) {
            return null;
        }
        ResumeEvidenceContext ctx = contexts.get(candidateId.trim());
        if (ctx == null) {
            return null;
        }
        if (ctx.expiresAtEpochMs() < System.currentTimeMillis()) {
            contexts.remove(candidateId.trim());
            return null;
        }
        return ctx;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        contexts.entrySet().removeIf(e -> e.getValue().expiresAtEpochMs() < now);
    }

    public record ResumeEvidenceContext(
            String candidateId,
            String compressedCvText,
            String detectedGithubUsername,
            long expiresAtEpochMs
    ) {
    }
}
