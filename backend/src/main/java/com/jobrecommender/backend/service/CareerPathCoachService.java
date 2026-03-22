package com.jobrecommender.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobrecommender.backend.config.GroqProperties;
import com.jobrecommender.backend.dto.CareerPathPredictionSkillDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CareerPathCoachService {

    private static final String SYSTEM_PROMPT = """
            You are a senior career coach for software engineers and data professionals.
            Your task: transform graph analytics into a short, motivating and personalized coaching message.
            Constraints:
            - Output plain text only.
            - Keep it between 90 and 150 words.
            - Mention the top recommended skill and why it unlocks new opportunities.
            - Mention at least one statistic from the analytics payload.
            - End with 2 concrete next actions in bullet-like format prefixed by '- '.
            Tone: pragmatic, motivational, non-generic.
            """;

    private final GroqProperties groqProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    public CoachingResult buildCoachingMessage(
            String candidateName,
            List<String> currentSkills,
            List<CareerPathPredictionSkillDTO> recommendations
    ) {
        if (recommendations == null || recommendations.isEmpty()) {
            return new CoachingResult(
                    "Aucune recommandation prioritaire n'a ete detectee pour le moment. Continue a consolider tes competences actuelles et reviens apres avoir enrichi ton profil.",
                    false
            );
        }

        if (!StringUtils.hasText(groqProperties.getApiKey())) {
            return new CoachingResult(buildFallbackMessage(candidateName, recommendations), false);
        }

        try {
            String content = callGroq(candidateName, currentSkills, recommendations);
            if (!StringUtils.hasText(content)) {
                return new CoachingResult(buildFallbackMessage(candidateName, recommendations), false);
            }
            return new CoachingResult(content.trim(), true);
        } catch (RuntimeException ex) {
            log.warn("Career coach LLM fallback triggered: {}", ex.getMessage());
            return new CoachingResult(buildFallbackMessage(candidateName, recommendations), false);
        }
    }

    private String callGroq(
            String candidateName,
            List<String> currentSkills,
            List<CareerPathPredictionSkillDTO> recommendations
    ) {
        Map<String, Object> promptPayload = new LinkedHashMap<>();
        promptPayload.put("candidateName", candidateName);
        promptPayload.put("currentSkills", currentSkills);
        promptPayload.put("recommendations", recommendations);

        String promptJson;
        try {
            promptJson = objectMapper.writeValueAsString(promptPayload);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to serialize career coaching payload", ex);
        }

        String userPrompt = "Use this graph analytics payload to coach the candidate:\n" + promptJson;

        Map<String, Object> requestPayload = Map.of(
                "model", groqProperties.getModel(),
                "temperature", 0.2d,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String body;
        try {
            body = objectMapper.writeValueAsString(requestPayload);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to build Groq request", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(groqProperties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, groqProperties.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + groqProperties.getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Groq career coach call failed with status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Groq career coach call failed", ex);
        }
    }

    private String buildFallbackMessage(String candidateName, List<CareerPathPredictionSkillDTO> recommendations) {
        CareerPathPredictionSkillDTO top = recommendations.get(0);
        String name = StringUtils.hasText(candidateName) ? candidateName : "Tu";
        String examples = top.sampleJobs() == null ? "" : top.sampleJobs().stream().limit(2).collect(Collectors.joining(" / "));

        return String.format(
                "%s, la competence la plus strategique a apprendre maintenant est '%s'. Les signaux du graphe montrent %d jobs debloquables, dont %d roles seniors, avec un score d'impact de %.2f. Cette competence est fortement co-occurente avec ton stack actuel (%d cooccurrences), ce qui indique une courbe d'apprentissage rentable. %s\n- Construis un mini-projet centre sur %s en reutilisant une de tes competences fortes.\n- Cible 2 offres de reference et aligne ton plan d'apprentissage sur leurs prerequis.",
                name,
                top.skillName(),
                top.unlockableJobs(),
                top.seniorUnlockableJobs(),
                top.linkPredictionScore(),
                top.cooccurrenceSupport(),
                StringUtils.hasText(examples) ? "Exemples d'opportunites: " + examples + "." : "",
                top.skillName()
        );
    }

    public record CoachingResult(String message, boolean fromLlm) {
    }
}
