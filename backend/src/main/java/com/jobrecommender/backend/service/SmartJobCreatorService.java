package com.jobrecommender.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobrecommender.backend.config.GroqProperties;
import com.jobrecommender.backend.dto.SmartJobFieldSuggestion;
import com.jobrecommender.backend.dto.SmartJobParseResponse;
import com.jobrecommender.backend.entity.Company;
import com.jobrecommender.backend.entity.Job;
import com.jobrecommender.backend.entity.Location;
import com.jobrecommender.backend.entity.Skill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmartJobCreatorService {

    private static final String SYSTEM_PROMPT = """
            You are an expert information extraction engine for recruiting systems.
            You receive a raw job description and a list of allowed field paths inferred from a graph data model.

            Output MUST be strict JSON only with this exact structure:
            {
              "suggestions": [
                {
                  "path": "string",
                  "value": "string",
                  "evidence": "string"
                }
              ],
              "warnings": ["string"]
            }

            Rules:
            - Use ONLY allowed paths. Never invent new paths.
            - For each suggestion, evidence MUST be an exact verbatim quote from source text.
            - If a value is uncertain, omit it.
            - For list fields such as skills[].name, return one suggestion per detected item.
            - No markdown, no comments, no extra keys.
            """;

    private final GroqProperties groqProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    public SmartJobParseResponse parseRawDescription(String rawText) {
        List<FieldDescriptor> inferredFieldDescriptors = inferJobFieldDescriptors();
        List<String> inferredFields = inferredFieldDescriptors.stream().map(FieldDescriptor::path).toList();
        String input = rawText == null ? "" : rawText.trim();

        if (!StringUtils.hasText(input) || looksUnusable(input)) {
            return emptyResponse(
                    false,
                    inferredFields,
                    "Texte incomprehensible ou trop court. Continuez en mode manuel sans blocage."
            );
        }

        if (!StringUtils.hasText(groqProperties.getApiKey())) {
            return emptyResponse(
                    false,
                    inferredFields,
                    "Parsing IA indisponible (GROQ_API_KEY absent). Le formulaire manuel reste pleinement disponible."
            );
        }

        try {
            List<SmartJobFieldSuggestion> suggestions = callGroq(input, inferredFieldDescriptors);
            return mapSuggestionsToResponse(input, suggestions, inferredFields, true);
        } catch (RuntimeException ex) {
            log.warn("Smart job parsing fallback triggered: {}", ex.getMessage());
            return emptyResponse(
                    false,
                    inferredFields,
                    "Echec du parsing IA (service indisponible). Vous pouvez finaliser l'offre manuellement."
            );
        }
    }

    private List<SmartJobFieldSuggestion> callGroq(String rawText, List<FieldDescriptor> inferredFieldDescriptors) {
        List<Map<String, Object>> fieldContracts = inferredFieldDescriptors.stream()
            .map(fd -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", fd.path);
                entry.put("neo4jProperty", fd.neo4jProperty);
                entry.put("multiple", fd.multiple);
                return entry;
            })
            .toList();

        String contractJson;
        try {
            contractJson = objectMapper.writeValueAsString(fieldContracts);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to serialize inferred field contract", ex);
        }

        String userPrompt = "Allowed field contract (inferred from Job graph model):\n"
                + contractJson
                + "\n\nRaw job description:\n"
                + rawText;

        Map<String, Object> payload = Map.of(
                "model", groqProperties.getModel(),
                "temperature", 0.0d,
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
            throw new IllegalStateException("Unable to build Groq smart parsing payload", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(groqProperties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, groqProperties.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + groqProperties.getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        String responseBody;
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Groq smart parsing call failed with status " + response.statusCode());
            }
            responseBody = response.body();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Groq smart parsing call failed", ex);
        }

        return parseSuggestions(responseBody, inferredFieldDescriptors);
    }

    private List<SmartJobFieldSuggestion> parseSuggestions(String groqResponseBody, List<FieldDescriptor> inferredFieldDescriptors) {
        Set<String> allowedPaths = inferredFieldDescriptors.stream().map(FieldDescriptor::path).collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        List<SmartJobFieldSuggestion> parsed = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(groqResponseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) {
                return List.of();
            }

            JsonNode extraction = objectMapper.readTree(content);
            JsonNode suggestions = extraction.path("suggestions");
            if (!suggestions.isArray()) {
                return List.of();
            }

            for (JsonNode node : suggestions) {
                String path = node.path("path").asText("").trim();
                String value = node.path("value").asText("").trim();
                String evidence = node.path("evidence").asText("").trim();

                if (!allowedPaths.contains(path) || !StringUtils.hasText(value) || !StringUtils.hasText(evidence)) {
                    continue;
                }
                parsed.add(new SmartJobFieldSuggestion(path, value, evidence));
            }

            return parsed;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to parse Groq smart parsing response", ex);
        }
    }

    private SmartJobParseResponse mapSuggestionsToResponse(
            String rawText,
            List<SmartJobFieldSuggestion> suggestions,
            List<String> inferredFields,
            boolean fromLlm
    ) {
        Job job = Job.builder().skills(new ArrayList<>()).build();
        Map<String, String> evidences = new LinkedHashMap<>();
        List<SmartJobFieldSuggestion> skillSuggestions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> seenSkills = new LinkedHashSet<>();

        for (SmartJobFieldSuggestion suggestion : suggestions) {
            if (!isEvidenceInSource(rawText, suggestion.evidence())) {
                warnings.add("Evidence ignoree (non retrouvee dans le texte source) pour le champ: " + suggestion.path());
                continue;
            }

            String value = suggestion.value().trim();
            switch (suggestion.path()) {
                case "jobLink" -> {
                    job.setJobLink(value);
                    evidences.put("jobLink", suggestion.evidence());
                }
                case "title" -> {
                    job.setTitle(value);
                    evidences.put("title", suggestion.evidence());
                }
                case "type" -> {
                    job.setType(value);
                    evidences.put("type", suggestion.evidence());
                }
                case "level" -> {
                    job.setLevel(value);
                    evidences.put("level", suggestion.evidence());
                }
                case "company.name" -> {
                    job.setCompany(Company.builder().name(value).build());
                    evidences.put("company.name", suggestion.evidence());
                }
                case "location.name" -> {
                    job.setLocation(Location.builder().name(value).build());
                    evidences.put("location.name", suggestion.evidence());
                }
                case "skills[].name" -> {
                    String key = value.toLowerCase(Locale.ROOT);
                    if (seenSkills.add(key)) {
                        job.getSkills().add(Skill.builder().name(value).build());
                        skillSuggestions.add(suggestion);
                    }
                }
                default -> {
                    // Ignore unknown fields defensively.
                }
            }
        }

        boolean success = hasAnyExtractedValue(job);
        if (!success) {
            warnings.add("Aucune valeur fiable extraite. Le recruteur peut poursuivre en saisie manuelle.");
        }

        return new SmartJobParseResponse(
                success,
                fromLlm,
                job,
                evidences,
                skillSuggestions,
                warnings,
                inferredFields
        );
    }

    private SmartJobParseResponse emptyResponse(boolean fromLlm, List<String> inferredFields, String warning) {
        Job emptyJob = Job.builder().skills(new ArrayList<>()).build();
        return new SmartJobParseResponse(
                false,
                fromLlm,
                emptyJob,
                Map.of(),
                List.of(),
                List.of(warning),
                inferredFields
        );
    }

    private boolean looksUnusable(String input) {
        String compact = input.replaceAll("\\s+", " ").trim();
        if (compact.length() < 20) {
            return true;
        }
        String[] words = compact.split(" ");
        return words.length < 5;
    }

    private boolean hasAnyExtractedValue(Job job) {
        if (job == null) {
            return false;
        }
        return StringUtils.hasText(job.getJobLink())
                || StringUtils.hasText(job.getTitle())
                || StringUtils.hasText(job.getType())
                || StringUtils.hasText(job.getLevel())
                || (job.getCompany() != null && StringUtils.hasText(job.getCompany().getName()))
                || (job.getLocation() != null && StringUtils.hasText(job.getLocation().getName()))
                || (job.getSkills() != null && !job.getSkills().isEmpty());
    }

    private boolean isEvidenceInSource(String rawText, String evidence) {
        if (!StringUtils.hasText(rawText) || !StringUtils.hasText(evidence)) {
            return false;
        }
        return rawText.contains(evidence) || rawText.toLowerCase(Locale.ROOT).contains(evidence.toLowerCase(Locale.ROOT));
    }

    private List<FieldDescriptor> inferJobFieldDescriptors() {
        List<FieldDescriptor> descriptors = new ArrayList<>();

        for (Field field : Job.class.getDeclaredFields()) {
            if (field.getType().equals(String.class)) {
                Property propertyAnnotation = field.getAnnotation(Property.class);
                String neo4jProperty = propertyAnnotation != null && StringUtils.hasText(propertyAnnotation.value())
                        ? propertyAnnotation.value().trim()
                        : field.getName();

                descriptors.add(new FieldDescriptor(field.getName(), neo4jProperty, false));
                continue;
            }

            if (field.getAnnotation(Relationship.class) == null) {
                continue;
            }

            if (List.class.isAssignableFrom(field.getType())) {
                Class<?> elementType = resolveListType(field);
                if (elementType != null && hasNameField(elementType)) {
                    descriptors.add(new FieldDescriptor(field.getName() + "[].name", "name", true));
                }
            } else if (hasNameField(field.getType())) {
                descriptors.add(new FieldDescriptor(field.getName() + ".name", "name", false));
            }
        }

        descriptors.sort(Comparator.comparing(FieldDescriptor::path));
        return descriptors;
    }

    private Class<?> resolveListType(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return null;
        }

        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (actualTypeArguments.length == 0 || !(actualTypeArguments[0] instanceof Class<?> clazz)) {
            return null;
        }
        return clazz;
    }

    private boolean hasNameField(Class<?> type) {
        try {
            Field name = type.getDeclaredField("name");
            return name.getType().equals(String.class);
        } catch (NoSuchFieldException ex) {
            return false;
        }
    }

    private record FieldDescriptor(String path, String neo4jProperty, boolean multiple) {
    }
}
