package io.github.luigidemasi.camelkit.knowledge.indexer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("Requires network access to GitHub")
class GitRepoFetcherTest {

    @TempDir
    Path tempDir;

    @Test
    void clonesRepoAndListsFiles() throws Exception {
        GitRepoFetcher fetcher = new GitRepoFetcher(tempDir);

        Path repoDir = fetcher.fetchRepo(
                "https://github.com/apache/camel-website.git",
                "main",
                "camel-website");

        assertTrue(Files.isDirectory(repoDir));
        assertTrue(Files.isDirectory(repoDir.resolve("content/security")));
    }

    @Test
    void listFilesWithPattern() throws Exception {
        GitRepoFetcher fetcher = new GitRepoFetcher(tempDir);
        Path repoDir = fetcher.fetchRepo(
                "https://github.com/apache/camel-website.git",
                "main",
                "camel-website");

        List<Path> cveFiles = fetcher.listFiles(repoDir.resolve("content/security"), "CVE-*.md");
        assertFalse(cveFiles.isEmpty(), "Should find CVE advisory files");
        assertTrue(cveFiles.stream().allMatch(p -> p.getFileName().toString().startsWith("CVE-")));
    }
}
