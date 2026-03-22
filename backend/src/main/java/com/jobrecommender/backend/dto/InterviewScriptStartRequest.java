package com.jobrecommender.backend.dto;

import java.util.List;

public record InterviewScriptStartRequest(
        List<String> claimedButUnverified,
        Integer targetQuestions
) {
}
