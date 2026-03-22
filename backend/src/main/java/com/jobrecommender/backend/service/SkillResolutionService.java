package com.jobrecommender.backend.service;

import com.jobrecommender.backend.util.SkillNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SkillResolutionService {

    private final SkillCanonicalizationService skillCanonicalizationService;
    private final LocalTextEmbeddingService localTextEmbeddingService;

    public ResolvedSkill resolve(String rawName) {
        String normalized = SkillNameNormalizer.normalize(rawName);
        String canonical = skillCanonicalizationService.canonicalize(rawName);

        if (!StringUtils.hasText(canonical)) {
            return new ResolvedSkill(rawName == null ? "" : rawName, normalized, "", Collections.emptyList());
        }

        List<Float> embedding;
        try {
            embedding = localTextEmbeddingService.embed(canonical);
        } catch (RuntimeException ex) {
            embedding = Collections.emptyList();
        }
        return new ResolvedSkill(rawName == null ? "" : rawName, normalized, canonical, embedding);
    }

    public record ResolvedSkill(String input, String normalized, String canonical, List<Float> embedding) {
    }
}
