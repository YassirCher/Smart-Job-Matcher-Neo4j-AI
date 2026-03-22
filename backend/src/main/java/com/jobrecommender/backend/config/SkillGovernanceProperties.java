package com.jobrecommender.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "skills")
public class SkillGovernanceProperties {

    /**
     * Alias -> canonical skill mapping. Configure in application.properties with keys like:
     * skills.aliases.ml=machine learning
     */
    private Map<String, String> aliases = new LinkedHashMap<>();
}
