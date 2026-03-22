package com.jobrecommender.backend.util;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FullTextQueryNormalizer {

    private FullTextQueryNormalizer() {
    }

    public static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }

        String[] tokens = raw.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<String> parts = new ArrayList<>();
        for (String token : tokens) {
            String clean = token.replaceAll("[+\\-&|!(){}\\[\\]^\\\"~*?:\\\\/]", "").trim();
            if (!clean.isEmpty()) {
                parts.add(clean + "*");
            }
        }
        return String.join(" AND ", parts);
    }
}
