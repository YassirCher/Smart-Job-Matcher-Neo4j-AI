package com.jobrecommender.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobrecommender.backend.config.GroqProperties;
import com.jobrecommender.backend.dto.BehavioralProfileResponse;
import com.jobrecommender.backend.dto.SoftSkillAnalysisJobResponse;
import com.jobrecommender.backend.dto.SoftSkillAnalyzeStartRequest;
import com.jobrecommender.backend.dto.SoftSkillEvidence;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
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
import java.util.LinkedHashMap;
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
public class SoftSkillBehavioralProfileService {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_COMPLETED = "completed";

    private static final String SYSTEM_PROMPT = """
            You are a senior behavioral analyst for technical recruitment.
            Infer candidate soft skills from professional tone and implicit signals in CV and GitHub evidence.
            Return strict JSON only with exact schema:
            {
              "softSkills": [
                {
                  "name": "string",
                  "confidence": 0.0,
                  "evidence": "string"
                }
              ]
            }

            Rules:
            - Keep only professionally relevant soft skills for engineering roles.
            - Evidence must be explicit and traceable to provided text signals.
            - Never invent evidence.
            - confidence in [0,1].
            - Deduplicate skills.
            - Provide max 8 soft skills.
            - Output plain JSON, no markdown.
            """;

    private final GroqProperties groqProperties;
    private final CandidateService candidateService;
    private final PendingResumeEvidenceStore pendingResumeEvidenceStore;
    private final GitHubIngestionService gitHubIngestionService;
    private final ReadmeNlpPreprocessor readmeNlpPreprocessor;
    private final Neo4jClient neo4jClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setName("soft-skill-analyzer");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentMap<String, SoftSkillJob> jobs = new ConcurrentHashMap<>();
    private final Deque<TokenWindowEntry> rollingWindow = new ArrayDeque<>();
    private final Object tokenLock = new Object();
    private int rollingTokens = 0;

    public SoftSkillAnalysisJobResponse startAnalysis(String candidateId, SoftSkillAnalyzeStartRequest request) {
        candidateService.ensureExists(candidateId);

        EvidenceCorpus corpus = buildEvidenceCorpus(candidateId, request);
        String jobId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        SoftSkillJob job = new SoftSkillJob(jobId, candidateId, corpus, now);
        jobs.put(jobId, job);
        cleanupExpiredJobs(now);

        CompletableFuture.runAsync(() -> runAnalysis(job), executor);
        return toResponse(job);
    }

