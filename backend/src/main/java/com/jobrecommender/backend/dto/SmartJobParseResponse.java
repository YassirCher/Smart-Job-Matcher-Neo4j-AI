package com.jobrecommender.backend.dto;

import com.jobrecommender.backend.entity.Job;

import java.util.List;
import java.util.Map;

public record SmartJobParseResponse(
        boolean success,
        boolean fromLlm,
        Job job,
        Map<String, String> evidences,
        List<SmartJobFieldSuggestion> skillSuggestions,
        List<String> warnings,
        List<String> inferredFields
) {
}
