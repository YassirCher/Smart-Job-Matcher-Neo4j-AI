package com.jobrecommender.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobrecommender.backend.config.GroqProperties;
import com.jobrecommender.backend.dto.InterviewQuestion;
import com.jobrecommender.backend.dto.InterviewScriptJobResponse;
import jakarta.annotation.PreDestroy;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicInterviewGeneratorService {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";

    private static final String SYSTEM_PROMPT = """
            You are a Principal Engineer conducting senior-level technical interviews.
            Goal: generate high-signal, difficult, role-relevant verification questions for claimed skills.
            Output MUST be strict JSON only and follow this exact schema:
            {
              "questions": [
                {
                  "skill": "string",
                  "question": "string",
                  "expectedSignals": ["string", "string"]
                }
              ]
            }

            Rules:
            - Generate between 3 and 5 questions.
            - Questions must be senior-level, practical, and falsifiable.
            - Avoid generic theory questions.
            - Each expectedSignals entry must be concise keyword(s) recruiters can listen for.
            - Keep expectedSignals length between 4 and 7 items per question.
            - No markdown, no comments, no extra keys.
            """;

    private final GroqProperties groqProperties;
    private final SkillCanonicalizationService skillCanonicalizationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setName("dynamic-interview-generator");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<String, InterviewJob> jobs = new ConcurrentHashMap<>();

    private final Deque<TokenWindowEntry> rollingWindow = new ArrayDeque<>();
    private final Object tokenLock = new Object();
    private int rollingWindowTokens = 0;

    public InterviewScriptJobResponse startGeneration(
            String candidateId,
            String candidateName,
            List<String> claimedButUnverified,
            Integer targetQuestions
    ) {
        List<String> skills = canonicalizeSkills(claimedButUnverified);
        if (skills.isEmpty()) {
            throw new IllegalArgumentException("claimedButUnverified must contain at least one skill");
        }

        int questionCount = normalizeQuestionCount(targetQuestions);
        long now = System.currentTimeMillis();
        String jobId = UUID.randomUUID().toString();

        InterviewJob job = new InterviewJob(
                jobId,
                candidateId,
                candidateName,
                skills,
                questionCount,
                now
        );
        jobs.put(jobId, job);
        cleanupExpiredJobs(now);

        CompletableFuture.runAsync(() -> generateInterview(job), executor);
        return toResponse(job);
    }

    public InterviewScriptJobResponse getJob(String candidateId, String jobId) {
        InterviewJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Interview generation job not found");
        }
        if (!job.candidateId.equals(candidateId)) {
            throw new IllegalArgumentException("Interview generation job does not belong to this candidate");
        }

        cleanupExpiredJobs(System.currentTimeMillis());
        return toResponse(job);
    }

    private void generateInterview(InterviewJob job) {
        long started = System.currentTimeMillis();
        job.status = STATUS_IN_PROGRESS;
        job.message = "Generation in progress";

        try {
            if (!StringUtils.hasText(groqProperties.getApiKey())) {
                job.fallbackUsed = true;
                job.model = "fallback-template";
                job.message = "Fallback used: GROQ_API_KEY missing";
                job.questions = buildFallbackQuestions(job.skills, job.targetQuestions);
                job.status = STATUS_COMPLETED;
                job.completedAtEpochMs = System.currentTimeMillis();
                return;
            }

            String userPrompt = buildUserPrompt(job.candidateName, job.skills, job.targetQuestions);
            int estimatedTokens = estimateTokenCost(SYSTEM_PROMPT) + estimateTokenCost(userPrompt) + 900;
            if (!tryReserveTokens(estimatedTokens)) {
                job.fallbackUsed = true;
                job.model = "fallback-template";
                job.message = "Fallback used: Groq TPM guard activated";
                job.questions = buildFallbackQuestions(job.skills, job.targetQuestions);
                job.status = STATUS_COMPLETED;
                job.completedAtEpochMs = System.currentTimeMillis();
                return;
            }

            List<InterviewQuestion> questions = callGroq(userPrompt, job.targetQuestions);
            if (questions.size() < 3) {
                job.fallbackUsed = true;
                job.model = "fallback-template";
                job.message = "Fallback used: Groq output quality below threshold";
                job.questions = buildFallbackQuestions(job.skills, job.targetQuestions);
            } else {
                job.fallbackUsed = false;
                job.model = groqProperties.getModel();
                job.message = "Interview script generated";
                job.questions = questions;
            }
        } catch (RuntimeException ex) {
            log.warn("[INTERVIEW_GENERATOR] fallback triggered candidateId={} jobId={} message={}",
                    job.candidateId,
                    job.jobId,
                    ex.getMessage());
            job.fallbackUsed = true;
            job.model = "fallback-template";
            job.message = "Fallback used after upstream error";
            job.questions = buildFallbackQuestions(job.skills, job.targetQuestions);
        } finally {
            job.status = STATUS_COMPLETED;
            job.completedAtEpochMs = System.currentTimeMillis();
            log.info("[INTERVIEW_GENERATOR] completed candidateId={} jobId={} fallback={} durationMs={}",
                    job.candidateId,
                    job.jobId,
                    job.fallbackUsed,
                    job.completedAtEpochMs - started);
        }
    }

    private List<InterviewQuestion> callGroq(String userPrompt, int targetQuestions) {
        Map<String, Object> payload = Map.of(
                "model", groqProperties.getModel(),
                "temperature", 0.1d,
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
            throw new IllegalStateException("Unable to build Groq interview payload", ex);
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
                throw new IllegalStateException("Groq interview call failed with status " + response.statusCode());
            }
            responseBody = response.body();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Groq interview call failed", ex);
        }

        return parseQuestions(responseBody, targetQuestions);
    }

    private List<InterviewQuestion> parseQuestions(String groqResponseBody, int targetQuestions) {
        try {
            JsonNode root = objectMapper.readTree(groqResponseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) {
                return List.of();
            }

            JsonNode parsed = objectMapper.readTree(content);
            JsonNode questionsNode = parsed.path("questions");
            if (!questionsNode.isArray()) {
                return List.of();
            }

            List<InterviewQuestion> out = new ArrayList<>();
            LinkedHashSet<String> dedupe = new LinkedHashSet<>();
            for (JsonNode node : questionsNode) {
                String question = node.path("question").asText("").trim();
                String skill = node.path("skill").asText("").trim().toLowerCase(Locale.ROOT);
                String dedupeKey = (skill + "|" + question).toLowerCase(Locale.ROOT);
                if (!StringUtils.hasText(question) || !dedupe.add(dedupeKey)) {
                    continue;
                }

                List<String> signals = new ArrayList<>();
                JsonNode signalsNode = node.path("expectedSignals");
                if (signalsNode.isArray()) {
                    for (JsonNode sig : signalsNode) {
                        String value = sig.asText("").trim();
                        if (StringUtils.hasText(value) && signals.size() < 7) {
                            signals.add(value);
                        }
                    }
                }
                if (signals.size() < 3) {
                    signals = expectedSignalsForSkill(skill);
                }

                out.add(new InterviewQuestion(
                        StringUtils.hasText(skill) ? skill : "technical depth",
                        question,
                        signals
                ));

                if (out.size() >= targetQuestions) {
                    break;
                }
            }
            return out;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to parse Groq interview response", ex);
        }
    }

    private List<String> buildFallbackSkillSequence(List<String> skills, int targetQuestions) {
        List<String> sequence = new ArrayList<>();
        if (skills.isEmpty()) {
            return sequence;
        }
        int idx = 0;
        while (sequence.size() < targetQuestions) {
            sequence.add(skills.get(idx % skills.size()));
            idx++;
        }
        return sequence;
    }

    private List<InterviewQuestion> buildFallbackQuestions(List<String> skills, int targetQuestions) {
        List<String> sequence = buildFallbackSkillSequence(skills, targetQuestions);
        List<InterviewQuestion> out = new ArrayList<>();

        for (int i = 0; i < sequence.size(); i++) {
            String skill = sequence.get(i);
            String question;
            int pattern = i % 4;
            if (pattern == 0) {
                question = "Sur " + skill + ", raconte une panne production complexe que tu as resolue. Decris ton protocole de diagnostic, les hypotheses eliminees, puis le rollback ou le correctif final.";
            } else if (pattern == 1) {
                question = "Concois une architecture scalable basee sur " + skill + " pour une application a forte charge. Quels compromis fais-tu entre cout, latence, observabilite et resilence ?";
            } else if (pattern == 2) {
                question = "Si un audit securite remet en cause ton implementation " + skill + ", quelles menaces prioritaires traites-tu d'abord et comment prouves-tu que le risque est reduit ?";
            } else {
                question = "Imagine que les performances degradent fortement sur un composant en " + skill + ". Quelles metriques examines-tu en premier et quel plan d'amelioration incremental proposes-tu ?";
            }

            out.add(new InterviewQuestion(skill, question, expectedSignalsForSkill(skill)));
        }

        return out;
    }

    private List<String> expectedSignalsForSkill(String skill) {
        String s = skill == null ? "" : skill.toLowerCase(Locale.ROOT);
        if (s.contains("java") || s.contains("spring")) {
            return List.of("profiling jfr", "thread dump", "gc tuning", "transaction boundaries", "resilience4j", "observability" );
        }
        if (s.contains("angular") || s.contains("frontend") || s.contains("react")) {
            return List.of("change detection", "state management", "lazy loading", "bundle budget", "web vitals", "error boundaries" );
        }
        if (s.contains("sql") || s.contains("postgres") || s.contains("mysql") || s.contains("neo4j")) {
            return List.of("query plan", "index strategy", "data model", "isolation level", "hot path", "rollback strategy" );
        }
        if (s.contains("docker") || s.contains("kubernetes") || s.contains("devops")) {
            return List.of("resource limits", "health probes", "rolling update", "incident runbook", "slo", "cost controls" );
        }
        return List.of("root cause analysis", "trade-off analysis", "latency budget", "failure mode", "monitoring metrics", "production hardening" );
    }

    private String buildUserPrompt(String candidateName, List<String> skills, int targetQuestions) {
        String effectiveName = StringUtils.hasText(candidateName) ? candidateName.trim() : "candidate";

        StringBuilder sb = new StringBuilder();
        sb.append("Candidate: ").append(effectiveName).append("\n");
        sb.append("Context: those skills are claimed on CV but not verified on GitHub.\n");
        sb.append("Target question count: ").append(targetQuestions).append("\n");
        sb.append("Skills to challenge:\n");
        for (String skill : skills) {
            sb.append("- ").append(skill).append("\n");
        }
        sb.append("Generate a recruiter-ready script in French with senior-level technical questions.");

        String raw = sb.toString();
        int maxChars = Math.max(1200, groqProperties.getInterviewMaxPromptChars());
        if (raw.length() <= maxChars) {
            return raw;
        }
        return raw.substring(0, maxChars);
    }

    private int estimateTokenCost(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0d);
    }

    private boolean tryReserveTokens(int requestedTokens) {
        synchronized (tokenLock) {
            long now = System.currentTimeMillis();
            while (!rollingWindow.isEmpty()) {
                TokenWindowEntry head = rollingWindow.peekFirst();
                if (head == null || now - head.timestampMs <= 60_000L) {
                    break;
                }
                rollingWindow.pollFirst();
                rollingWindowTokens -= head.tokens;
            }

            int budget = Math.max(1, groqProperties.getInterviewTpmBudget());
            if (rollingWindowTokens + requestedTokens > budget) {
                return false;
            }

            rollingWindow.addLast(new TokenWindowEntry(now, requestedTokens));
            rollingWindowTokens += requestedTokens;
            return true;
        }
    }

    private int normalizeQuestionCount(Integer requested) {
        int value = requested == null ? 4 : requested;
        return Math.max(3, Math.min(5, value));
    }

    private List<String> canonicalizeSkills(List<String> rawSkills) {
        if (rawSkills == null || rawSkills.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        int limit = Math.max(3, groqProperties.getInterviewPromptSkillLimit());
        for (String raw : rawSkills) {
            String canonical = skillCanonicalizationService.canonicalize(raw);
            if (StringUtils.hasText(canonical)) {
                out.add(canonical.trim().toLowerCase(Locale.ROOT));
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private InterviewScriptJobResponse toResponse(InterviewJob job) {
        return new InterviewScriptJobResponse(
                job.jobId,
                job.candidateId,
                job.status,
                Math.max(500, groqProperties.getInterviewPollMs()),
                job.message,
                job.startedAtEpochMs,
                job.completedAtEpochMs,
                job.model,
                job.fallbackUsed,
                job.questions
        );
    }

    private void cleanupExpiredJobs(long now) {
        long ttlMs = Math.max(300, groqProperties.getInterviewJobTtlSeconds()) * 1000L;
        for (Map.Entry<String, InterviewJob> entry : jobs.entrySet()) {
            InterviewJob value = entry.getValue();
            Long completedAt = value.completedAtEpochMs;
            if (completedAt != null && now - completedAt > ttlMs) {
                jobs.remove(entry.getKey());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private record TokenWindowEntry(long timestampMs, int tokens) {
    }

    private static final class InterviewJob {
        private final String jobId;
        private final String candidateId;
        private final String candidateName;
        private final List<String> skills;
        private final int targetQuestions;
        private final long startedAtEpochMs;

        private volatile String status;
        private volatile String message;
        private volatile Long completedAtEpochMs;
        private volatile String model;
        private volatile boolean fallbackUsed;
        private volatile List<InterviewQuestion> questions;

        private InterviewJob(
                String jobId,
                String candidateId,
                String candidateName,
                List<String> skills,
                int targetQuestions,
                long startedAtEpochMs
        ) {
            this.jobId = jobId;
            this.candidateId = candidateId;
            this.candidateName = candidateName;
            this.skills = List.copyOf(skills);
            this.targetQuestions = targetQuestions;
            this.startedAtEpochMs = startedAtEpochMs;
            this.status = STATUS_QUEUED;
            this.message = "Generation queued";
            this.completedAtEpochMs = null;
            this.model = "pending";
            this.fallbackUsed = false;
            this.questions = List.of();
        }
    }
}
