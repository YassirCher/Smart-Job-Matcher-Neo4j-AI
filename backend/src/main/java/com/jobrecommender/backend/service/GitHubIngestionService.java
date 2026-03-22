package com.jobrecommender.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobrecommender.backend.config.GitHubAnalyzerProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class GitHubIngestionService {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private final GitHubAnalyzerProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

    public GitHubReadmePayload ingest(String githubUsername, List<String> repositoryUrls, Integer maxReposOverride, Boolean includeForksOverride) {
        int maxRepos = sanitizeMaxRepos(maxReposOverride);
        boolean includeForks = includeForksOverride != null ? includeForksOverride : properties.isIncludeForks();

        List<RepoDescriptor> repos = new ArrayList<>();
        if (StringUtils.hasText(githubUsername)) {
            String username = githubUsername.trim();
            try {
                repos.addAll(fetchReposByUser(username, maxRepos, includeForks));
            } catch (IllegalArgumentException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("403")) {
                    repos.addAll(fetchReposByUserFromPublicPage(username, maxRepos));
                } else {
                    throw ex;
                }
            }
        }
        if (repositoryUrls != null && !repositoryUrls.isEmpty()) {
            for (String url : repositoryUrls) {
                RepoDescriptor parsed = parseRepoUrl(url);
                if (parsed != null) {
                    repos.add(parsed);
                }
            }
        }

        List<RepoDescriptor> unique = deduplicateRepos(repos);
        if (unique.size() > maxRepos) {
            unique = unique.subList(0, maxRepos);
        }

        int totalBudget = Math.max(8000, properties.getMaxTotalPayloadChars());
        int perRepoBudget = Math.max(1500, properties.getMaxReadmeCharsPerRepo());
        int consumed = 0;

        List<RepoReadme> readmes = new ArrayList<>();
        for (RepoDescriptor repo : unique) {
            if (consumed >= totalBudget) {
                break;
            }

            String readme = fetchReadme(repo.owner(), repo.repo());
            if (!StringUtils.hasText(readme)) {
                continue;
            }

            String compact = collapseWhitespace(readme);
            int allowed = Math.min(perRepoBudget, totalBudget - consumed);
            if (compact.length() > allowed) {
                compact = compact.substring(0, allowed);
            }

            consumed += compact.length();
            readmes.add(new RepoReadme(
                    repo.owner(),
                    repo.repo(),
                    repo.language(),
                    repo.topics(),
                    repo.htmlUrl(),
                    compact
            ));
        }

        return new GitHubReadmePayload(unique.size(), readmes);
    }

    private List<RepoDescriptor> fetchReposByUser(String user, int maxRepos, boolean includeForks) {
        String encodedUser = URLEncoder.encode(user, StandardCharsets.UTF_8);
        String uri = properties.getApiBaseUrl() + "/users/" + encodedUser + "/repos?per_page=100&sort=updated";

        HttpRequest request = withCommonHeaders(HttpRequest.newBuilder(URI.create(uri))).GET().build();
        String body = sendForBody(request);
        if (!StringUtils.hasText(body)) {
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isArray()) {
                return List.of();
            }

            List<RepoDescriptor> repos = new ArrayList<>();
            for (JsonNode node : root) {
                if (repos.size() >= maxRepos) {
                    break;
                }

                boolean fork = node.path("fork").asBoolean(false);
                if (!includeForks && fork) {
                    continue;
                }

                String owner = node.path("owner").path("login").asText("");
                String repo = node.path("name").asText("");
                String language = node.path("language").asText("");
                String htmlUrl = node.path("html_url").asText("");

                if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo)) {
                    continue;
                }

                List<String> topics = new ArrayList<>();
                JsonNode topicsNode = node.path("topics");
                if (topicsNode.isArray()) {
                    for (JsonNode topic : topicsNode) {
                        String t = topic.asText("");
                        if (StringUtils.hasText(t)) {
                            topics.add(t.trim());
                        }
                    }
                }

                repos.add(new RepoDescriptor(owner, repo, language, topics, htmlUrl));
            }

            return repos;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to parse GitHub repositories response", ex);
        }
    }

    private String fetchReadme(String owner, String repo) {
        String encodedOwner = URLEncoder.encode(owner, StandardCharsets.UTF_8);
        String encodedRepo = URLEncoder.encode(repo, StandardCharsets.UTF_8);
        String uri = properties.getApiBaseUrl() + "/repos/" + encodedOwner + "/" + encodedRepo + "/readme";

        HttpRequest request = withCommonHeaders(HttpRequest.newBuilder(URI.create(uri)))
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        String body;
        try {
            body = sendForBody(request);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("403")) {
                return fetchReadmeFromRaw(owner, repo);
            }
            throw ex;
        }

        if (!StringUtils.hasText(body)) {
            return fetchReadmeFromRaw(owner, repo);
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            String encoding = root.path("encoding").asText("");
            String content = root.path("content").asText("");
            if (!StringUtils.hasText(content)) {
                return "";
            }

            if ("base64".equalsIgnoreCase(encoding)) {
                byte[] decoded = Base64.getMimeDecoder().decode(content);
                return new String(decoded, StandardCharsets.UTF_8);
            }

            return content;
        } catch (Exception ex) {
            return "";
        }
    }

    private List<RepoDescriptor> fetchReposByUserFromPublicPage(String user, int maxRepos) {
        String encodedUser = URLEncoder.encode(user, StandardCharsets.UTF_8);
        String uri = "https://github.com/" + encodedUser + "?tab=repositories";

        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "text/html")
                .header("User-Agent", "job-recommender-github-analyzer")
                .GET()
                .build();

        String html;
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            html = response.body();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        Pattern pattern = Pattern.compile("href=\"/" + Pattern.quote(user) + "/([A-Za-z0-9_.-]+)\"");
        Matcher matcher = pattern.matcher(html);
        Set<String> seen = new LinkedHashSet<>();
        List<RepoDescriptor> repos = new ArrayList<>();

        while (matcher.find() && repos.size() < maxRepos) {
            String repo = matcher.group(1);
            if (!StringUtils.hasText(repo) || !seen.add(repo.toLowerCase(Locale.ROOT))) {
                continue;
            }

            repos.add(new RepoDescriptor(
                    user,
                    repo,
                    "",
                    List.of(),
                    "https://github.com/" + user + "/" + repo
            ));
        }

        return repos;
    }

    private String fetchReadmeFromRaw(String owner, String repo) {
        List<String> candidates = List.of(
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/README.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/readme.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/README.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/readme.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/README.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/readme.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/README.rst",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/README.rst",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/README.rst"
        );

        for (String url : candidates) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(HTTP_TIMEOUT)
                        .header("User-Agent", "job-recommender-github-analyzer")
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() >= 200 && resp.statusCode() < 300 && StringUtils.hasText(resp.body())) {
                    return resp.body();
                }
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return "";
    }

    private HttpRequest.Builder withCommonHeaders(HttpRequest.Builder builder) {
        builder.timeout(HTTP_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "job-recommender-github-analyzer");

        if (StringUtils.hasText(properties.getToken())) {
            builder.header("Authorization", "Bearer " + properties.getToken().trim());
        }
        return builder;
    }

    private String sendForBody(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return response.body();
            }
            if (code == 404) {
                return "";
            }
            if (code == 403) {
                throw new IllegalArgumentException("GitHub API access forbidden or rate-limited (403). Configure GITHUB_TOKEN to continue GitHub analysis.");
            }
            if (code == 429) {
                throw new IllegalArgumentException("GitHub API rate limit reached (429). Retry later or configure GITHUB_TOKEN.");
            }
            throw new IllegalStateException("GitHub API call failed with status " + code);
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub API call failed", ex);
        }
    }

    private RepoDescriptor parseRepoUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }

        String normalized = url.trim().replace("https://github.com/", "").replace("http://github.com/", "");
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.toLowerCase(Locale.ROOT).endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }

        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return null;
        }

        return new RepoDescriptor(parts[0], parts[1], "", List.of(), "https://github.com/" + parts[0] + "/" + parts[1]);
    }

    private List<RepoDescriptor> deduplicateRepos(List<RepoDescriptor> repos) {
        List<RepoDescriptor> unique = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RepoDescriptor repo : repos) {
            String key = (repo.owner() + "/" + repo.repo()).toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                unique.add(repo);
            }
        }
        return unique;
    }

    private int sanitizeMaxRepos(Integer maxReposOverride) {
        int defaultValue = Math.max(1, properties.getMaxRepos());
        if (maxReposOverride == null) {
            return defaultValue;
        }
        return Math.max(1, Math.min(maxReposOverride, 50));
    }

    private String collapseWhitespace(String input) {
        return input.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    public record RepoDescriptor(
            String owner,
            String repo,
            String language,
            List<String> topics,
            String htmlUrl
    ) {
    }

    public record RepoReadme(
            String owner,
            String repo,
            String language,
            List<String> topics,
            String htmlUrl,
            String readme
    ) {
    }

    public record GitHubReadmePayload(
            int repositoriesDiscovered,
            List<RepoReadme> repositories
    ) {
    }
}
