package com.jobrecommender.backend;

import com.jobrecommender.backend.service.GitHubIngestionService;
import com.jobrecommender.backend.service.ReadmeNlpPreprocessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReadmeNlpPreprocessorTest {

    private final ReadmeNlpPreprocessor preprocessor = new ReadmeNlpPreprocessor();

    @Test
    void shouldRemoveMarkdownNoiseAndDeduplicateTokens() {
        String readme = """
                # Project
                ![build](https://img.shields.io/badge/build-passing)
                <b>Awesome</b> project with Java Java JAVA and Kubernetes kubernetes.
                ```java
                System.out.println(\"hello\");
                ```
                docs at https://example.org
                machine learning machine learning
                """;

        GitHubIngestionService.RepoReadme repo = new GitHubIngestionService.RepoReadme(
                "alice",
                "ml-repo",
                "Java",
                List.of("kubernetes", "spring-boot"),
                "https://github.com/alice/ml-repo",
                readme
        );

        ReadmeNlpPreprocessor.PreprocessedCorpus corpus = preprocessor.preprocess(List.of(repo), 50000, 200);

        assertEquals(1, corpus.repositories().size());
        String vocab = corpus.repositories().get(0).uniqueVocabulary();

        assertTrue(vocab.contains("java"));
        assertTrue(vocab.contains("kubernetes"));
        assertTrue(vocab.contains("machine_learning"));
        assertFalse(vocab.contains("https://"));
        assertFalse(vocab.contains("system.out.println"));

        long javaCount = vocab.chars().filter(c -> c == 'j').count();
        assertTrue(javaCount > 0);
        assertTrue(corpus.duplicateTokensDropped() > 0);
        assertTrue(corpus.uniqueTokenCount() <= 200);
    }

    @Test
    void shouldHandleNullAndVeryLargeReadmeSafely() {
        String huge = "java ".repeat(20000) + "``` code block ```";

        GitHubIngestionService.RepoReadme repo1 = new GitHubIngestionService.RepoReadme(
                "bob",
                "huge",
                "",
                List.of(),
                "",
                huge
        );

        GitHubIngestionService.RepoReadme repo2 = new GitHubIngestionService.RepoReadme(
                "bob",
                "null",
                "",
                List.of(),
                "",
                null
        );

        ReadmeNlpPreprocessor.PreprocessedCorpus corpus = preprocessor.preprocess(List.of(repo1, repo2), 8000, 120);

        assertNotNull(corpus);
        assertTrue(corpus.cleanedChars() <= 16000);
        assertTrue(corpus.uniqueTokenCount() <= 120);
        assertNotNull(corpus.globalUniqueVocabulary());
    }

    @Test
    void shouldApplyPhraseCompactionOptimization() {
        String readme = "This repo focuses on natural language processing and large language model pipelines.";

        GitHubIngestionService.RepoReadme repo = new GitHubIngestionService.RepoReadme(
                "carol",
                "nlp",
                "python",
                List.of(),
                "",
                readme
        );

        ReadmeNlpPreprocessor.PreprocessedCorpus corpus = preprocessor.preprocess(List.of(repo), 12000, 300);

        String global = corpus.globalUniqueVocabulary();
        assertTrue(global.contains("natural_language_processing"));
        assertTrue(global.contains("large_language_model"));
    }
}
