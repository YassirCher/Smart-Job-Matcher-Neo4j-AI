package com.jobrecommender.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ReadmeNlpPreprocessor {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```.*?```");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MARKDOWN_BADGE_PATTERN = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z][a-z0-9_+.#-]{1,30}", Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "into", "your", "you", "are", "was",
            "were", "have", "has", "using", "used", "build", "project", "application", "readme", "about",
            "more", "than", "when", "where", "which", "will", "can", "our", "their", "its", "also",
            "please", "installation", "getting", "started", "overview", "features", "license", "contributing"
    );

    private static final List<String> COMPOUND_PHRASES = List.of(
            "machine learning", "deep learning", "natural language processing", "computer vision",
            "large language model", "retrieval augmented generation", "spring boot", "node.js",
            "azure service bus", "event hub", "kubernetes", "docker compose", "neo4j graph"
    );

    public PreprocessedCorpus preprocess(List<GitHubIngestionService.RepoReadme> repositories, int maxPayloadChars, int maxUniqueTokens) {
        if (repositories == null || repositories.isEmpty()) {
            return new PreprocessedCorpus(List.of(), "", 0, 0, 0, 0);
        }

        int safeChars = Math.max(4000, Math.min(maxPayloadChars, 120000));
        int safeTokenCap = Math.max(200, Math.min(maxUniqueTokens, 3000));

        List<RepoVocabulary> perRepo = new ArrayList<>();
        LinkedHashSet<String> globalUnique = new LinkedHashSet<>();

        int rawChars = 0;
        int cleanedChars = 0;
        int duplicateTokensDropped = 0;

        for (GitHubIngestionService.RepoReadme repo : repositories) {
            if (globalUnique.size() >= safeTokenCap) {
                break;
            }

            String original = safe(repo.readme());
            rawChars += original.length();
            String cleaned = structuralCleanup(original, safeChars);
            cleaned = compactPhrases(cleaned);
            cleanedChars += cleaned.length();

            LinkedHashSet<String> repoTokens = new LinkedHashSet<>();
            Matcher matcher = TOKEN_PATTERN.matcher(cleaned);
            while (matcher.find()) {
                if (globalUnique.size() >= safeTokenCap) {
                    break;
                }
                String token = normalizeToken(matcher.group());
                if (!StringUtils.hasText(token) || STOPWORDS.contains(token)) {
                    continue;
                }
                boolean freshRepo = repoTokens.add(token);
                boolean freshGlobal = globalUnique.add(token);
                if (!freshRepo || !freshGlobal) {
                    duplicateTokensDropped++;
                }
            }

            perRepo.add(new RepoVocabulary(
                    repo.owner(),
                    repo.repo(),
                    repo.language(),
                    repo.topics(),
                    repo.htmlUrl(),
                    String.join(" ", repoTokens)
            ));
        }

        String globalVocabulary = String.join(" ", globalUnique);
        log.info(
                "README NLP preprocessing completed: repos={}, rawChars={}, cleanedChars={}, uniqueTokens={}, duplicateTokensDropped={}",
                repositories.size(), rawChars, cleanedChars, globalUnique.size(), duplicateTokensDropped
        );

        return new PreprocessedCorpus(
                perRepo,
                globalVocabulary,
                rawChars,
                cleanedChars,
                globalUnique.size(),
                duplicateTokensDropped
        );
    }

    public String compressFreeText(String rawText, int maxChars, int maxUniqueTokens) {
        int safeChars = Math.max(2000, Math.min(maxChars, 120000));
        int safeTokenCap = Math.max(120, Math.min(maxUniqueTokens, 5000));

        String cleaned = structuralCleanup(safe(rawText), safeChars);
        cleaned = compactPhrases(cleaned);

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(cleaned);
        while (matcher.find() && unique.size() < safeTokenCap) {
            String token = normalizeToken(matcher.group());
            if (!StringUtils.hasText(token) || STOPWORDS.contains(token)) {
                continue;
            }
            unique.add(token);
        }

        return String.join(" ", unique);
    }

    private String structuralCleanup(String input, int maxChars) {
        if (!StringUtils.hasText(input)) {
            return "";
        }

        String bounded = input.length() > maxChars ? input.substring(0, maxChars) : input;
        String noCode = CODE_BLOCK_PATTERN.matcher(bounded).replaceAll(" ");
        String noUrl = URL_PATTERN.matcher(noCode).replaceAll(" ");
        String noHtml = HTML_TAG_PATTERN.matcher(noUrl).replaceAll(" ");
        String noBadge = MARKDOWN_BADGE_PATTERN.matcher(noHtml).replaceAll(" ");

        return noBadge
                .replace('`', ' ')
                .replace('|', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String compactPhrases(String input) {
        String result = input.toLowerCase(Locale.ROOT);
        for (String phrase : COMPOUND_PHRASES) {
            String compact = phrase.replace(' ', '_');
            result = result.replace(phrase, compact);
        }
        return result;
    }

    private String normalizeToken(String token) {
        String normalized = safe(token).toLowerCase(Locale.ROOT).trim();
        normalized = normalized.replaceAll("^[^a-z0-9]+", "");
        normalized = normalized.replaceAll("[^a-z0-9_+.#-]+$", "");
        if (!StringUtils.hasText(normalized) || normalized.length() < 2) {
            return "";
        }
        return normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record RepoVocabulary(
            String owner,
            String repo,
            String language,
            List<String> topics,
            String htmlUrl,
            String uniqueVocabulary
    ) {
    }

    public record PreprocessedCorpus(
            List<RepoVocabulary> repositories,
            String globalUniqueVocabulary,
            int rawChars,
            int cleanedChars,
            int uniqueTokenCount,
            int duplicateTokensDropped
    ) {
    }
}
