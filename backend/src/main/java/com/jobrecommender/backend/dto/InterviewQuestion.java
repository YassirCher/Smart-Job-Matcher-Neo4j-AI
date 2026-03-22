package com.jobrecommender.backend.dto;

import java.util.List;

public record InterviewQuestion(
        String skill,
        String question,
        List<String> expectedSignals
) {
}
