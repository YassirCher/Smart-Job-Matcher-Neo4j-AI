package com.jobrecommender.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobrecommender.backend.config.GitHubAnalyzerProperties;
import com.jobrecommender.backend.config.GroqProperties;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroqQwenSkillExtractionService {

    private static final Pattern RETRY_AFTER_SECONDS_PATTERN = Pattern.compile("try again in\\s+([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);

        private static final String CV_SYSTEM_PROMPT = """
                        You are an expert technical resume parser.
                        Task: extract only technical skills explicitly or strongly implied by CV text.
                        Output MUST be strict JSON only. No markdown, no comments, no extra text.

                        Return exactly this schema:
                        {
                            "skills": [
                                {
                                    "label": "string",
                                    "category": "language|framework|tool|domain",
                                    "confidence": 0.0,
                                    "evidence": ["short evidence snippets"]
                                }
                            ]
                        }

                        Rules:
                        - lowercase labels
                        - deduplicate labels
                        - confidence in [0,1]
                        - keep only technical skills relevant to software/data/ai roles
                        """;

    private static final String SYSTEM_PROMPT = """
            You are an expert software engineering and AI skill extractor.
            Task: infer candidate technical skills from GitHub repository metadata and README content.
            Output MUST be strict JSON only. No markdown, no comments, no extra text.

            Return exactly this schema:
            {
              "skills": [
                {
                  "label": "string",
                  "category": "language|framework|tool|domain",
                  "confidence": 0.0,
                  "evidence": ["short evidence snippets"]
                }
              ]
            }

            Rules:
            - Use lowercase labels.
            - Deduplicate labels.
            - Keep only technical skills.
            - Include advanced domains when clearly implied by repository intent and README context, e.g.
              machine learning, deep learning, natural language processing, web mining, computer vision, recommender systems.
            - confidence must be between 0 and 1.
            - evidence entries must be concise and grounded in provided repository content.
            """;

    private final GroqProperties groqProperties;
    private final GitHubAnalyzerProperties gitHubAnalyzerProperties;
    private final ReadmeNlpPreprocessor readmeNlpPreprocessor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    public ExtractionResult extractSkills(GitHubIngestionService.GitHubReadmePayload payload) {
        if (payload == null || payload.repositories() == null || payload.repositories().isEmpty()) {
            return new ExtractionResult(List.of());
        }

        ReadmeNlpPreprocessor.PreprocessedCorpus corpus = readmeNlpPreprocessor.preprocess(
                payload.repositories(),
                gitHubAnalyzerProperties.getMaxTotalPayloadChars(),
                gitHubAnalyzerProperties.getMaxUniqueTokens()
        );
        log.info(
                "Groq extraction input optimized: rawChars={}, cleanedChars={}, uniqueTokens={}, duplicatesDropped={}",
                corpus.rawChars(),
                corpus.cleanedChars(),
                corpus.uniqueTokenCount(),
                corpus.duplicateTokensDropped()
        );

        if (!StringUtils.hasText(groqProperties.getApiKey())) {
            return extractSkillsHeuristic(corpus);
        }

        String userPrompt = buildUserPrompt(corpus);
        String responseBody = callGroq(userPrompt);
        return parseSkills(responseBody);
    }

    public ExtractionResult extractSkillsFromCvText(String compressedCvText) {
        String input = compressedCvText == null ? "" : compressedCvText.trim();
        if (!StringUtils.hasText(input)) {
            return new ExtractionResult(List.of());
        }

        log.info("[GROQ_CV_REQ] starting CV skill extraction, payloadChars={}", input.length());

        if (!StringUtils.hasText(groqProperties.getApiKey())) {
            ExtractionResult fallback = extractSkillsHeuristic(
                    new ReadmeNlpPreprocessor.PreprocessedCorpus(
                            List.of(),
                            input,
                            input.length(),
                            input.length(),
                            input.split("\\s+").length,
                            0
                    )
            );
            log.info("[GROQ_CV_REQ] fallback extraction used, skills={}", fallback.skills().size());
            return fallback;
        }

        String responseBody = callGroqCv(input);
        ExtractionResult out = parseSkills(responseBody);
        log.info("[GROQ_CV_REQ] completed CV skill extraction, skills={}", out.skills().size());
        return out;
    }

    private String callGroq(String userPrompt) {
        Map<String, Object> payload = Map.of(
                "model", groqProperties.getModel(),
                "temperature", groqProperties.getTemperature(),
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build Groq request payload", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(groqProperties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, groqProperties.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + groqProperties.getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }

                if (status == 429 && attempt < maxAttempts) {
                    sleepQuietly(resolveRetryDelayMs(response.body()));
                    continue;
                }

                if (status == 429) {
                    throw new IllegalArgumentException("Groq rate limit reached (429). Please retry in a few seconds.");
                }

                throw new IllegalStateException("Groq call failed with status " + status + ": " + response.body());
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Groq call failed", ex);
            }
        }

        throw new IllegalStateException("Groq call failed after retries");
    }

    private String callGroqCv(String compressedCvText) {
        Map<String, Object> payload = Map.of(
                "model", groqProperties.getModel(),
                "temperature", groqProperties.getTemperature(),
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", CV_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", "Extract technical skills from this compressed CV text:\n" + compressedCvText)
                )
        );

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build Groq CV request payload", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(groqProperties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, groqProperties.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + groqProperties.getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }

                if (status == 429 && attempt < maxAttempts) {
                    sleepQuietly(resolveRetryDelayMs(response.body()));
                    continue;
                }

                if (status == 429) {
                    throw new IllegalArgumentException("Groq rate limit reached (429). Please retry in a few seconds.");
                }

                throw new IllegalStateException("Groq CV call failed with status " + status + ": " + response.body());
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Groq CV call failed", ex);
            }
        }

        throw new IllegalStateException("Groq CV call failed after retries");
    }

    private long resolveRetryDelayMs(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return 3000L;
        }

        Matcher matcher = RETRY_AFTER_SECONDS_PATTERN.matcher(responseBody);
        if (matcher.find()) {
            try {
                double seconds = Double.parseDouble(matcher.group(1));
                return Math.max(1500L, (long) (seconds * 1000L) + 300L);
            } catch (NumberFormatException ignored) {
                return 3000L;
            }
        }

        return 3000L;
    }

    private void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(Math.max(500L, delayMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private ExtractionResult parseSkills(String groqResponseBody) {
        try {
            JsonNode root = objectMapper.readTree(groqResponseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) {
                return new ExtractionResult(List.of());
            }

            JsonNode parsed = objectMapper.readTree(content);
            JsonNode skillsNode = parsed.path("skills");
            if (!skillsNode.isArray()) {
                return new ExtractionResult(List.of());
            }

            List<ExtractedSkill> skills = new ArrayList<>();
            Set<String> dedupe = new LinkedHashSet<>();

            for (JsonNode node : skillsNode) {
                String label = node.path("label").asText("").trim().toLowerCase(Locale.ROOT);
                if (!StringUtils.hasText(label) || !dedupe.add(label)) {
                    continue;
                }

                String category = node.path("category").asText("tool").trim().toLowerCase(Locale.ROOT);
                double confidence = node.path("confidence").asDouble(0.0d);
                confidence = Math.max(0.0d, Math.min(1.0d, confidence));

                List<String> evidence = new ArrayList<>();
                JsonNode evidenceNode = node.path("evidence");
                if (evidenceNode.isArray()) {
                    for (JsonNode ev : evidenceNode) {
                        String e = ev.asText("").trim();
                        if (StringUtils.hasText(e)) {
                            evidence.add(e);
                        }
                    }
                }

                skills.add(new ExtractedSkill(label, category, confidence, evidence));
            }

            return new ExtractionResult(skills);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to parse Groq response", ex);
        }
    }

    private String buildUserPrompt(ReadmeNlpPreprocessor.PreprocessedCorpus corpus) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze this compressed GitHub profile evidence and extract candidate technical skills.\\n");
        sb.append("The vocabulary below is deduplicated and cleaned from markdown/code/noise for token efficiency.\\n");
        sb.append("Global unique vocabulary:\\n");
        sb.append(corpus.globalUniqueVocabulary()).append("\\n");
        sb.append("Repository snapshots:\\n");

        int idx = 1;
        for (ReadmeNlpPreprocessor.RepoVocabulary repo : corpus.repositories()) {
            sb.append("--- repo ").append(idx++).append(" ---\\n");
            sb.append("owner: ").append(repo.owner()).append("\\n");
            sb.append("name: ").append(repo.repo()).append("\\n");
            sb.append("language: ").append(repo.language()).append("\\n");
            sb.append("topics: ").append(repo.topics()).append("\\n");
            sb.append("url: ").append(repo.htmlUrl()).append("\\n");
            sb.append("unique_vocab: ").append(repo.uniqueVocabulary()).append("\\n");
        }

        sb.append("Return strict JSON only with schema {skills:[...]}.");
        return sb.toString();
    }

    private ExtractionResult extractSkillsHeuristic(ReadmeNlpPreprocessor.PreprocessedCorpus corpus) {
        Map<String, ExtractedSkill> skills = new HashMap<>();

        for (ReadmeNlpPreprocessor.RepoVocabulary repo : corpus.repositories()) {
            collectSkill(skills, sanitize(repo.language()), "language", 0.7d, "repo language");

            if (repo.topics() != null) {
                for (String topic : repo.topics()) {
                    collectSkill(skills, sanitize(topic), "tool", 0.62d, "repo topic");
                }
            }

            String vocab = sanitize(repo.uniqueVocabulary());
            if (StringUtils.hasText(vocab)) {
                detectByKeywords(skills, vocab.toLowerCase(Locale.ROOT));
            }
        }

        if (StringUtils.hasText(corpus.globalUniqueVocabulary())) {
            detectByKeywords(skills, corpus.globalUniqueVocabulary().toLowerCase(Locale.ROOT));
        }

        return new ExtractionResult(new ArrayList<>(skills.values()));
    }

    private void detectByKeywords(Map<String, ExtractedSkill> skills, String corpus) {
        Map<String, String> keywords = Map.ofEntries(
                Map.entry("machine learning", "domain"),
                Map.entry("deep learning", "domain"),
                Map.entry("natural language processing", "domain"),
                Map.entry("computer vision", "domain"),
                Map.entry("recommender system", "domain"),
                Map.entry("spring boot", "framework"),
                Map.entry("angular", "framework"),
                Map.entry("react", "framework"),
                Map.entry("node.js", "tool"),
                Map.entry("docker", "tool"),
                Map.entry("kubernetes", "tool"),
                Map.entry("neo4j", "tool"),
                Map.entry("postgresql", "tool"),
                Map.entry("mongodb", "tool"),
                Map.entry("tensorflow", "framework"),
                Map.entry("pytorch", "framework")
        );

        for (Map.Entry<String, String> entry : keywords.entrySet()) {
            if (corpus.contains(entry.getKey())) {
                collectSkill(skills, entry.getKey(), entry.getValue(), 0.65d, "readme keyword");
            }
        }
    }

    private void collectSkill(
            Map<String, ExtractedSkill> skills,
            String rawLabel,
            String category,
            double confidence,
            String evidence
    ) {
        String label = normalizeSkillLabel(rawLabel);
        if (!StringUtils.hasText(label)) {
            return;
        }

        ExtractedSkill existing = skills.get(label);
        if (existing == null) {
            skills.put(label, new ExtractedSkill(
                    label,
                    category,
                    confidence,
                    List.of(evidence)
            ));
            return;
        }

        double mergedConfidence = Math.max(existing.confidence(), confidence);
        List<String> mergedEvidence = new ArrayList<>(existing.evidence());
        if (!mergedEvidence.contains(evidence)) {
            mergedEvidence.add(evidence);
        }
        skills.put(label, new ExtractedSkill(
                label,
                existing.category(),
                mergedConfidence,
                mergedEvidence
        ));
    }

    private String normalizeSkillLabel(String input) {
        String value = sanitize(input).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(value)) {
            return "";
        }
        value = value.replaceAll("^[^a-z0-9]+", "");
        value = value.replace('_', ' ').replace('-', ' ').trim();
        value = value.replaceAll("\\s+", " ");
        return value;
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim();
    }

    public record ExtractedSkill(
            String label,
            String category,
            double confidence,
            List<String> evidence
    ) {
    }

    public record ExtractionResult(
            List<ExtractedSkill> skills
    ) {
    }
}
