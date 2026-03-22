package com.jobrecommender.backend.dto;

public record SmartJobFieldSuggestion(
        String path,
        String value,
        String evidence
) {
}
