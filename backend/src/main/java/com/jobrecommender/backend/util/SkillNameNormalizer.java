package com.jobrecommender.backend.util;

import java.util.Locale;

public final class SkillNameNormalizer {

    private SkillNameNormalizer() {
    }

    public static String normalize(String rawName) {
        if (rawName == null) {
            return "";
        }

        String normalized = rawName.strip().toLowerCase(Locale.ROOT);
        // Remove common list bullet prefixes such as "* skill" or "- skill"
        normalized = normalized.replaceAll("^[*\\-•]+\\s*", "");
        // Collapse duplicate spaces/tabs/newlines into one space
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized;
    }
}
