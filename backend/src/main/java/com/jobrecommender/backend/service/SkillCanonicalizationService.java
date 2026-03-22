package com.jobrecommender.backend.service;

import com.jobrecommender.backend.config.SkillGovernanceProperties;
import com.jobrecommender.backend.util.SkillNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SkillCanonicalizationService {

    private final SkillGovernanceProperties properties;

    public String canonicalize(String rawName) {
        String normalized = SkillNameNormalizer.normalize(rawName);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }

        Map<String, String> aliasMap = buildAliasMap();

        String exactMatch = aliasMap.get(normalized);
        if (StringUtils.hasText(exactMatch)) {
            return exactMatch;
        }

        String aggressiveKey = toAggressiveKey(normalized);
        if (!StringUtils.hasText(aggressiveKey)) {
            return normalized;
        }

        for (Map.Entry<String, String> entry : aliasMap.entrySet()) {
            if (aggressiveKey.equals(toAggressiveKey(entry.getKey()))) {
                return entry.getValue();
            }
        }

        return normalized;
    }

    private Map<String, String> buildAliasMap() {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        if (properties.getAliases() == null || properties.getAliases().isEmpty()) {
            return aliasMap;
        }

        for (Map.Entry<String, String> entry : properties.getAliases().entrySet()) {
            String alias = SkillNameNormalizer.normalize(entry.getKey());
            String canonical = SkillNameNormalizer.normalize(entry.getValue());
            if (!StringUtils.hasText(alias) || !StringUtils.hasText(canonical)) {
                continue;
            }
            aliasMap.put(alias, canonical);
        }
        return aliasMap;
    }

    private String toAggressiveKey(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }
}