    public SoftSkillAnalysisJobResponse getJob(String candidateId, String jobId) {
        SoftSkillJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Soft skill analysis job not found");
        }
        if (!job.candidateId.equals(candidateId)) {
            throw new IllegalArgumentException("Soft skill analysis job does not belong to this candidate");
        }
        cleanupExpiredJobs(System.currentTimeMillis());
        return toResponse(job);
    }

    public BehavioralProfileResponse getBehavioralProfile(String candidateId) {
        candidateService.ensureExists(candidateId);

        String cypher = """
                MATCH (c:Candidate {id: $candidateId})-[r:POSSESSES_SOFT_SKILL]->(ss:SoftSkill)
                RETURN ss.name AS softSkillName,
                       coalesce(r.confidence, 0.0) AS confidence,
                       coalesce(r.evidence, '') AS evidence
                ORDER BY confidence DESC, softSkillName ASC
                """;

        List<SoftSkillEvidence> softSkills = neo4jClient.query(cypher)
                .bind(candidateId).to("candidateId")
                .fetch().all().stream()
                .map(row -> new SoftSkillEvidence(
                        stringValue(row.get("softSkillName")),
                        doubleValue(row.get("confidence")),
                        stringValue(row.get("evidence"))
                ))
                .toList();

        return new BehavioralProfileResponse(candidateId, softSkills.size(), softSkills);
    }

    private void runAnalysis(SoftSkillJob job) {
        job.status = STATUS_IN_PROGRESS;
        job.message = "Behavioral analysis in progress";

        try {
            if (!StringUtils.hasText(job.corpus.combinedText())) {
                job.fallbackUsed = true;
                job.model = "fallback-heuristic";
                job.message = "No textual evidence available for behavioral inference";
                job.softSkills = List.of();
                return;
            }

            List<SoftSkillEvidence> output;
            if (!StringUtils.hasText(groqProperties.getApiKey())) {
                output = inferByHeuristics(job.corpus.combinedText());
                job.fallbackUsed = true;
                job.model = "fallback-heuristic";
                job.message = "Fallback used: GROQ_API_KEY missing";
            } else {
                String prompt = buildUserPrompt(job.corpus);
                int estimatedTokens = estimateTokens(SYSTEM_PROMPT) + estimateTokens(prompt) + 700;

                if (!tryReserveTokens(estimatedTokens)) {
                    output = inferByHeuristics(job.corpus.combinedText());
                    job.fallbackUsed = true;
                    job.model = "fallback-heuristic";
                    job.message = "Fallback used: Groq TPM guard activated";
                } else {
                    output = callGroq(prompt);
                    if (output.isEmpty()) {
                        output = inferByHeuristics(job.corpus.combinedText());
                        job.fallbackUsed = true;
                        job.model = "fallback-heuristic";
                        job.message = "Fallback used: no reliable soft skill extracted";
                    } else {
                        job.fallbackUsed = false;
                        job.model = groqProperties.getModel();
                        job.message = "Behavioral profile generated";
                    }
                }
            }

            upsertSoftSkills(job.candidateId, output, job.fallbackUsed ? "fallback" : "groq");
            job.softSkills = output;
        } catch (RuntimeException ex) {
            log.warn("[SOFT_SKILL_ANALYZER] fallback due to error candidateId={} jobId={} message={}",
                    job.candidateId,
                    job.jobId,
                    ex.getMessage());
            List<SoftSkillEvidence> fallback = inferByHeuristics(job.corpus.combinedText());
            upsertSoftSkills(job.candidateId, fallback, "fallback");
            job.softSkills = fallback;
            job.fallbackUsed = true;
            job.model = "fallback-heuristic";
            job.message = "Fallback used after upstream error";
        } finally {
            job.status = STATUS_COMPLETED;
            job.completedAtEpochMs = System.currentTimeMillis();
        }
    }

    private EvidenceCorpus buildEvidenceCorpus(String candidateId, SoftSkillAnalyzeStartRequest request) {
        PendingResumeEvidenceStore.ResumeEvidenceContext cached = pendingResumeEvidenceStore.get(candidateId);

        String cvText = "";
        if (request != null && StringUtils.hasText(request.cvSummary())) {
            cvText = readmeNlpPreprocessor.compressFreeText(request.cvSummary().trim(), 6000, 1200);
        } else if (cached != null && StringUtils.hasText(cached.compressedCvText())) {
            cvText = cached.compressedCvText();
        }

        String githubUsername = "";
        if (request != null && StringUtils.hasText(request.githubUsername())) {
            githubUsername = request.githubUsername().trim();
        } else if (cached != null && StringUtils.hasText(cached.detectedGithubUsername())) {
            githubUsername = cached.detectedGithubUsername().trim();
        }

        String githubText = "";
        if (StringUtils.hasText(githubUsername) || (request != null && request.repositoryUrls() != null && !request.repositoryUrls().isEmpty())) {
            GitHubIngestionService.GitHubReadmePayload payload = gitHubIngestionService.ingest(
                    githubUsername,
                    request == null ? List.of() : request.repositoryUrls(),
                    request == null ? null : request.maxRepos(),
                    request == null ? null : request.includeForks()
            );

            ReadmeNlpPreprocessor.PreprocessedCorpus corpus = readmeNlpPreprocessor.preprocess(
                    payload.repositories(),
                    10000,
                    900
            );
            githubText = corpus.globalUniqueVocabulary();
        }

        StringBuilder combined = new StringBuilder();
        if (StringUtils.hasText(cvText)) {
            combined.append("CV_SIGNALS:\n").append(cvText).append("\n\n");
        }
        if (StringUtils.hasText(githubText)) {
            combined.append("GITHUB_SIGNALS:\n").append(githubText);
        }

        return new EvidenceCorpus(cvText, githubText, combined.toString().trim());
    }

    private String buildUserPrompt(EvidenceCorpus corpus) {
        StringBuilder sb = new StringBuilder();
        sb.append("Infer candidate soft skills from the evidence below.\n");
        sb.append("Each soft skill must include a concrete evidence sentence.\n");
        sb.append(corpus.combinedText());

        String raw = sb.toString();
        int maxChars = Math.max(2000, groqProperties.getSoftSkillMaxPromptChars());
        if (raw.length() <= maxChars) {
            return raw;
        }
        return raw.substring(0, maxChars);
    }

    private List<SoftSkillEvidence> callGroq(String userPrompt) {
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
            throw new IllegalStateException("Unable to build Groq soft skill payload", ex);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(groqProperties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, groqProperties.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + groqProperties.getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        String response;
        try {
            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new IllegalStateException("Groq soft skill call failed with status " + httpResponse.statusCode());
            }
            response = httpResponse.body();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Groq soft skill call failed", ex);
        }

        return parseSoftSkills(response);
    }

    private List<SoftSkillEvidence> parseSoftSkills(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (!StringUtils.hasText(content)) {
                return List.of();
            }

            JsonNode parsed = objectMapper.readTree(content);
            JsonNode skillsNode = parsed.path("softSkills");
            if (!skillsNode.isArray()) {
                return List.of();
            }

            LinkedHashMap<String, SoftSkillEvidence> dedupe = new LinkedHashMap<>();
            for (JsonNode node : skillsNode) {
                String name = normalizeSoftSkillName(node.path("name").asText(""));
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                String evidence = node.path("evidence").asText("").trim();
                if (!StringUtils.hasText(evidence)) {
                    continue;
                }
                double confidence = Math.max(0.0d, Math.min(1.0d, node.path("confidence").asDouble(0.0d)));
                dedupe.putIfAbsent(name, new SoftSkillEvidence(name, confidence, evidence));
            }
            return dedupe.values().stream().limit(8).toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to parse soft skill response", ex);
        }
    }

    List<SoftSkillEvidence> inferByHeuristics(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        String corpus = text.toLowerCase(Locale.ROOT);
        LinkedHashMap<String, SoftSkillEvidence> out = new LinkedHashMap<>();

        addIfMatch(out, corpus,
                "leadership",
                List.of("encadre", "mentored", "led", "managed", "team lead"),
                0.72d,
                "Deduit a partir d'indices d'encadrement/coordination d'equipe dans les textes.");

        addIfMatch(out, corpus,
                "rigueur",
                List.of("tests", "test coverage", "quality", "lint", "ci/cd", "documentation"),
                0.70d,
                "Deduit via la presence de signaux de qualite logicielle (tests, CI, documentation)." );

        addIfMatch(out, corpus,
                "problem solver",
                List.of("debug", "incident", "optimiz", "troubleshoot", "root cause"),
                0.69d,
                "Deduit car le candidat decrit des analyses de pannes et resolutions techniques complexes.");

        addIfMatch(out, corpus,
                "team player",
                List.of("collabor", "cross-functional", "pair programming", "peer review", "stakeholder"),
                0.66d,
                "Deduit via des indices de collaboration transverse et revues collectives.");

        addIfMatch(out, corpus,
                "communication",
                List.of("document", "readme", "knowledge sharing", "presentation", "explain"),
                0.64d,
                "Deduit de la qualite et profondeur des elements de communication technique produits.");

        return out.values().stream().limit(8).toList();
    }

    private void addIfMatch(
            Map<String, SoftSkillEvidence> out,
            String corpus,
            String softSkill,
            List<String> needles,
            double confidence,
            String evidence
    ) {
        for (String needle : needles) {
            if (corpus.contains(needle)) {
                out.putIfAbsent(softSkill, new SoftSkillEvidence(softSkill, confidence, evidence));
                return;
            }
        }
    }

    private void upsertSoftSkills(String candidateId, List<SoftSkillEvidence> skills, String source) {
        if (skills == null || skills.isEmpty()) {
            return;
        }

        String cypher = """
                MATCH (c:Candidate {id: $candidateId})
                UNWIND $rows AS row
                MERGE (ss:SoftSkill {name: row.name})
                ON CREATE SET ss.id = randomUUID()
                MERGE (c)-[r:POSSESSES_SOFT_SKILL]->(ss)
                SET r.evidence = row.evidence,
                    r.confidence = row.confidence,
                    r.source = row.source,
                    r.updatedAtEpochMs = timestamp()
                RETURN count(ss) AS updated
                """;

        List<Map<String, Object>> rows = skills.stream()
            .map(s -> {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", normalizeSoftSkillName(s.softSkillName()));
                row.put("confidence", Math.max(0.0d, Math.min(1.0d, s.confidence())));
                row.put("evidence", StringUtils.hasText(s.evidence()) ? s.evidence().trim() : "No explicit evidence");
                row.put("source", source);
                return row;
            })
                .toList();

        neo4jClient.query(cypher)
                .bind(candidateId).to("candidateId")
                .bind(rows).to("rows")
                .fetch().all();
    }

    private String normalizeSoftSkillName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0d);
    }

    private boolean tryReserveTokens(int requested) {
        synchronized (tokenLock) {
            long now = System.currentTimeMillis();
            while (!rollingWindow.isEmpty()) {
                TokenWindowEntry head = rollingWindow.peekFirst();
                if (head == null || now - head.timestampMs <= 60_000L) {
                    break;
                }
                rollingWindow.pollFirst();
                rollingTokens -= head.tokens;
            }

            int budget = Math.max(1, groqProperties.getSoftSkillTpmBudget());
            if (rollingTokens + requested > budget) {
                return false;
            }

            rollingWindow.addLast(new TokenWindowEntry(now, requested));
            rollingTokens += requested;
            return true;
        }
    }

    private SoftSkillAnalysisJobResponse toResponse(SoftSkillJob job) {
        return new SoftSkillAnalysisJobResponse(
                job.jobId,
                job.candidateId,
                job.status,
                Math.max(600, groqProperties.getSoftSkillPollMs()),
                job.message,
                job.startedAtEpochMs,
                job.completedAtEpochMs,
                job.model,
                job.fallbackUsed,
                job.softSkills
        );
    }

    private void cleanupExpiredJobs(long now) {
        long ttlMs = Math.max(300, groqProperties.getSoftSkillJobTtlSeconds()) * 1000L;
        jobs.entrySet().removeIf(entry -> {
            Long completedAt = entry.getValue().completedAtEpochMs;
            return completedAt != null && now - completedAt > ttlMs;
        });
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private double doubleValue(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0.0d;
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

    private record EvidenceCorpus(
            String cvSignals,
            String githubSignals,
            String combinedText
    ) {
    }

    private record TokenWindowEntry(long timestampMs, int tokens) {
    }

    private static final class SoftSkillJob {
        private final String jobId;
        private final String candidateId;
        private final EvidenceCorpus corpus;
        private final long startedAtEpochMs;

        private volatile String status;
        private volatile String message;
        private volatile Long completedAtEpochMs;
        private volatile String model;
        private volatile boolean fallbackUsed;
        private volatile List<SoftSkillEvidence> softSkills;

        private SoftSkillJob(String jobId, String candidateId, EvidenceCorpus corpus, long startedAtEpochMs) {
            this.jobId = jobId;
            this.candidateId = candidateId;
            this.corpus = corpus;
            this.startedAtEpochMs = startedAtEpochMs;
            this.status = STATUS_QUEUED;
            this.message = "Behavioral analysis queued";
            this.completedAtEpochMs = null;
            this.model = "pending";
            this.fallbackUsed = false;
            this.softSkills = List.of();
        }
    }
}
