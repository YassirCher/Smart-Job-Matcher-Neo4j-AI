package com.jobrecommender.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "github.analyzer")
public class GitHubAnalyzerProperties {

    private String apiBaseUrl = "https://api.github.com";

    private String token = "";

    private int maxRepos = 12;

    private boolean includeForks = false;

    private int maxReadmeCharsPerRepo = 7000;

    private int maxTotalPayloadChars = 50000;

    private int maxUniqueTokens = 1200;

    private int analysisTtlSeconds = 1200;

    private int vectorTopK = 5;

    private double vectorMinScore = 0.88d;

    private double vectorMinMargin = 0.03d;

    private double llmMinConfidence = 0.55d;
}
