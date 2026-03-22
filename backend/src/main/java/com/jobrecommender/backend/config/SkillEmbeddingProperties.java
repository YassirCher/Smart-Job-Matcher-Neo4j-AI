package com.jobrecommender.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "skills.embedding")
public class SkillEmbeddingProperties {

    private boolean enabled = true;

    private String modelId = "sentence-transformers/all-MiniLM-L6-v2";

    private int dimension = 384;

    private boolean normalize = true;
}
