package com.jobrecommender.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "groq")
public class GroqProperties {

    private String apiKey = "";

    private String baseUrl = "https://api.groq.com/openai/v1";

    private String model = "qwen/qwen3-32b";

    private double temperature = 0.0d;

    private int timeoutSeconds = 30;

    private int interviewTpmBudget = 5200;

    private int interviewPromptSkillLimit = 8;

    private int interviewMaxPromptChars = 3500;

    private int interviewJobTtlSeconds = 900;

    private int interviewPollMs = 1200;

    private int softSkillTpmBudget = 2200;

    private int softSkillMaxPromptChars = 4200;

    private int softSkillJobTtlSeconds = 900;

    private int softSkillPollMs = 1200;
}
