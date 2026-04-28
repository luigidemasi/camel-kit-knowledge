package io.github.luigidemasi.camelkit.knowledge.indexer.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseNotesChunkerTest {

    private final ReleaseNotesChunker chunker = new ReleaseNotesChunker();

    @Test
    void singleJiraRow() {
        String md = """
                ## 4.14.4 fixed issues

                | Issue | Description |
                |-------|-------------|
                | [CAMEL-22784](https://issues.apache.org/jira/browse/CAMEL-22784) | Fix NPE in camel-kafka |
                """;

        ReleaseNotesChunker.ChunkResult result = chunker.chunk(md);

        assertEquals(1, result.issues().size());
        ReleaseNotesChunker.ResolvedIssue issue = result.issues().get(0);
        assertEquals(List.of("CAMEL-22784"), issue.jiraIds());
        assertEquals("Fix NPE in camel-kafka", issue.description());
        assertEquals("https://issues.apache.org/jira/browse/CAMEL-22784", issue.url());
        assertEquals("4.14.4 fixed issues", issue.sectionTitle());
    }

    @Test
    void multipleJiraRows() {
        String md = """
                ## Resolved issues

                | Issue | Description |
                |-------|-------------|
                | [CAMEL-22784](https://issues.apache.org/jira/browse/CAMEL-22784) | Upgrade to Quarkus 3.8 |
                | [CAMEL-22832](https://issues.apache.org/jira/browse/CAMEL-22832) | Update Spring Boot to 3.3 |
                | [CAMEL-22900](https://issues.apache.org/jira/browse/CAMEL-22900) | Add camel-langchain4j |
                """;

        ReleaseNotesChunker.ChunkResult result = chunker.chunk(md);

        assertEquals(3, result.issues().size());
        assertEquals("CAMEL-22784", result.issues().get(0).jiraIds().get(0));
        assertEquals("CAMEL-22832", result.issues().get(1).jiraIds().get(0));
        assertEquals("CAMEL-22900", result.issues().get(2).jiraIds().get(0));
    }

    @Test
    void upstreamCamelReferenceExtracted() {
        String md = """
                ## Fixed issues

                | Issue | Description |
                |-------|-------------|
                | [CAMEL-22832](https://issues.apache.org/jira/browse/CAMEL-22832) | Backport [CAMEL-22784] fix for HTTP timeout |
                """;

        ReleaseNotesChunker.ChunkResult result = chunker.chunk(md);

        assertEquals(1, result.issues().size());
        ReleaseNotesChunker.ResolvedIssue issue = result.issues().get(0);
        assertEquals(List.of("CAMEL-22832", "CAMEL-22784"), issue.jiraIds());
    }

    @Test
    void camelPrimaryIdNotDuplicated() {
        String md = """
                ## Fixed issues

                | Issue | Description |
                |-------|-------------|
                | [CAMEL-22784](https://issues.apache.org/jira/browse/CAMEL-22784) | Fix for CAMEL-22784 regression |
                """;

        ReleaseNotesChunker.ChunkResult result = chunker.chunk(md);

        assertEquals(1, result.issues().size());
        // CAMEL-22784 appears once (primary), not duplicated from description
        assertEquals(List.of("CAMEL-22784"), result.issues().get(0).jiraIds());
    }

    @Test
    void nonTableContentGoesToOtherSections() {
        String md = """
                ## Overview

                This release includes important bug fixes.

                ## Fixed issues

                | Issue | Description |
                |-------|-------------|
                | [CAMEL-22784](https://issues.apache.org/jira/browse/CAMEL-22784) | Fix NPE |

                ## Known issues

                There is a known issue with camel-kafka on Windows.
                """;

        ReleaseNotesChunker.ChunkResult result = chunker.chunk(md);

        assertEquals(1, result.issues().size());
        // Overview and Known issues should be in otherSections
        assertFalse(result.otherSections().isEmpty());
        boolean hasOverview = result.otherSections().stream()
                .anyMatch(s -> s.title().contains("Overview"));
        boolean hasKnownIssues = result.otherSections().stream()
                .anyMatch(s -> s.title().contains("Known issues"));
        assertTrue(hasOverview, "Overview section should be in otherSections");
        assertTrue(hasKnownIssues, "Known issues section should be in otherSections");
    }

    @Test
    void numberedTableRow() {
        // Some release notes have numbered rows: | 1 | [CAMEL-22784](url) | desc |
        String md = """
                ## Fixed issues

                | # | Issue | Description |
                |---|-------|-------------|
                | 1 | [CAMEL-22784](https://issues.apache.org/jira/browse/CAMEL-22784) | Fix NPE |
                """;

        ReleaseNotesChunker.ChunkResult result = chunker.chunk(md);

        assertEquals(1, result.issues().size());
        assertEquals("CAMEL-22784", result.issues().get(0).jiraIds().get(0));
    }

    @Test
    void multipleUpstreamReferences() {
        String md = """
                ## Fixed issues

                | Issue | Description |
                |-------|-------------|
                | [CAMEL-23000](https://issues.apache.org/jira/browse/CAMEL-23000) | Backport [CAMEL-22832] and [CAMEL-22900] fixes |
                """;

        ReleaseNotesChunker.ChunkResult result = chunker.chunk(md);

        assertEquals(1, result.issues().size());
        assertEquals(List.of("CAMEL-23000", "CAMEL-22832", "CAMEL-22900"),
                result.issues().get(0).jiraIds());
    }

    @Test
    void emptyMarkdownReturnsEmpty() {
        ReleaseNotesChunker.ChunkResult result = chunker.chunk("");

        assertTrue(result.issues().isEmpty());
    }

    @Test
    void headingContextCarriesThrough() {
        String md = """
                ## Apache Camel 4.14.4

                ### Resolved issues

                | Issue | Description |
                |-------|-------------|
                | [CAMEL-22784](https://issues.apache.org/jira/browse/CAMEL-22784) | Fix NPE |

                ### Bug fixes

                | Issue | Description |
                |-------|-------------|
                | [CAMEL-22900](https://issues.apache.org/jira/browse/CAMEL-22900) | Fix timeout |
                """;

        ReleaseNotesChunker.ChunkResult result = chunker.chunk(md);

        assertEquals(2, result.issues().size());
        assertEquals("Resolved issues", result.issues().get(0).sectionTitle());
        assertEquals("Bug fixes", result.issues().get(1).sectionTitle());
    }
}
